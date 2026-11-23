package com.anlandnext;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible launcher for a pinned home-screen shortcut.
 *
 * A shortcut created for container software points here, not at a launcher app:
 * the window that opens is hosted by {@code AwlWindowActivity} in this same
 * package, so tapping the icon goes straight into the application's own task
 * instead of flashing another app's "launching…" screen first.
 *
 * This activity draws nothing and finishes as soon as the daemon has started
 * the process. Starting container software needs root, which the daemon has and
 * this app does not, so the work is a binder call. The daemon only accepts a
 * container name, a user name and a .desktop id — all whitelisted, never an
 * executable path — because this activity has to be exported for the launcher
 * to reach it, which makes everything it accepts reachable by any app.
 */
public final class AppLaunchActivity extends Activity {
    public static final String EXTRA_CONTAINER = "container";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_APP_ID = "app_id";
    public static final String EXTRA_APP_NAME = "app_name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String container = getIntent().getStringExtra(EXTRA_CONTAINER);
        String user = getIntent().getStringExtra(EXTRA_USER);
        String appid = getIntent().getStringExtra(EXTRA_APP_ID);
        String name = getIntent().getStringExtra(EXTRA_APP_NAME);

        if (appid == null || appid.isEmpty()) {
            finish();
            return;
        }
        int rc = WlBinder.appLaunch(container, user, appid);
        if (rc != 0) {
            /* silence on success is the point; a failure nobody can see would
             * look like a dead icon, so say it once and get out */
            Toast.makeText(this,
                    getString(R.string.app_launch_failed,
                            name == null || name.isEmpty() ? appid : name),
                    Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    /* No content view anywhere: a translucent theme plus an empty activity is
     * the cheapest window that can still be started from the launcher. */
}
