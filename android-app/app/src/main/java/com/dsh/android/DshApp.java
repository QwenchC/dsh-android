package com.dsh.android;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

/** Process-wide owner of the runtime and of the notification channel it reports through. */
public final class DshApp extends Application {

    /** Channel the foreground service's ongoing notification lives on. */
    static final String CHANNEL_RUNTIME = "dsh-runtime";

    private static DshRuntime runtime;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_RUNTIME,
                getString(R.string.channel_runtime),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_runtime_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        // Installed before anything else runs, so a failure during startup is
        // recorded too.
        CrashRecorder.install(this);
        runtime = new DshRuntime(this);
    }

    /** The single runtime instance this process owns. */
    public static DshRuntime runtime() {
        return runtime;
    }
}
