package ru.home.ivajoin;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * For up to 2 minutes after a meeting shortcut is tapped, walks the IVA Connect join flow inside
 * that one app: on every screen fills the fields it recognises (server, meeting ID, your name,
 * password) and presses the join button. Done once the screen with the password is submitted.
 * Idle the rest of the time.
 */
public class JoinService extends AccessibilityService {
    private enum Kind { NONE, SERVER, ID, NAME, PASSWORD }

    private static final String[] PW_HINTS = {"парол", "password", "passcode", "pin", "пин-код", "пинкод"};
    private static final String[] SERVER_HINTS = {"сервер", "server", "адрес", "address", "host", "хост", "домен", "domain", "url"};
    private static final String[] ID_HINTS = {"идентификатор", "номер", "конференц", "мероприят", "комнат", "встреч",
            "room", "meeting", "conference", "event", "conf_id", "confid", "meetingid", "roomid"};
    private static final String[] EMAIL_HINTS = {"e-mail", "email", "почт"};
    // account login fields: never put the display name there
    private static final String[] SKIP_HINTS = {"логин", "login", "e-mail", "email", "почт", "пользовател", "username",
            "поиск", "search", "сообщ", "message", "чат", "chat"};
    private static final String[] NAME_HINTS = {"имя", "name", "фио", "представ", "как к вам", "как вас", "nick", "псевдоним"};

    private static final long RETRY_MS = 700;
    private static final long AFTER_CLICK_MS = 1500;
    private static final int MAX_CLICKS = 10;
    private static final int MAX_SET_TRIES = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable step = this::step;
    private long token = -1;
    private boolean posted;
    private int clicks;
    private boolean cancelled; // already went Back from a sign-in screen with the wrong server during this join
    private final Map<AccessibilityNodeInfo, Integer> setTries = new HashMap<>();
    private String lastDump = "";

    static boolean isEnabled(Context c) {
        ComponentName me = new ComponentName(c, JoinService.class);
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        for (String s : list.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (me.equals(cn)) return true;
        }
        return false;
    }

    // ---- scope: the system delivers events only from the app being joined, and only while a join is armed ----

    private final SharedPreferences.OnSharedPreferenceChangeListener prefs = (sp, key) -> {
        if (key == null || key.startsWith("p_")) updateScope();
    };
    private final Runnable rescope = this::updateScope;

    @Override
    protected void onServiceConnected() {
        Store.listenPending(this, prefs);
        updateScope();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Store.unlistenPending(this, prefs);
        handler.removeCallbacks(rescope);
        return super.onUnbind(intent);
    }

    private void updateScope() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        handler.removeCallbacks(rescope);
        String[] pkgs;
        long left = Store.pendingLeftMs(this);
        if (left > 0) {
            String pkg = Store.pendingPkg(this);
            pkgs = pkg == null ? null : new String[]{pkg}; // link opened in an app we couldn't name in advance
            handler.postDelayed(rescope, left + 100); // narrow back down when time runs out
        } else {
            pkgs = new String[]{getPackageName()}; // idle: hear nothing but ourselves
        }
        if (Arrays.equals(info.packageNames, pkgs)) return;
        info.packageNames = pkgs;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (Store.pendingRoom(this) < 0) return;
        String pkg = Store.pendingPkg(this);
        CharSequence ep = e.getPackageName();
        if (pkg != null && (ep == null || !pkg.contentEquals(ep))) return;
        if (!posted) schedule(250); // don't let a stream of events postpone the step forever
    }

    @Override
    public void onInterrupt() {}

    private void schedule(long delay) {
        handler.removeCallbacks(step);
        handler.postDelayed(step, delay);
        posted = true;
    }

    private void finish() {
        Store.clearPending(this);
        setTries.clear();
        handler.removeCallbacks(step);
        posted = false;
    }

    private void step() {
        posted = false;
        long idx = Store.pendingRoom(this);
        if (idx < 0) return; // nothing armed, or time ran out

        long t = Store.pendingToken(this);
        if (t != token) { // a new shortcut tap: start over
            token = t;
            clicks = 0;
            cancelled = false;
            setTries.clear();
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = Store.pendingPkg(this);
        if (root == null || (pkg != null && (root.getPackageName() == null || !pkg.contentEquals(root.getPackageName())))) {
            schedule(RETRY_MS);
            return;
        }

        if (clicks >= MAX_CLICKS) {
            finish();
            return;
        }

        // The IVA Connect flow, screen by screen:
        //   0. server address (only when IVA lost its settings) -> type the room's server, press Connect
        //   1. "Sign in" (login + password)   -> press "Enter with ID", touch no fields;
        //      if it names another server than the room's, press Back to get to 0.
        //   2. a field for the meeting ID      -> type ID, press "Join"
        //   3. "Join event" card, no fields    -> press "Join"
        //   4. "Guest" tab with "Name"         -> type name, press "Join"
        //   5. "Event passcode"                -> type password, press "Join", done

        List<AccessibilityNodeInfo> edits = new ArrayList<>();
        collectEditable(root, edits, 0);

        // 0. server address screen: IVA shows it instead of sign-in after its settings are reset
        for (AccessibilityNodeInfo f : edits) {
            if (classify(f) != Kind.SERVER) continue;
            String server = serverFor(idx);
            if (server.isEmpty()) break; // nothing to type: leave it to the user
            fillThenPress(root, f, Kind.SERVER, server, CONNECT);
            return;
        }

        // 1. sign-in screen
        AccessibilityNodeInfo enterWithId = findButton(root, ENTER_WITH_ID);
        if (enterWithId != null) {
            // IVA keeps the last server: if it's not the room's one, Back returns to the server screen (0.), once
            if (!cancelled && wrongServer(root, idx)) {
                cancelled = true;
                performGlobalAction(GLOBAL_ACTION_BACK);
                clicks++;
                schedule(AFTER_CLICK_MS);
                return;
            }
            click(enterWithId);
            return;
        }

        // 5. passcode screen
        for (AccessibilityNodeInfo f : edits) {
            if (classify(f) != Kind.PASSWORD) continue;
            String pw = Store.password(this, idx);
            if (pw.isEmpty()) break; // no password saved: leave it to the user
            if (fillThenJoin(root, f, Kind.PASSWORD, pw)) finish();
            return;
        }

        if (edits.size() == 1 && classify(edits.get(0)) != Kind.SERVER) {
            AccessibilityNodeInfo f = edits.get(0);
            if (hasText(root, GUEST)) {
                // 4. guest name screen
                String name = Store.userName(this);
                if (!name.isEmpty()) {
                    boolean noPw = !Store.hasPassword(this, idx);
                    if (fillThenJoin(root, f, Kind.NAME, name) && noPw) finish(); // no password: this was the last screen
                    return;
                }
            } else {
                // 2. meeting ID screen
                String id = Store.confId(this, idx);
                if (!id.isEmpty()) {
                    fillThenJoin(root, f, Kind.ID, id);
                    return;
                }
            }
        }

        // 3. "Join event" card
        if (edits.isEmpty()) {
            AccessibilityNodeInfo join = findButton(root, JOIN);
            if (join != null) {
                click(join);
                return;
            }
        }

        remember(root);
        schedule(RETRY_MS);
    }

    private static final String ENTER_WITH_ID = "Enter with ID, Войти по ID, Войти с ID, Вход по ID";
    private static final String JOIN = "Join, Присоединиться, Подключиться, Войти, Next, Далее, Continue, Продолжить";
    private static final String GUEST = "Guest, Гость";
    private static final String CONNECT = "Continue, Продолжить, Connect, Подключиться, Next, Далее";

    private static final Pattern HOST = Pattern.compile(
            "(?<![@\\p{L}\\p{N}.-])(?:https?://)?((?:[\\p{L}\\p{N}-]+\\.)+\\p{L}{2,})", Pattern.CASE_INSENSITIVE);

    /** True if the screen names a server and none of the names is the room's server. */
    private boolean wrongServer(AccessibilityNodeInfo root, long idx) {
        String want = hostOf(Store.server(this, idx));
        if (want.isEmpty()) return false; // room without a server: take whatever IVA has
        List<String> shown = new ArrayList<>();
        collectHosts(root, shown, 0);
        if (shown.isEmpty()) return false; // IVA doesn't show the server here: nothing to compare
        for (String h : shown) if (h.equals(want)) return false;
        return true;
    }

    private void collectHosts(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 40) return;
        if (n.isVisibleToUser() && !n.isEditable()) { // typed text is the user's: only labels count
            CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
            if (t != null) {
                Matcher m = HOST.matcher(t);
                while (m.find()) out.add(hostOf(m.group(1)));
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) collectHosts(n.getChild(i), out, depth + 1);
    }

    /** "https://IVA.example.ru:443/x" -> "iva.example.ru" */
    static String hostOf(String s) {
        s = Store.cleanServer(s).toLowerCase(Locale.ROOT);
        int cut = s.length();
        for (char c : new char[]{'/', ':', '?', '#'}) {
            int i = s.indexOf(c);
            if (i >= 0) cut = Math.min(cut, i);
        }
        s = s.substring(0, cut);
        return s.startsWith("www.") ? s.substring(4) : s;
    }

    /** The room's server, or the most recently used saved one if the room has none. */
    private String serverFor(long idx) {
        String s = Store.cleanServer(Store.server(this, idx));
        if (!s.isEmpty()) return s;
        List<String> saved = Servers.get(this).all();
        return saved.isEmpty() ? "" : saved.get(0);
    }

    /** Types the value if the field doesn't hold it yet, otherwise presses Join. True once Join is pressed. */
    private boolean fillThenJoin(AccessibilityNodeInfo root, AccessibilityNodeInfo f, Kind k, String value) {
        return fillThenPress(root, f, k, value, JOIN);
    }

    private boolean fillThenPress(AccessibilityNodeInfo root, AccessibilityNodeInfo f, Kind k, String value, String buttons) {
        if (!alreadyHolds(f, k, value)) {
            int tries = setTries.containsKey(f) ? setTries.get(f) : 0;
            if (tries < MAX_SET_TRIES) { // after that accept what the app reformatted it to
                setTries.put(f, tries + 1);
                setText(f, value);
                schedule(500); // let the app enable Join
                return false;
            }
        }
        AccessibilityNodeInfo join = findButton(root, buttons);
        if (join == null) {
            remember(root);
            schedule(RETRY_MS);
            return false;
        }
        click(join);
        return true;
    }

    private void click(AccessibilityNodeInfo btn) {
        btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        clicks++;
        schedule(AFTER_CLICK_MS);
    }

    private static boolean hasText(AccessibilityNodeInfo root, String labels) {
        for (String raw : labels.split(",")) {
            String label = raw.trim();
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(label);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
                if (n.isVisibleToUser() && t != null && label.equalsIgnoreCase(t.toString().trim())) return true;
            }
        }
        return false;
    }

    private boolean alreadyHolds(AccessibilityNodeInfo f, Kind k, String want) {
        if (k == Kind.PASSWORD) return setTries.containsKey(f); // can't read a password back
        CharSequence cur = f.getText();
        if (cur == null) return false;
        String hint = f.getHintText() == null ? null : f.getHintText().toString();
        if (hint != null && hint.contentEquals(cur)) return false; // empty field reporting its hint
        String a = norm(cur.toString()), b = norm(k == Kind.SERVER ? Store.cleanServer(want) : want);
        return !b.isEmpty() && a.contains(b);
    }

    private static void setText(AccessibilityNodeInfo f, String value) {
        f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        f.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    // ---- recognising fields ----

    private static Kind classify(AccessibilityNodeInfo f) {
        if (f.isPassword()) return Kind.PASSWORD;
        String d = describe(f);
        if (containsAny(d, PW_HINTS)) return Kind.PASSWORD;
        if (containsAny(d, EMAIL_HINTS)) return Kind.NONE; // before SERVER: "адрес почты"
        if (containsAny(d, SERVER_HINTS)) return Kind.SERVER; // before NAME: "имя сервера"
        if (containsAny(d, ID_HINTS) || hasWord(d, "id")) return Kind.ID;
        if (containsAny(d, SKIP_HINTS)) return Kind.NONE;
        if (containsAny(d, NAME_HINTS)) return Kind.NAME;
        return Kind.NONE;
    }

    /** Hint, label, description and view id of a field (never its typed text). */
    private static String describe(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        if (n.getHintText() != null) sb.append(n.getHintText()).append(' ');
        if (n.getContentDescription() != null) sb.append(n.getContentDescription()).append(' ');
        String id = n.getViewIdResourceName(); // "pkg:id/entry" -> keep only "entry"
        if (id != null) sb.append(id.substring(id.lastIndexOf('/') + 1)).append(' ');
        AccessibilityNodeInfo label = n.getLabeledBy();
        if (label != null && label.getText() != null) sb.append(label.getText()).append(' ');
        AccessibilityNodeInfo parent = n.getParent();
        if (parent != null) {
            // Material text fields keep the floating label on the wrapper
            if (parent.getHintText() != null) sb.append(parent.getHintText()).append(' ');
            if (parent.getContentDescription() != null) sb.append(parent.getContentDescription()).append(' ');
            // a caption right above the field
            for (int i = 0; i < parent.getChildCount(); i++) {
                AccessibilityNodeInfo c = parent.getChild(i);
                if (c == null) continue;
                if (c.equals(n)) {
                    if (i > 0) {
                        AccessibilityNodeInfo prev = parent.getChild(i - 1);
                        if (prev != null && !prev.isEditable() && prev.getText() != null) sb.append(prev.getText()).append(' ');
                    }
                    break;
                }
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private void collectEditable(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40) return;
        if (n.isEditable() && n.isVisibleToUser()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectEditable(n.getChild(i), out, depth + 1);
    }

    private static AccessibilityNodeInfo findButton(AccessibilityNodeInfo root, String labels) {
        for (String raw : labels.split(",")) {
            String label = raw.trim();
            if (label.isEmpty()) continue;
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(label);
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                if (n.isEditable() || !n.isVisibleToUser()) continue;
                CharSequence txt = n.getText() != null ? n.getText() : n.getContentDescription();
                if (txt == null || !label.equalsIgnoreCase(txt.toString().trim())) continue; // exact label only
                AccessibilityNodeInfo c = clickableAncestor(n);
                if (c != null && c.isEnabled()) return c;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        for (int i = 0; n != null && i < 6; i++, n = n.getParent()) {
            if (n.isClickable()) return n;
        }
        return null;
    }

    private static boolean containsAny(String s, String[] words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static boolean hasWord(String s, String word) {
        for (String t : s.split("[^\\p{L}\\p{N}]+|_")) if (t.equals(word)) return true;
        return false;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}.]", "");
    }

    // ---- diagnostics shown in the app, so labels can be tuned ----

    private void remember(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        sb.append(root.getPackageName()).append('\n');
        dump(root, sb, 0, new int[]{0});
        String s = sb.toString();
        if (!s.equals(lastDump)) {
            lastDump = s;
            Store.setLastSeen(this, s);
        }
    }

    private void dump(AccessibilityNodeInfo n, StringBuilder sb, int depth, int[] count) {
        if (n == null || depth > 40 || count[0] > 60) return;
        if (n.isVisibleToUser()) {
            if (n.isEditable()) {
                // never record what is typed into fields, only how they are labelled
                sb.append("[поле → ").append(kindName(classify(n))).append("] ")
                        .append(n.getHintText() == null ? "" : n.getHintText()).append('\n');
                count[0]++;
            } else {
                CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
                if (t != null && t.toString().trim().length() > 0) {
                    sb.append(n.isClickable() ? "[кнопка] " : "").append(t.toString().trim()).append('\n');
                    count[0]++;
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) dump(n.getChild(i), sb, depth + 1, count);
    }

    private static String kindName(Kind k) {
        switch (k) {
            case SERVER: return "сервер";
            case ID: return "ID";
            case NAME: return "имя";
            case PASSWORD: return "пароль";
            default: return "?";
        }
    }
}
