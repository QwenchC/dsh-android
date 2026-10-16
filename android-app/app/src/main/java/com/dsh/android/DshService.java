package com.dsh.android;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the Harness process alive while the user is not looking at it.
 *
 * Android reclaims the process of a backgrounded application; the runtime's
 * server, its sessions, and any agent turn in flight must survive that, so the
 * service holds a foreground notification for as long as the runtime runs.
 */
public final class DshService extends Service {

    private static final int NOTIFICATION_ID = 1;

    /** Starts the service, which starts the runtime. */
    static void start(Context context) {
        Intent intent = new Intent(context, DshService.class);
        context.startForegroundService(intent);
    }

    /** Stops the service and requests nonblocking runtime cleanup. */
    static void stop(Context context) {
        context.stopService(new Intent(context, DshService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DshApp.runtime().addListener(listener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!DshApp.runtime().autoStart()) { stopSelf(); return START_NOT_STICKY; }
        startForeground(NOTIFICATION_ID, notification(getString(R.string.notification_starting)));
        DshApp.runtime().start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        DshApp.runtime().removeListener(listener);
        main.removeCallbacksAndMessages(null);
        DshApp.runtime().stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final DshRuntime.Listener listener = (state, detail) -> main.post(() -> {
        if (state != DshApp.runtime().state()) return;
        if (state == DshRuntime.State.STOPPED || state == DshRuntime.State.FAILED) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        String text = state == DshRuntime.State.READY
                ? getString(R.string.notification_ready, "http://127.0.0.1:" + DshApp.runtime().port())
                : getString(R.string.notification_starting);
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    });

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder builder = new Notification.Builder(this, DshApp.CHANNEL_RUNTIME);
        builder.setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(content)
                .setOngoing(true)
                // The runtime holds the processor awake, which is the whole
                // reason a turn survives the screen going off; the notification
                // is where that cost is stated.
                .setStyle(new Notification.BigTextStyle()
                        .bigText(text + "\n" + getString(R.string.notification_awake)));
        return builder.build();
    }

    /** Declares the foreground service type on the platforms that require it. */
    @Override
    public void onTimeout(int startId) {
        // Android 15 bounds some foreground service types; the runtime keeps its
        // process only while the notification stands, so honour the timeout.
        DshApp.runtime().stop();
        stopSelf();
    }

    /** The service type reported to the platform; unused below API 34. */
    static int foregroundServiceType() {
        return Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
    }
}
