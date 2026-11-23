package ru.home.ivajoin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Debug builds only: replaces all data with made-up rooms, then opens the main screen. */
public class DemoSeedActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        for (long r : Store.rooms(this)) Store.deleteRoom(this, r);
        for (String s : Servers.get(this).all()) Servers.get(this).delete(s);

        Store.setUserName(this, "Анна");
        Store.setMode(this, Store.MODE_APP);
        room("Планёрка", "iva.example.ru", "482913507", "demo-pass");
        room("Ретро команды", "iva.example.ru", "615204", "");
        room("Созвон с дизайном", "video.example.com", "730118", "demo-pass");
        // most recently used first
        Servers.get(this).touch("video.example.com");
        Servers.get(this).touch("iva.example.ru");

        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private void room(String label, String server, String id, String password) {
        long r = Store.addRoom(this);
        Store.save(this, r, label, server, id);
        if (!password.isEmpty()) {
            try {
                Store.setPassword(this, r, password);
            } catch (Exception ignored) {
            }
        }
    }
}
