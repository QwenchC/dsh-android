package com.dsh.android;

import android.app.Activity;
import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Keeps a screen's content between the system bars.
 *
 * Android 15 draws every application edge to edge, so the status and navigation
 * bars are painted over the content unless the content reserves their space.
 * Each activity is a full-window surface, so it applies the inset to
 * its root view once, and the keyboard's inset is included so a text field is
 * never left under it.
 */
final class EdgeToEdge {

    private EdgeToEdge() {}

    /** Reserve the system-bar and keyboard space on a root view. */
    static void apply(Activity activity, View root) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        final int left = root.getPaddingLeft(), top = root.getPaddingTop();
        final int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.ime() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
