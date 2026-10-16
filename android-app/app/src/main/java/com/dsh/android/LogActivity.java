package com.dsh.android;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shows the runtime's captured output, which is the only diagnostic surface on a device. */
public final class LogActivity extends Activity {

    private TextView output;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            output.setText(DshApp.runtime().logText());
            output.append(crashText());
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.log_title);

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextSize(11f);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        output.setPadding(padding, padding, padding, padding);
        scroll.addView(output, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        EdgeToEdge.apply(this, scroll);
        setContentView(scroll);
    }

    /**
     * The recorded crash traces, when the application has failed before.
     *
     * They are shown after the runtime log because a crash is the reason
     * someone opens this screen, and it must be readable without a host
     * connection.
     */
    private String crashText() {
        java.io.File log = new java.io.File(RuntimeLayout.root(this), "crash.log");
        if (!log.isFile()) return "";
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(log.toPath());
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException unreadable) {
            return "\n(crash.log could not be read: " + unreadable.getMessage() + ")\n";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }
}
