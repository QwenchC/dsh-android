package com.dsh.android;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records a fatal failure where the user can read it.
 *
 * This port is exercised on devices the developer does not hold, and a crash
 * that only reaches the system log is a crash nobody can report. The trace is
 * appended beside the runtime log, which the application can already display.
 */
final class CrashRecorder implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "DshCrash";

    private final Context context;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashRecorder(Context context, Thread.UncaughtExceptionHandler previous) {
        this.context = context;
        this.previous = previous;
    }

    /** Install the recorder; the previous handler still runs. */
    static void install(Context context) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof CrashRecorder) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashRecorder(context, previous));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable failure) {
        try {
            record(thread, failure);
        } catch (RuntimeException cannotRecord) {
            Log.w(TAG, "could not record the crash", cannotRecord);
        } finally {
            if (previous != null) previous.uncaughtException(thread, failure);
        }
    }

    private void record(Thread thread, Throwable failure) {
        StringWriter text = new StringWriter();
        text.write("\n=== crash ");
        text.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        text.write(" in ");
        text.write(thread.getName());
        text.write(" ===\n");
        failure.printStackTrace(new PrintWriter(text));
        File log = new File(RuntimeLayout.root(context), "crash.log");
        File parent = log.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "no directory for " + log);
            return;
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(log, true)) {
            out.write(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException cannotWrite) {
            Log.w(TAG, "could not write " + log, cannotWrite);
        }
    }
}
