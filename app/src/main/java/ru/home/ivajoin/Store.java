package ru.home.ivajoin;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Settings for the rooms, plus the short-lived "join in progress" state the service reads.
 * Rooms have stable ids (not positions), so home-screen shortcuts survive deleting other rooms.
 */
final class Store {
    static final int MODE_APP = 0;  // launch IVA Connect and walk its join screens
    static final int MODE_LINK = 1; // open https://<server>/id/<id>

    private static final long PENDING_TTL_MS = 120_000;
    private static final String KEY_ALIAS = "ivajoin_pw";

    private Store() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    // ---- shared settings ----

    static String userName(Context c) { return p(c).getString("user_name", ""); }
    static void setUserName(Context c, String v) { p(c).edit().putString("user_name", v).apply(); }
    static int mode(Context c) { return p(c).getInt("mode", MODE_APP); }
    static void setMode(Context c, int m) { p(c).edit().putInt("mode", m).apply(); }
    static String ivaPkg(Context c) { return p(c).getString("iva_pkg", ""); }
    static void setIvaPkg(Context c, String pkg) { p(c).edit().putString("iva_pkg", pkg).apply(); }

    // ---- room list ----

    static List<Long> rooms(Context c) {
        // versions before 2.2 had two fixed rooms stored under ids 0 and 1
        String s = p(c).getString("rooms", null);
        if (s == null) s = p(c).contains("conf0") || p(c).contains("conf1") ? "0,1" : "";
        List<Long> out = new ArrayList<>();
        for (String part : s.split(",")) {
            if (!part.trim().isEmpty()) out.add(Long.parseLong(part.trim()));
        }
        return out;
    }

    private static void setRooms(Context c, List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        p(c).edit().putString("rooms", sb.toString()).apply();
    }

    static long addRoom(Context c) {
        List<Long> ids = rooms(c);
        long id = 0;
        for (Long x : ids) id = Math.max(id, x + 1);
        ids.add(id);
        setRooms(c, ids);
        return id;
    }

    static void deleteRoom(Context c, long r) {
        List<Long> ids = rooms(c);
        ids.remove(r);
        setRooms(c, ids);
        p(c).edit().remove("name" + r).remove("server" + r).remove("conf" + r).remove("pw" + r).apply();
    }

    // ---- per-room settings ----

    static String label(Context c, long r) { return p(c).getString("name" + r, "Комната"); }
    static String server(Context c, long r) { return p(c).getString("server" + r, ""); }
    static String confId(Context c, long r) { return p(c).getString("conf" + r, ""); }
    static boolean hasPassword(Context c, long r) { return p(c).contains("pw" + r); }
    static boolean exists(Context c, long r) { return rooms(c).contains(r); }

    static void save(Context c, long r, String label, String server, String confId) {
        p(c).edit()
                .putString("name" + r, label.isEmpty() ? "Комната" : label)
                .putString("server" + r, server)
                .putString("conf" + r, confId)
                .apply();
    }

    /** "https://iva.example.ru/" -> "iva.example.ru" */
    static String cleanServer(String s) {
        s = s.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static String link(Context c, long r) {
        String server = server(c, r), id = confId(c, r);
        if (server.isEmpty() || id.isEmpty()) return "";
        return "https://" + server + "/id/" + id;
    }

    static boolean isConfigured(Context c, long r) {
        return exists(c, r) && !confId(c, r).isEmpty() && (mode(c) == MODE_APP || !server(c, r).isEmpty());
    }

    static void setPassword(Context c, long r, String pw) throws Exception {
        p(c).edit().putString("pw" + r, encrypt(pw)).apply();
    }

    static void clearPassword(Context c, long r) {
        p(c).edit().remove("pw" + r).apply();
    }

    static String password(Context c, long r) {
        String v = p(c).getString("pw" + r, null);
        if (v == null) return "";
        try {
            return decrypt(v);
        } catch (Exception e) {
            return "";
        }
    }

    // ---- pending join (set by a shortcut, consumed by the service) ----

    static void startPending(Context c, long r, String pkg) {
        long now = System.currentTimeMillis();
        p(c).edit()
                .putLong("p_room", r)
                .putString("p_pkg", pkg)
                .putLong("p_until", now + PENDING_TTL_MS)
                .putLong("p_token", now)
                .apply();
    }

    /** Room being joined right now, or -1. */
    static long pendingRoom(Context c) {
        SharedPreferences s = p(c);
        if (System.currentTimeMillis() > s.getLong("p_until", 0)) return -1;
        return s.getLong("p_room", -1);
    }

    /** Milliseconds until the current join expires, 0 if nothing is armed. */
    static long pendingLeftMs(Context c) {
        SharedPreferences s = p(c);
        if (s.getLong("p_room", -1) < 0) return 0;
        return Math.max(0, s.getLong("p_until", 0) - System.currentTimeMillis());
    }

    static String pendingPkg(Context c) { return p(c).getString("p_pkg", null); }

    /** Calls back when a join is armed or cleared. Keep a strong reference to the listener. */
    static void listenPending(Context c, SharedPreferences.OnSharedPreferenceChangeListener l) {
        p(c).registerOnSharedPreferenceChangeListener(l);
    }

    static void unlistenPending(Context c, SharedPreferences.OnSharedPreferenceChangeListener l) {
        p(c).unregisterOnSharedPreferenceChangeListener(l);
    }
    static long pendingToken(Context c) { return p(c).getLong("p_token", 0); }
    static void clearPending(Context c) { p(c).edit().putLong("p_until", 0).apply(); }

    // ---- diagnostics: what the service saw on screen last time it could not finish ----

    static void setLastSeen(Context c, String s) { p(c).edit().putString("last_seen", s).apply(); }
    static String lastSeen(Context c) { return p(c).getString("last_seen", ""); }

    // ---- password encryption with a key that never leaves the Android Keystore ----

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(KEY_ALIAS, null);
    }

    private static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        String[] parts = stored.split(":", 2);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
