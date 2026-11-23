package ru.home.ivajoin;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    // ---- palette ----
    private int bg, surface, ink, muted, line, accent, accentSoft, onAccent, warn, warnSoft, danger;
    /** Room badge colours; the home-screen shortcut uses the same one. */
    private static final int[] BADGES = {0xFF2F8F7F, 0xFF4B5BB5, 0xFFB7791F, 0xFF8E4C8A, 0xFF4F6D7A};

    private LinearLayout serviceBox;
    private LinearLayout roomList;
    private LinearLayout serverBox;
    private TextView modeApp, modeLink;
    private TextView ivaApp;
    private TextView lastSeen;
    private long expanded = -1;
    private Editor openEditor;

    /** Inputs of the room that is open right now. */
    private static final class Editor {
        long room;
        EditText label, conf, password;
        AutoCompleteTextView server;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            bg = 0xFF0F1A19; surface = 0xFF172524; ink = 0xFFE3EDEB; muted = 0xFF8FA3A0; line = 0xFF2A3B39;
            accent = 0xFF4FB8A5; accentSoft = 0xFF1C3330; onAccent = 0xFF06211D;
            warn = 0xFFE3A94B; warnSoft = 0xFF33280F; danger = 0xFFE57B6E;
        } else {
            bg = 0xFFEEF2F1; surface = 0xFFFFFFFF; ink = 0xFF1B2B2A; muted = 0xFF667775; line = 0xFFD8E0DE;
            accent = 0xFF2F8F7F; accentSoft = 0xFFE3F0ED; onAccent = 0xFFFFFFFF;
            warn = 0xFF8A5A12; warnSoft = 0xFFFBEFD9; danger = 0xFFB4442F;
        }
        if (getActionBar() != null) getActionBar().hide();
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        if (!night) w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(40));

        TextView title = text("IVA Автовход", 28, ink, Typeface.BOLD);
        root.addView(title);
        TextView sub = text("Касание по «Войти», и IVA Connect сама дойдёт до митинга.", 15, muted, Typeface.NORMAL);
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        serviceBox = new LinearLayout(this);
        serviceBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(serviceBox);

        // ---- your name ----
        root.addView(section("Ваше имя в митинге"));
        EditText name = input("Например, Александр", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText(Store.userName(this));
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { Store.setUserName(MainActivity.this, s.toString().trim()); }
        });
        root.addView(name);

        // ---- how to open ----
        root.addView(section("Как открывать"));
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setBackground(round(surface, dp(14), line));
        modeApp = segment("Приложение IVA");
        modeLink = segment("По ссылке");
        modeApp.setOnClickListener(v -> { Store.setMode(this, Store.MODE_APP); renderMode(); });
        modeLink.setOnClickListener(v -> { Store.setMode(this, Store.MODE_LINK); renderMode(); });
        seg.addView(modeApp, new LinearLayout.LayoutParams(0, dp(40), 1));
        seg.addView(modeLink, new LinearLayout.LayoutParams(0, dp(40), 1));
        root.addView(seg);

        // ---- rooms ----
        root.addView(section("Комнаты"));
        roomList = new LinearLayout(this);
        roomList.setOrientation(LinearLayout.VERTICAL);
        roomList.setBackground(round(surface, dp(20), line));
        roomList.setClipToOutline(true);
        LayoutTransition lt = new LayoutTransition();
        lt.enableTransitionType(LayoutTransition.CHANGING);
        roomList.setLayoutTransition(lt);
        root.addView(roomList);
        TextView add = textButton("+ Добавить комнату", accent);
        add.setOnClickListener(v -> {
            saveQuietly();
            expanded = Store.addRoom(this);
            renderRooms();
        });
        root.addView(add);

        // ---- servers ----
        root.addView(section("Сохранённые серверы"));
        serverBox = new LinearLayout(this);
        serverBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(serverBox);

        // ---- more ----
        TextView more = section("Ещё ▾");
        root.addView(more);
        LinearLayout moreBox = new LinearLayout(this);
        moreBox.setOrientation(LinearLayout.VERTICAL);
        moreBox.setVisibility(View.GONE);
        more.setOnClickListener(v -> {
            boolean show = moreBox.getVisibility() != View.VISIBLE;
            moreBox.setVisibility(show ? View.VISIBLE : View.GONE);
            more.setText(show ? "Ещё ▴" : "Ещё ▾");
        });
        ivaApp = text("", 15, ink, Typeface.NORMAL);
        moreBox.addView(ivaApp);
        TextView pick = textButton("Выбрать другое приложение", accent);
        pick.setOnClickListener(v -> pickIvaApp());
        moreBox.addView(pick);
        TextView diagTitle = text("Где автовход остановился в прошлый раз", 15, ink, Typeface.BOLD);
        diagTitle.setPadding(0, dp(16), 0, dp(6));
        moreBox.addView(diagTitle);
        lastSeen = text("", 13, muted, Typeface.NORMAL);
        lastSeen.setTypeface(Typeface.MONOSPACE);
        lastSeen.setTextIsSelectable(true);
        moreBox.addView(lastSeen);
        root.addView(moreBox);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);

        if (Store.ivaPkg(this).isEmpty()) autoDetectIva();
        if (Store.rooms(this).isEmpty()) expanded = Store.addRoom(this);
        // rooms saved before the server list existed
        if (Servers.get(this).all().isEmpty()) {
            for (long r : Store.rooms(this)) Servers.get(this).touch(Store.server(this, r));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderService();
        renderMode();
        renderRooms();
        renderServers();
        String pkg = Store.ivaPkg(this);
        ivaApp.setText(pkg.isEmpty() ? "Приложение IVA Connect не найдено." : "Открывается: " + appLabel(pkg));
        String seen = Store.lastSeen(this);
        lastSeen.setText(seen.isEmpty() ? "Пока ни разу не останавливался." : seen);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveQuietly();
    }

    // ---- service state ----

    private void renderService() {
        serviceBox.removeAllViews();
        if (JoinService.isEnabled(this)) {
            TextView ok = text("●  Автовход включён", 14, accent, Typeface.BOLD);
            ok.setPadding(0, 0, 0, dp(4));
            serviceBox.addView(ok);
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(8));
        box.setBackground(round(warnSoft, dp(16), 0));
        box.addView(text("Автовход выключен", 16, warn, Typeface.BOLD));
        TextView t = text("Без него IVA откроется, но имя и пароль придётся вводить самому. "
                + "Если переключатель серый, сначала разрешите ограниченные настройки.", 14, ink, Typeface.NORMAL);
        t.setPadding(0, dp(4), 0, dp(4));
        box.addView(t);
        TextView on = textButton("Включить в спец. возможностях", warn);
        on.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        box.addView(on);
        TextView restricted = textButton("Разрешить ограниченные настройки", warn);
        restricted.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        box.addView(restricted);
        serviceBox.addView(box);
    }

    private void renderMode() {
        boolean app = Store.mode(this) == Store.MODE_APP;
        styleSegment(modeApp, app);
        styleSegment(modeLink, !app);
    }

    // ---- rooms ----

    private void renderRooms() {
        roomList.removeAllViews();
        openEditor = null;
        List<Long> rooms = Store.rooms(this);
        for (int i = 0; i < rooms.size(); i++) {
            long r = rooms.get(i);
            if (i > 0) roomList.addView(divider());
            roomList.addView(roomRow(r));
            if (r == expanded) roomList.addView(roomEditor(r));
        }
    }

    private View roomRow(long r) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(12), dp(14));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(line),
                round(r == expanded ? accentSoft : surface, 0, 0), null));

        String label = Store.label(this, r);
        TextView badge = text(initials(label), 15, Color.WHITE, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(badgeColor(r));
        badge.setBackground(dot);
        row.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(14), 0, dp(8), 0);
        mid.addView(text(label, 15, muted, Typeface.NORMAL));
        String id = Store.confId(this, r);
        TextView idView = text(id.isEmpty() ? "ID не задан" : spacedId(id), id.isEmpty() ? 17 : 22, id.isEmpty() ? muted : ink, Typeface.BOLD);
        idView.setFontFeatureSettings("tnum");
        idView.setSingleLine(true);
        mid.addView(idView);
        String server = Store.server(this, r);
        if (!server.isEmpty()) {
            TextView s = text(server, 13, muted, Typeface.NORMAL);
            s.setSingleLine(true);
            mid.addView(s);
        }
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        boolean ready = Store.isConfigured(this, r);
        TextView join = text("Войти", 15, ready ? onAccent : muted, Typeface.BOLD);
        join.setGravity(Gravity.CENTER);
        join.setPadding(dp(18), 0, dp(18), 0);
        join.setBackground(ready
                ? new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(accent, dp(20), 0), null)
                : round(0, dp(20), line));
        join.setOnClickListener(v -> {
            saveQuietly();
            if (!Store.isConfigured(this, r)) {
                expanded = r;
                renderRooms();
                Toast.makeText(this, "Укажите ID комнаты", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, LaunchActivity.class).putExtra(LaunchActivity.EXTRA_ROOM, r));
        });
        row.addView(join, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        row.setOnClickListener(v -> {
            saveQuietly();
            expanded = expanded == r ? -1 : r;
            renderRooms();
        });
        return row;
    }

    private View roomEditor(long r) {
        Editor e = new Editor();
        e.room = r;
        openEditor = e;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(accentSoft);
        box.setPadding(dp(16), 0, dp(16), dp(12));

        box.addView(fieldLabel("Название"));
        e.label = input("Планёрка", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.label.setText(Store.label(this, r));
        box.addView(e.label);

        box.addView(fieldLabel("Сервер"));
        e.server = new AutoCompleteTextView(this);
        styleInput(e.server, "iva.company.ru", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.server.setText(Store.server(this, r));
        e.server.setThreshold(1);
        e.server.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, Servers.get(this).all()));
        box.addView(e.server);
        List<String> saved = Servers.get(this).all();
        if (!saved.isEmpty()) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout pills = new LinearLayout(this);
            pills.setPadding(0, dp(8), 0, 0);
            for (String host : saved) {
                TextView pill = pill(host);
                pill.setOnClickListener(v -> {
                    e.server.setText(host);
                    e.server.setSelection(host.length());
                    e.server.dismissDropDown();
                });
                pills.addView(pill);
            }
            hs.addView(pills);
            box.addView(hs);
        }

        box.addView(fieldLabel("ID комнаты"));
        e.conf = input("123456789", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.conf.setText(Store.confId(this, r));
        box.addView(e.conf);

        boolean hasPw = Store.hasPassword(this, r);
        box.addView(fieldLabel(hasPw ? "Пароль сохранён. Новый заменит его" : "Пароль комнаты"));
        e.password = input(hasPw ? "••••••" : "Если комната с паролем", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(e.password);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(16), 0, 0);
        TextView save = text("Сохранить", 15, onAccent, Typeface.BOLD);
        save.setGravity(Gravity.CENTER);
        save.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(accent, dp(14), 0), null));
        save.setOnClickListener(v -> {
            if (save(e, true)) {
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
                expanded = -1;
                renderRooms();
                renderServers();
            }
        });
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(46), 1));
        TextView pin = text("На рабочий стол", 15, accent, Typeface.BOLD);
        pin.setGravity(Gravity.CENTER);
        pin.setBackground(round(0, dp(14), accent));
        pin.setOnClickListener(v -> {
            if (save(e, true)) {
                renderServers();
                pinShortcut(r);
            }
        });
        LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        pinLp.leftMargin = dp(10);
        actions.addView(pin, pinLp);
        box.addView(actions);

        LinearLayout minor = new LinearLayout(this);
        if (hasPw) {
            TextView clearPw = textButton("Забыть пароль", muted);
            clearPw.setOnClickListener(v -> {
                Store.clearPassword(this, r);
                renderRooms();
            });
            minor.addView(clearPw, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        } else {
            minor.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
        }
        TextView del = textButton("Удалить комнату", danger);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Удалить «" + Store.label(this, r) + "»?")
                .setMessage("Пароль комнаты тоже удалится, ярлык на рабочем столе перестанет работать.")
                .setPositiveButton("Удалить", (d, which) -> {
                    Store.deleteRoom(this, r);
                    openEditor = null;
                    expanded = -1;
                    dropShortcut(r);
                    publishShortcuts();
                    renderRooms();
                })
                .setNegativeButton("Оставить", null)
                .show());
        minor.addView(del);
        box.addView(minor);
        return box;
    }

    /** Saves the open room; with {@code strict} explains what is missing instead of saving half. */
    private boolean save(Editor e, boolean strict) {
        if (!Store.exists(this, e.room)) return false;
        String server = Store.cleanServer(e.server.getText().toString());
        String id = e.conf.getText().toString().replaceAll("\\s", "");
        if (strict && id.isEmpty()) {
            Toast.makeText(this, "Укажите ID комнаты", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (strict && Store.mode(this) == Store.MODE_LINK && server.isEmpty()) {
            Toast.makeText(this, "Для входа по ссылке нужен сервер", Toast.LENGTH_SHORT).show();
            return false;
        }
        Store.save(this, e.room, e.label.getText().toString().trim(), server, id);
        Servers.get(this).touch(server);
        String pw = e.password.getText().toString();
        if (!pw.isEmpty()) {
            try {
                Store.setPassword(this, e.room, pw);
                e.password.setText("");
            } catch (Exception ex) {
                Toast.makeText(this, "Пароль не сохранился: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                return false;
            }
        }
        publishShortcuts();
        return true;
    }

    private void saveQuietly() {
        if (openEditor != null) save(openEditor, false);
    }

    // ---- servers ----

    private void renderServers() {
        serverBox.removeAllViews();
        List<String> all = Servers.get(this).all();
        if (all.isEmpty()) {
            serverBox.addView(text("Сервер попадёт сюда, когда вы сохраните комнату. Потом его можно подставить одним касанием.", 14, muted, Typeface.NORMAL));
            return;
        }
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(round(surface, dp(20), line));
        for (int i = 0; i < all.size(); i++) {
            String host = all.get(i);
            if (i > 0) group.addView(divider());
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(4), dp(4), dp(4));
            TextView h = text(host, 15, ink, Typeface.NORMAL);
            h.setSingleLine(true);
            row.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView x = text("Убрать", 14, muted, Typeface.NORMAL);
            x.setPadding(dp(12), dp(12), dp(12), dp(12));
            x.setOnClickListener(v -> {
                Servers.get(this).delete(host);
                renderServers();
                if (expanded >= 0) {
                    saveQuietly();
                    renderRooms();
                }
            });
            row.addView(x);
            group.addView(row);
        }
        serverBox.addView(group);
    }

    // ---- choosing the IVA Connect app ----

    private List<ResolveInfo> launchableApps() {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = new ArrayList<>(getPackageManager().queryIntentActivities(main, 0));
        apps.removeIf(a -> getPackageName().equals(a.activityInfo.packageName));
        PackageManager pm = getPackageManager();
        Collections.sort(apps, (a, b) -> {
            boolean ia = looksLikeIva(a), ib = looksLikeIva(b);
            if (ia != ib) return ia ? -1 : 1;
            return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
        });
        return apps;
    }

    private boolean looksLikeIva(ResolveInfo a) {
        String s = (a.loadLabel(getPackageManager()) + " " + a.activityInfo.packageName).toLowerCase(Locale.ROOT);
        return s.contains("iva") || s.contains("ива");
    }

    private void autoDetectIva() {
        for (ResolveInfo a : launchableApps()) {
            if (looksLikeIva(a)) {
                Store.setIvaPkg(this, a.activityInfo.packageName);
                return;
            }
        }
    }

    private void pickIvaApp() {
        List<ResolveInfo> apps = launchableApps();
        CharSequence[] names = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).loadLabel(getPackageManager());
        new AlertDialog.Builder(this)
                .setTitle("Какое приложение открывать")
                .setItems(names, (d, which) -> {
                    Store.setIvaPkg(this, apps.get(which).activityInfo.packageName);
                    onResume();
                })
                .show();
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "не установлено";
        }
    }

    // ---- shortcuts ----

    private ShortcutInfo shortcut(long r) {
        String name = Store.label(this, r);
        Intent launch = new Intent(this, LaunchActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(LaunchActivity.EXTRA_ROOM, r);
        return new ShortcutInfo.Builder(this, "meet" + r)
                .setShortLabel(name)
                .setLongLabel(name)
                .setIcon(Icon.createWithBitmap(badgeBitmap(name, badgeColor(r))))
                .setIntent(launch)
                .build();
    }

    /** Long-press menu on the app icon, and refresh of already pinned shortcuts. */
    private void publishShortcuts() {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null) return;
        List<ShortcutInfo> list = new ArrayList<>();
        for (long r : Store.rooms(this)) {
            if (Store.isConfigured(this, r)) list.add(shortcut(r));
        }
        try {
            sm.updateShortcuts(list);
            int max = sm.getMaxShortcutCountPerActivity();
            sm.setDynamicShortcuts(list.size() > max ? list.subList(0, max) : list);
        } catch (Exception ignored) {
            // rate-limited or unsupported launcher: not critical
        }
    }

    private void dropShortcut(long r) {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null) return;
        try {
            sm.disableShortcuts(Collections.singletonList("meet" + r), "Комната удалена");
        } catch (Exception ignored) {
        }
    }

    private void pinShortcut(long r) {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Лаунчер не умеет ярлыки. Комнаты есть в меню по долгому нажатию на иконку.", Toast.LENGTH_LONG).show();
            return;
        }
        sm.requestPinShortcut(shortcut(r), null);
    }

    private static int badgeColor(long r) {
        return BADGES[(int) (Math.abs(r) % BADGES.length)];
    }

    private static String initials(String name) {
        String t = name.trim();
        return t.isEmpty() ? "?" : t.substring(0, Math.min(2, t.length())).toUpperCase();
    }

    /** "123456789" -> "123 456 789", easier to compare at a glance. */
    private static String spacedId(String id) {
        if (!id.matches("\\d{6,}")) return id;
        StringBuilder sb = new StringBuilder();
        int lead = id.length() % 3;
        for (int i = 0; i < id.length(); i++) {
            if (i > 0 && (i - lead) % 3 == 0) sb.append(' ');
            sb.append(id.charAt(i));
        }
        return sb.toString();
    }

    private static Bitmap badgeBitmap(String name, int color) {
        int s = 192;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawCircle(s / 2f, s / 2f, s / 2f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(s * 0.40f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(initials(name), s / 2f, s / 2f - (fm.ascent + fm.descent) / 2f, p);
        return bmp;
    }

    // ---- tiny UI kit ----

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView text(String s, int sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", style));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 17, ink, Typeface.BOLD);
        t.setPadding(0, dp(28), 0, dp(10));
        return t;
    }

    private TextView fieldLabel(String s) {
        TextView t = text(s, 13, muted, Typeface.NORMAL);
        t.setPadding(dp(2), dp(14), 0, dp(6));
        return t;
    }

    private TextView textButton(String s, int color) {
        TextView t = text(s, 15, color, Typeface.BOLD);
        t.setPadding(0, dp(12), dp(8), dp(12));
        return t;
    }

    private TextView pill(String s) {
        TextView t = text(s, 14, accent, Typeface.NORMAL);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        t.setBackground(round(surface, dp(100), line));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView segment(String s) {
        TextView t = text(s, 15, ink, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void styleSegment(TextView t, boolean on) {
        t.setTextColor(on ? onAccent : muted);
        t.setBackground(on ? round(accent, dp(10), 0) : null);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(line);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        return v;
    }

    private EditText input(String hint, int type) {
        EditText e = new EditText(this);
        styleInput(e, hint, type);
        return e;
    }

    private void styleInput(EditText e, String hint, int type) {
        e.setInputType(type);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setTextSize(16);
        e.setTextColor(ink);
        e.setHintTextColor(muted);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackground(round(surface, dp(12), line));
    }
}
