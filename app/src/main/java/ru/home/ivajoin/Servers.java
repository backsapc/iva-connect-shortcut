package ru.home.ivajoin;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Saved IVA servers for quick input, most recently used first. */
final class Servers extends SQLiteOpenHelper {
    private static Servers instance;

    static synchronized Servers get(Context c) {
        if (instance == null) instance = new Servers(c.getApplicationContext());
        return instance;
    }

    private Servers(Context c) {
        super(c, "servers.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE servers (host TEXT PRIMARY KEY NOT NULL CHECK (length(host) > 0), used_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    List<String> all() {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("servers", new String[]{"host"}, null, null, null, null, "used_at DESC")) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /** Adds the server or moves it to the top. */
    void touch(String host) {
        if (host == null || host.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("host", host);
        v.put("used_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("servers", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void delete(String host) {
        getWritableDatabase().delete("servers", "host = ?", new String[]{host});
    }
}
