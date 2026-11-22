package com.anlandnext;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.anlandnext.awl.Awl;

import java.util.List;
import java.util.Locale;

/**
 * Window list (launcher entry), implemented on the libawl client library —
 * the same API third-party consumer apps use (Awl.getWindows /
 * registerCallback / attachWindow / closeWindow). The daemon scopes by
 * caller identity: this APK is package-authenticated, so it manages every
 * window; a consumer app only ever sees its own uid's.
 * Refreshed from daemon window events (create/destroy/attach/detach) while
 * resumed, plus a snapshot pull on resume (events are live-edge only).
 * Tap → attachWindow (bring-to-front / re-attach). Long-press → dropdown
 * menu (the long-press itself does nothing, guarding against accidental
 * triggers):
 *   Close — the only close entry (daemon T_CLOSE → the client exits
 *   cleanly);
 *   Window info — id/title/attach state.
 */
public class MainActivity extends Activity {
    private static final int MENU_CLOSE = 1;
    private static final int MENU_INFO = 2;
    private static final int MENU_PIN = 3;

    /** true while the "Apps" tab is selected (refresh() would otherwise
     *  overwrite the application list with window rows) */
    private boolean showApps;
    private LinearLayout list;
    private TextView status;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = this::refresh;

    /** daemon window events → one coalesced snapshot pull (a re-attach bursts
     *  detach+attach; a window going away bursts destroy+detach) */
    private final Awl.Callback events = new Awl.Callback() {
        @Override public void onWindowCreated(long id, String title) { scheduleRefresh(); }
        @Override public void onWindowDestroyed(long id) { scheduleRefresh(); }
        @Override public void onWindowAttached(long id) { scheduleRefresh(); }
        @Override public void onWindowDetached(long id) { scheduleRefresh(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 48);

        status = new TextView(this);
        status.setTextSize(14);
        root.addView(status);

        Button settings = new Button(this);
        settings.setText(R.string.settings_title);
        settings.setOnClickListener(v ->
                startActivity(new Intent(this, WlSettingsActivity.class)));
        root.addView(settings);

        /* Windows / Apps switch. The window list can only show windows that
         * already exist; anland 6.x has no desktop environment to start new
         * container software, so the catalogue comes from the container-side
         * anland-apps tool over binder. */
        RadioGroup tabs = new RadioGroup(this);
        tabs.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton tabWindows = new RadioButton(this);
        tabWindows.setId(101);
        tabWindows.setText(R.string.tab_windows);
        RadioButton tabApps = new RadioButton(this);
        tabApps.setId(102);
        tabApps.setText(R.string.tab_apps);
        tabs.addView(tabWindows);
        tabs.addView(tabApps);
        tabWindows.setChecked(true);
        tabs.setOnCheckedChangeListener((g, id) -> {
            showApps = (id == 102);
            if (showApps) refreshApps(); else refresh();
        });
        root.addView(tabs);

        ScrollView sc = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Awl.registerCallback(events);   /* live list updates while resumed (daemon disconnects a paused subscriber) */
        refresh();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        Awl.unregisterCallback(events);   /* last callback out → subscription dropped */
        super.onPause();
    }

    /** event-burst coalescing: one snapshot pull per burst */
    private void scheduleRefresh() {
        handler.removeCallbacks(refresher);
        handler.postDelayed(refresher, 60);
    }

    private void refresh() {
        if (showApps) return;   /* the Apps tab owns the list while selected */
        list.removeAllViews();
        List<Awl.WlWindow> wins = Awl.getWindows();
        if (wins == null) {
            status.setText(R.string.status_daemon_unreachable);
            return;
        }
        Awl.ensureSubscribed();   /* daemon restarted under us → re-subscribe the event stream */
        status.setText(getString(R.string.status_window_count, wins.size()));
        for (Awl.WlWindow w : wins) list.addView(row(w));
        if (wins.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.status_empty);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 96, 0, 0);
            list.addView(empty);
        }
    }

    /** Container application catalogue (the "Apps" tab). The daemon relays the
     *  container-side anland-apps listing; empty means daemon or container is
     *  down, which anland 6.x cannot fix from here. */
    private void refreshApps() {
        list.removeAllViews();
        List<WlBinder.AppEntry> apps = WlBinder.appsList("");
        if (apps.isEmpty()) {
            status.setText(R.string.apps_unavailable);
            TextView empty = new TextView(this);
            empty.setText(R.string.apps_empty);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 96, 0, 0);
            list.addView(empty);
            return;
        }
        status.setText(getString(R.string.apps_count, apps.size()));
        for (WlBinder.AppEntry a : apps) {
            if (a.noDisplay) continue;      /* NoDisplay/Hidden entries stay callable by id but are not listed */
            list.addView(appRow(a));
        }
    }

    private View appRow(WlBinder.AppEntry a) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(24, 28, 24, 28);

        TextView t = new TextView(this);
        t.setText(a.name);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        r.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        r.setOnClickListener(v -> {
            int rc = WlBinder.appsRun("", a.id);
            if (rc != 0) {
                Toast.makeText(this, getString(R.string.apps_launch_failed, a.name),
                        Toast.LENGTH_SHORT).show();
            }
            /* the new window is fronted by the module's anland-focus-new poller */
        });
        r.setOnLongClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, v);
            pm.getMenu().add(Menu.NONE, MENU_PIN, MENU_PIN, R.string.apps_menu_shortcut);
            pm.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == MENU_PIN) { pinShortcut(a); return true; }
                return false;
            });
            pm.show();
            return true;
        });
        return r;
    }

    /** Ask the launcher to pin a home-screen shortcut that launches this app. */
    private void pinShortcut(WlBinder.AppEntry a) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, R.string.apps_pin_unsupported, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent launch = new Intent(this, AppShortcutActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(AppShortcutActivity.EXTRA_APP_ID, a.id)
                .putExtra(AppShortcutActivity.EXTRA_APP_NAME, a.name);
        Icon icon = Icon.createWithBitmap(letterIcon(a.name));
        ShortcutInfo si = new ShortcutInfo.Builder(this, "anland-app-" + a.id)
                .setShortLabel(a.name)
                .setLongLabel(a.name)
                .setIcon(icon)
                .setIntent(launch)
                .build();
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, R.string.apps_pin_unsupported, Toast.LENGTH_SHORT).show();
            return;
        }
        sm.requestPinShortcut(si, null);
    }

    /** Simple letter avatar; a .desktop icon pipeline can replace it later. */
    private Bitmap letterIcon(String name) {
        int size = 192;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF2B579A);
        c.drawRoundRect(0, 0, size, size, 36, 36, p);
        p.setColor(0xFFFFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(104);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        String letter = name == null || name.isEmpty() ? "?"
                : name.substring(0, 1).toUpperCase(java.util.Locale.getDefault());
        c.drawText(letter, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p);
        return bmp;
    }

    private View row(Awl.WlWindow w) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(24, 28, 24, 28);

        TextView t = new TextView(this);
        t.setText(w.title == null || w.title.isEmpty()
                ? getString(R.string.window_fallback_title, w.id) : w.title);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        r.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView st = new TextView(this);
        st.setText(w.attached ? R.string.state_attached : R.string.state_detached);
        st.setTextSize(13);
        st.setTextColor(w.attached ? Color.rgb(0, 128, 0) : Color.rgb(160, 96, 0));
        r.addView(st);

        r.setOnClickListener(v -> {
            /* libawl hosting: AwlWindowActivity (merged from the library) —
             * the daemon evicts any previous holder of this window */
            Awl.attachWindow(MainActivity.this, w.id, w.title);
        });
        r.setOnLongClickListener(v -> {
            showRowMenu(v, w);
            return true;
        });
        return r;
    }

    /** Row long-press dropdown: Close (the only close entry) / Window info */
    private void showRowMenu(View anchor, Awl.WlWindow w) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(Menu.NONE, MENU_CLOSE, Menu.NONE, R.string.menu_close);
        menu.getMenu().add(Menu.NONE, MENU_INFO, Menu.NONE, R.string.menu_window_info);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_CLOSE) {
                Awl.closeWindow(w.id);   /* daemon decides: the client exits cleanly */
                scheduleRefresh();
            } else if (item.getItemId() == MENU_INFO) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.menu_window_info)
                        .setMessage(String.format(Locale.US,
                                getString(R.string.window_info_format),
                                w.id,
                                w.title == null || w.title.isEmpty()
                                        ? getString(R.string.title_none) : w.title,
                                w.attached ? getString(R.string.state_attached_foreground)
                                           : getString(R.string.state_detached_kept)))
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show();
            }
            return true;
        });
        menu.show();
    }
}
