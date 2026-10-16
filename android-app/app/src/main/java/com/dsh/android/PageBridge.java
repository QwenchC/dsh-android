package com.dsh.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.File;

/**
 * The page's way of asking the device to open a file.
 *
 * The Harness renders a file reference as a button, and its own answer is the
 * right sidebar's preview. On a phone the document usually belongs to another
 * application, so this bridge asks which one and opens it there — and offers
 * the preview as the alternative, because the in-product view remains a
 * legitimate choice.
 *
 * The origin-restricted WebMessage listener receives only main-frame messages.
 * Injected script reaches it as `window.DSHAndroid.postMessage`.
 */
final class PageBridge {

    /** Scheme the injected script uses to name the bridge object. */
    static final String NAME = "DSHAndroid";

    private final Activity activity;
    private final DshRuntime runtime;

    PageBridge(Activity activity, DshRuntime runtime) {
        this.activity = activity;
        this.runtime = runtime;
    }

    /**
     * Ask how to open one file the conversation produced.
     *
     * MainActivity validates the sending origin and main frame before calling here.
     * Canonical workspace containment is checked before showing any file metadata.
     *
     * @param path - the path exactly as the conversation spelled it: absolute,
     * or relative to the session's workspace.
     */
    public void openFile(String path) {
        if (path == null || path.length() > 32768) return;
        activity.runOnUiThread(() -> {
            try {
                File file = absolute(path).getCanonicalFile();
                if (!inside(runtime.servedWorkspace(), file) && !inside(runtime.privateWorkspace(), file)) {
                    Toast.makeText(activity, R.string.file_outside_workspace, Toast.LENGTH_LONG).show();
                    return;
                }
                ask(file);
            } catch (java.io.IOException | IllegalArgumentException invalid) {
                Toast.makeText(activity, R.string.file_open_missing, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Resolve a conversation path against the workspace this application serves.
     *
     * A session outlives a change of workspace, so a file an earlier session
     * wrote is still in the private workspace after the projects move to shared
     * storage. The current workspace is tried first and the private one is the
     * fallback, which is where those files actually are.
     *
     * @param path - absolute, or relative to the workspace the session used.
     * @returns the file the conversation means.
     */
    private File absolute(String path) {
        File named = new File(path);
        if (named.isAbsolute()) return named;
        File current = new File(runtime.servedWorkspace(), path);
        if (current.exists()) return current;
        return new File(runtime.privateWorkspace(), path);
    }

    private static boolean inside(File root, File file) throws java.io.IOException {
        return file.getPath().startsWith(root.getCanonicalPath() + File.separator);
    }

    private void ask(File file) {
        if (!file.isFile()) {
            Toast.makeText(activity, R.string.file_open_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.file_open_title, file.getName()))
                .setPositiveButton(R.string.file_open_with, (dialog, which) -> openWith(file))
                .setNegativeButton(R.string.file_open_preview, (dialog, which) -> resumeInPage())
                .show();
    }

    /** Hand the file to the platform, which is what asks the user to choose. */
    private void openWith(File file) {
        Uri uri = uriFor(file);
        if (uri == null) {
            Toast.makeText(activity, R.string.files_not_shareable, Toast.LENGTH_LONG).show();
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(Intent.createChooser(view, activity.getString(R.string.files_open_with)));
        } catch (RuntimeException nothing) {
            // No viewer, or one that refused the grant. Ending the process over
            // a file the user asked to see would be the worse outcome.
            Toast.makeText(activity, R.string.files_no_viewer, Toast.LENGTH_LONG).show();
        }
    }

    /** Let the page handle the click it originally received. */
    private void resumeInPage() {
        WebView page = activity.findViewById(R.id.web);
        if (page == null) return;
        page.evaluateJavascript("window.__dshAndroidResume && window.__dshAndroidResume()", null);
    }

    /**
     * A URI another application may read, or null when none exists.
     *
     * The workspace on shared storage is addressed through the provider the
     * device already uses for it; the private workspace through this
     * application's own provider, which serves that directory and nothing else.
     */
    private Uri uriFor(File file) {
        String path = file.getAbsolutePath();
        String served = new File(activity.getFilesDir(), ProjectFilesProvider.SERVED_ROOT).getAbsolutePath();
        if (path.equals(served) || path.startsWith(served + File.separator)) {
            String relative = path.substring(served.length() + 1).replace(File.separatorChar, '/');
            return new Uri.Builder()
                    .scheme("content")
                    .authority(activity.getPackageName() + ProjectFilesProvider.AUTHORITY_SUFFIX)
                    .path("/" + relative)
                    .build();
        }
        String external = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.startsWith(external + File.separator)) {
            return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                    "primary:" + path.substring(external.length() + 1));
        }
        return null;
    }

    private String mimeOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String guessed = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
            if (guessed != null) return guessed;
        }
        return "application/octet-stream";
    }

    /**
     * The script the page runs.
     *
     * A file reference is the Harness's own `fileLink` button, whose `title`
     * carries the path. The click is intercepted before the application's
     * renderer sees it, the bridge asks how to open the file, and
     * `__dshAndroidResume` replays the click when the preview is chosen.
     */
    static final String SCRIPT = "(function () {"
            + "if (!window.DSHAndroid || window.__dshAndroidInstalled) return;"
            + "window.__dshAndroidInstalled = true;"
            + "window.__dshAndroidPending = null;"
            + "window.__dshAndroidResume = function () {"
            + "  var pending = window.__dshAndroidPending;"
            + "  window.__dshAndroidPending = null;"
            + "  if (pending) { window.__dshAndroidAllow = true; pending.click(); window.__dshAndroidAllow = false; }"
            + "};"
            + "document.addEventListener('click', function (event) {"
            + "  if (window.__dshAndroidAllow) return;"
            + "  var node = event.target;"
            + "  while (node && node !== document) {"
            + "    if (node.tagName === 'BUTTON' && typeof node.className === 'string'"
            + "        && node.className.indexOf('fileLink') >= 0) {"
            + "      var path = node.getAttribute('title');"
            + "      if (path) {"
            + "        event.preventDefault();"
            + "        event.stopPropagation();"
            + "        window.__dshAndroidPending = node;"
            + "        window.DSHAndroid.postMessage(path);"
            + "        return;"
            + "      }"
            + "    }"
            + "    node = node.parentNode;"
            + "  }"
            + "}, true);"
            + "})();";
}
