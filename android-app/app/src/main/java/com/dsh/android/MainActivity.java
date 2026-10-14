package com.dsh.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * The Harness surface: the Web UI served by the application's own runtime,
 * rendered in a WebView.
 *
 * The activity owns no runtime state beyond the WebView. It starts the
 * foreground service, waits for the loopback origin to answer, and then loads
 * it; every other capability it needs — uploads, downloads, back navigation —
 * is delegated to the platform.
 */
public final class MainActivity extends Activity {

    private static final int REQUEST_FILE_CHOOSER = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;

    private WebView web;
    private LinearLayout status;
    private TextView statusTitle;
    private TextView statusDetail;
    private ValueCallback<Uri[]> fileCallback;
    private String loadedUrl;

    private DshRuntime.Listener listener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);
        setContentView(buildContentView());
        listener = (state, detail) -> runOnUiThread(() -> render(state, detail));
        requestNotificationPermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        DshApp.runtime().addListener(listener);
        DshService.start(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        DshApp.runtime().removeListener(listener);
    }

    /** The WebView plus the status panel shown until the server answers. */
    private View buildContentView() {
        FrameLayout root = new FrameLayout(this);

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        web.setId(R.id.web);
        web.addJavascriptInterface(new PageBridge(this, DshApp.runtime()), PageBridge.NAME);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // The page is a single application, so one installation per load
                // covers every file reference it will ever render.
                view.evaluateJavascript(PageBridge.SCRIPT, null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(Intent.createChooser(params.createIntent(), getString(R.string.choose_file)), REQUEST_FILE_CHOOSER);
                } catch (ActivityNotFoundException noPicker) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.setDownloadListener(downloadListener());
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xFF101418);
        int padding = (int) (28 * getResources().getDisplayMetrics().density);
        status.setPadding(padding, padding, padding, padding);

        statusTitle = new TextView(this);
        statusTitle.setTextColor(0xFFE6EAF0);
        statusTitle.setTextSize(17f);
        status.addView(statusTitle);

        statusDetail = new TextView(this);
        statusDetail.setTextColor(0xFF8A93A0);
        statusDetail.setTextSize(13f);
        statusDetail.setPadding(0, padding / 3, 0, padding / 2);
        status.addView(statusDetail);

        Button log = new Button(this);
        log.setText(R.string.open_log);
        log.setOnClickListener(view -> startActivity(new Intent(this, LogActivity.class)));
        status.addView(log);

        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The page occupies its own row beneath this bar, so the application's
        // own actions never sit on top of the interface they belong to.
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(0xFF101418);
        column.addView(buildToolbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        EdgeToEdge.apply(column);
        return column;
    }

    /**
     * The application's own row: the project folder, the file list, and the
     * actions that used to live in an options menu no activity bar ever showed.
     */
    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int gap = (int) (6 * getResources().getDisplayMetrics().density);
        bar.setPadding(gap, gap / 2, gap, gap / 2);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(0xFFE6EAF0);
        title.setTextSize(15f);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button folder = new Button(this);
        folder.setText(R.string.action_project_folder);
        folder.setOnClickListener(view -> openProjectFolder());
        bar.addView(folder);

        Button files = new Button(this);
        files.setText(R.string.action_files);
        files.setOnClickListener(view -> startActivity(new Intent(this, FilesActivity.class)));
        bar.addView(files);

        Button more = new Button(this);
        more.setText(R.string.action_more);
        more.setOnClickListener(this::showOverflow);
        bar.addView(more);
        return bar;
    }

    /**
     * Open the project folder in the device's own file manager.
     *
     * A folder on shared storage has a document URI any file manager
     * understands. A folder private to this application has none — nothing
     * outside the sandbox can list it — so the answer there is the grant and
     * the move that make the folder reachable, not a button that cannot work.
     */
    private void openProjectFolder() {
        File workspace = DshApp.runtime().workspace();
        String external = Environment.getExternalStorageDirectory().getAbsolutePath();
        String path = workspace.getAbsolutePath();
        if (!path.startsWith(external + File.separator)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.action_project_folder)
                    .setMessage(R.string.folder_private)
                    .setPositiveButton(R.string.menu_storage, (dialog, which) -> requestSharedStorage())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        android.net.Uri uri = android.provider.DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:" + path.substring(external.length() + 1));
        // The URI belongs to the platform's storage provider, not to this
        // application, so no read grant is attached: asking the platform to
        // grant a permission this application does not hold is itself a
        // security refusal on some builds.
        if (openWith(uri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                || openWith(uri, "resource/folder")
                || openWith(uri, "application/octet-stream")) {
            return;
        }
        // Nothing on this device can show a folder. The application's own list
        // always can, so the action still does what it promised.
        Toast.makeText(this, R.string.folder_unavailable, Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, FilesActivity.class));
    }

    /**
     * Offer one view of a URI to the platform.
     *
     * @param uri - the document to show.
     * @param mime - the type whose handlers are tried.
     * @returns whether a handler accepted it.
     */
    private boolean openWith(android.net.Uri uri, String mime) {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime);
        if (view.resolveActivity(getPackageManager()) == null) return false;
        try {
            startActivity(view);
            return true;
        } catch (RuntimeException refused) {
            // A file manager that advertises the type can still refuse the URI
            // (a missing grant, a provider it cannot reach). Trying the next
            // spelling of the request is better than ending the application.
            android.util.Log.w("DshMain", "file manager refused " + uri + " as " + mime, refused);
            return false;
        }
    }

    /** The actions menu, shown from this application's own bar. */
    private void showOverflow(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, R.string.menu_reload);
        popup.getMenu().add(0, 2, 1, R.string.menu_log);
        popup.getMenu().add(0, 3, 2, R.string.menu_workspace);
        popup.getMenu().add(0, 4, 3, R.string.menu_storage);
        popup.getMenu().add(0, 5, 4, R.string.menu_battery);
        popup.getMenu().add(0, 6, 5, R.string.menu_stop);
        popup.getMenu().add(0, 8, 6, R.string.menu_move);
        // A build under test on a device nobody is attached to needs a way to
        // prove the crash recorder works; a release build never offers it.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            popup.getMenu().add(0, 7, 6, R.string.menu_simulate_crash);
        }
        popup.setOnMenuItemClickListener(this::onOptionsItemSelected);
        popup.show();
    }

    private DownloadListener downloadListener() {
        return (url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) request.addRequestHeader("Cookie", cookie);
                request.setMimeType(mimeType);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType));
                DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (manager != null) manager.enqueue(request);
            } catch (RuntimeException unsupported) {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            }
        };
    }

    /** Renders one runtime state into the WebView and the status panel. */
    private void render(DshRuntime.State state, String detail) {
        switch (state) {
            case READY: {
                status.setVisibility(View.GONE);
                String url = DshApp.runtime().baseUrl();
                if (url != null && !url.equals(loadedUrl)) {
                    loadedUrl = url;
                    web.loadUrl(url);
                }
                break;
            }
            case FAILED: {
                status.setVisibility(View.VISIBLE);
                statusTitle.setText(R.string.state_failed);
                statusDetail.setText(detail);
                break;
            }
            case STOPPED: {
                status.setVisibility(View.VISIBLE);
                statusTitle.setText(R.string.state_stopped);
                statusDetail.setText(detail);
                status.addView(startButton());
                break;
            }
            default: {
                status.setVisibility(View.VISIBLE);
                statusTitle.setText(state == DshRuntime.State.INSTALLING
                        ? R.string.state_installing : R.string.state_starting);
                statusDetail.setText(detail);
                break;
            }
        }
    }

    private Button startButton() {
        Button start = new Button(this);
        start.setText(R.string.start_runtime);
        start.setOnClickListener(view -> {
            status.removeView(start);
            DshService.start(this);
        });
        return start;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQUEST_NOTIFICATIONS);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.menu_reload);
        menu.add(0, 2, 1, R.string.menu_log);
        menu.add(0, 3, 2, R.string.menu_workspace);
        menu.add(0, 4, 3, R.string.menu_storage);
        menu.add(0, 5, 4, R.string.menu_battery);
        menu.add(0, 6, 5, R.string.menu_stop);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                loadedUrl = null;
                web.reload();
                return true;
            case 2:
                startActivity(new Intent(this, LogActivity.class));
                return true;
            case 3:
                chooseWorkspace();
                return true;
            case 4:
                requestSharedStorage();
                return true;
            case 5:
                requestUnrestrictedBattery();
                return true;
            case 6:
                DshApp.runtime().stop();
                DshService.stop(this);
                Toast.makeText(this, R.string.runtime_stopped, Toast.LENGTH_SHORT).show();
                return true;
            case 7:
                throw new IllegalStateException("simulated failure, to check the crash recorder");
            case 8:
                moveProjectToSharedStorage();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /** Offers the two roots the application can actually write to. */
    private void chooseWorkspace() {
        final File[] roots = new File[] { DshApp.runtime().workspace(), sharedWorkspace() };
        String[] labels = new String[] {
                getString(R.string.workspace_private, roots[0].getAbsolutePath()),
                getString(R.string.workspace_shared, roots[1].getAbsolutePath()),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_workspace)
                .setItems(labels, (dialog, which) -> {
                    DshApp.runtime().setWorkspace(roots[which]);
                    Toast.makeText(this, R.string.workspace_saved, Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private File sharedWorkspace() {
        File external = Environment.getExternalStorageDirectory();
        return new File(external, "DSH");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the all-files-access screen is the moment the offer
        // makes sense: shared storage is what lets a generated file open in
        // another application and the project folder appear in a file manager.
        if (Build.VERSION.SDK_INT < 30) return;
        if (!Environment.isExternalStorageManager()) return;
        if (DshApp.runtime().hasSharedStorage()) return;
        SharedPreferences preferences = getSharedPreferences("dsh", MODE_PRIVATE);
        if (preferences.getBoolean("shared_storage_offered", false)) return;
        if (isFinishing() || isDestroyed()) return;
        preferences.edit().putBoolean("shared_storage_offered", true).apply();
        new AlertDialog.Builder(this)
                .setTitle(R.string.shared_offer_title)
                .setMessage(R.string.shared_offer_message)
                .setPositiveButton(R.string.shared_offer_accept,
                        (dialog, which) -> moveProjectToSharedStorage())
                .setNegativeButton(R.string.shared_offer_decline, null)
                .show();
    }

    /**
     * Move the project to shared storage, then work there.
     *
     * Copying is the point of the offer: a project that stayed behind in the
     * application's private directory would make the new folder look empty and
     * leave the user's files where no file manager reaches them. Nothing is
     * overwritten, and the copy runs off the main thread because a project can
     * be large.
     */
    private void moveProjectToSharedStorage() {
        File target = new File(Environment.getExternalStorageDirectory(), "DSH");
        if (!Environment.isExternalStorageManager() && Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, R.string.move_needs_storage, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.move_started, Toast.LENGTH_SHORT).show();
        // A project lives either in the application's own workspace or directly
        // under the Harness home, because that is what the Harness's directory
        // picker offers as "Home". Both are copied; the Harness's own state
        // directory is not, since sessions and credentials belong to the
        // application and not to the project.
        // The application's workspace contributes its contents, because it *is*
        // the project. A directory under the Harness home is one project among
        // several, so it is copied as a directory and keeps its name.
        File workspace = DshApp.runtime().privateWorkspace();
        java.util.List<File> projectDirectories = new java.util.ArrayList<>();
        File[] homeEntries = RuntimeLayout.home(this).listFiles();
        if (homeEntries != null) {
            for (File entry : homeEntries) {
                if (!".dsh".equals(entry.getName())) projectDirectories.add(entry);
            }
        }
        new Thread(() -> {
            int[] counts = copyTree(workspace, target);
            for (File project : projectDirectories) {
                int[] part = project.isDirectory()
                        ? copyTree(project, new File(target, project.getName()))
                        : copyFile(project, target);
                counts[0] += part[0];
                counts[1] += part[1];
                counts[2] += part[2];
            }
            runOnUiThread(() -> {
                DshApp.runtime().setSharedStorage(true);
                DshApp.runtime().setWorkspace(target);
                Toast.makeText(this, counts[0] == 0 && counts[1] == 0
                                ? getString(R.string.move_none)
                                : getString(R.string.move_done, counts[0], counts[1], target.getAbsolutePath()),
                        Toast.LENGTH_LONG).show();
            });
        }, "dsh-move-project").start();
    }

    /**
     * Copy a directory tree without replacing anything.
     *
     * @param source - the directory to read.
     * @param target - the directory to fill.
     * @returns copied, skipped (already present), and failed counts.
     */
    /**
     * Copy one file into a directory without replacing anything.
     *
     * @param source - the file to read.
     * @param target - the directory to fill.
     * @returns copied, skipped, and failed counts.
     */
    private static int[] copyFile(File source, File target) {
        int[] counts = new int[3];
        if (!target.isDirectory() && !target.mkdirs()) {
            counts[2]++;
            return counts;
        }
        File destination = new File(target, source.getName());
        if (destination.exists()) {
            counts[1]++;
            return counts;
        }
        try {
            java.nio.file.Files.copy(source.toPath(), destination.toPath());
            counts[0]++;
        } catch (java.io.IOException | RuntimeException cannotCopy) {
            android.util.Log.w("DshMain", "could not move " + source, cannotCopy);
            counts[2]++;
        }
        return counts;
    }

    private static int[] copyTree(File source, File target) {
        int[] counts = new int[3];
        if (!target.isDirectory() && !target.mkdirs()) {
            counts[2]++;
            return counts;
        }
        File[] children = source.listFiles();
        if (children == null) return counts;
        for (File child : children) {
            File destination = new File(target, child.getName());
            if (child.isDirectory()) {
                int[] deeper = copyTree(child, destination);
                counts[0] += deeper[0];
                counts[1] += deeper[1];
                counts[2] += deeper[2];
                continue;
            }
            if (destination.exists()) {
                counts[1]++;
                continue;
            }
            try {
                java.nio.file.Files.copy(child.toPath(), destination.toPath());
                counts[0]++;
            } catch (java.io.IOException | RuntimeException cannotCopy) {
                android.util.Log.w("DshMain", "could not move " + child, cannotCopy);
                counts[2]++;
            }
        }
        return counts;
    }

    /** Report a failure the platform raised, instead of ending the process. */
    private void reportFailure(String what, RuntimeException failure) {
        android.util.Log.w("DshMain", what, failure);
        Toast.makeText(this, what, Toast.LENGTH_LONG).show();
    }

    /** Sends the user to the all-files-access grant when the platform has one. */
    private void requestSharedStorage() {
        if (Build.VERSION.SDK_INT < 30) {
            DshApp.runtime().setSharedStorage(true);
            Toast.makeText(this, R.string.storage_granted, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Environment.isExternalStorageManager()) {
            DshApp.runtime().setSharedStorage(true);
            Toast.makeText(this, R.string.storage_granted, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException noSettings) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    /**
     * Ask for the exemption that lets a turn outlive deep idle.
     *
     * A foreground service and a wake lock keep the process and the processor
     * alive with the screen off, but the platform still defers network access
     * once the device settles into idle. Exempting the application is what
     * keeps a long model request streaming through that window, and it is the
     * user's decision to grant — hence a menu action rather than a manifest
     * claim.
     */
    private void requestUnrestrictedBattery() {
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (power != null && power.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, R.string.battery_granted, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException noSettings) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
