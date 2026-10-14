package com.dsh.android;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Keeps a screen's content between the system bars.
 *
 * Android 15 draws every application edge to edge, so the status and navigation
 * bars are painted over the content unless the content reserves their space.
 * Both activities here are full-window surfaces, so each applies the inset to
 * its root view once, and the keyboard's inset is included so a text field is
 * never left under it.
 */
final class EdgeToEdge {

    private EdgeToEdge() {}

    /** Reserve the system-bar and keyboard space on a root view. */
    static void apply(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}
