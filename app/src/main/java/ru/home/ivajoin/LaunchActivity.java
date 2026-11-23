package ru.home.ivajoin;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible activity behind each home-screen shortcut: arms the service and opens IVA Connect. */
public class LaunchActivity extends Activity {
    static final String EXTRA_ROOM = "idx"; // key kept from 2.0 so old pinned shortcuts still work

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context app = getApplicationContext();

        long r = room(getIntent());
        if (r < 0 || !Store.isConfigured(this, r)) {
            Toast.makeText(app, "Комната не настроена: укажите ID", Toast.LENGTH_SHORT).show();
            openSettings();
            return;
        }

        if (!JoinService.isEnabled(this)) {
            Toast.makeText(app, "Автовход выключен, данные придётся ввести вручную", Toast.LENGTH_LONG).show();
        }

        Intent open;
        String pkg;
        if (Store.mode(this) == Store.MODE_APP) {
            pkg = Store.ivaPkg(this);
            open = pkg.isEmpty() ? null : getPackageManager().getLaunchIntentForPackage(pkg);
            if (open == null) {
                Toast.makeText(app, "Не выбрано приложение IVA Connect", Toast.LENGTH_LONG).show();
                openSettings();
                return;
            }
            // start from IVA's first screen every time, so the screen walk is predictable
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        } else {
            open = new Intent(Intent.ACTION_VIEW, Uri.parse(Store.link(this, r))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pkg = linkHandler(open);
        }

        // the service only acts inside this app
        Store.startPending(this, r, pkg);
        try {
            startActivity(open);
        } catch (ActivityNotFoundException e) {
            Store.clearPending(this);
            Toast.makeText(app, "Не удалось открыть IVA Connect", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    /** 2.0 shortcuts put an int extra, newer ones a long. */
    private static long room(Intent i) {
        Object v = i.getExtras() == null ? null : i.getExtras().get(EXTRA_ROOM);
        return v instanceof Number ? ((Number) v).longValue() : -1;
    }

    private String linkHandler(Intent view) {
        ResolveInfo ri = getPackageManager().resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        String pkg = ri.activityInfo.packageName;
        String cls = ri.activityInfo.name == null ? "" : ri.activityInfo.name;
        if ("android".equals(pkg) || cls.contains("Resolver") || cls.contains("Chooser")) return null;
        return pkg;
    }

    private void openSettings() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
