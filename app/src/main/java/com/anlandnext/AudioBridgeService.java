package com.anlandnext;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps com.anlandnext in the foreground so the SLES source module may record.
 *
 * The recorder lives in the PulseAudio process, which runs under the app uid
 * (see module/service.sh), so the AudioRecord is attributed to com.anlandnext.
 * RECORD_AUDIO is granted in the foreground-only app-op mode, and
 * AudioPolicyManager silences the record path for a non-foreground uid
 * ("startInput on silenced input ... Unsilencing" is followed by a stream of
 * zeros reaching libpulse clients). An Activity cannot hold that state: the
 * container windows come and go with the desktops, and the user may lock the
 * screen while a container app is still recording.
 *
 * A foreground service keeps the uid foreground with no window on screen. The
 * manifest targets SDK 29, so the API 34 rules that require
 * FOREGROUND_SERVICE_MICROPHONE and a while-in-use start do not apply; only
 * FOREGROUND_SERVICE has to be declared.
 */
public class AudioBridgeService extends Service {
    private static final String CHANNEL_ID = "anland_audio";
    /** 'AN' — stable, so a re-start replaces the previous notification. */
    private static final int NOTIFICATION_ID = 0x414e;

    @Override
    public void onCreate() {
        super.onCreate();
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.app_name),
                        // IMPORTANCE_MIN keeps it out of the status bar's
                        // attention budget; the service only needs the uid
                        // to stay foreground.
                        NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.app_name))
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    /**
     * START_STICKY: the daemon is the only owner of this service, and losing it
     * silently would mute the container microphone for the rest of the session.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
