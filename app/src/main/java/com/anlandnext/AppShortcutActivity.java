package com.anlandnext;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Entry point for a pinned home-screen shortcut: launches one container
 * application and finishes immediately.
 *
 * Android starts a shortcut's intent in a task of its own, so this activity
 * exists purely to hold that intent; the actual window is the AwlWindowActivity
 * the daemon spawns when the client maps its toplevel.
 */
public class AppShortcutActivity extends Activity {
    public static final String EXTRA_APP_ID = "app_id";
    public static final String EXTRA_APP_NAME = "app_name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_APP_ID) : null;
        String name = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_APP_NAME) : null;
        if (id == null || id.isEmpty()) {
            finish();
            return;
        }
        int rc = WlBinder.appsRun("", id);
        if (rc != 0) {
            Toast.makeText(this,
                    getString(R.string.apps_launch_failed,
                            name == null || name.isEmpty() ? id : name),
                    Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
