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
public final class MainActivity extends androidx.activity.ComponentActivity {

    private static final int REQUEST_NOTIFICATIONS = 1002;
    private static final String FILE_CHOOSER_PENDING = "dsh.fileChooserPending";

    private WebView web;
    private LinearLayout status;
    private TextView statusTitle;
    private TextView statusDetail;
    private ValueCallback<Uri[]> fileCallback;
    private boolean fileChooserPending;
    private final androidx.activity.result.ActivityResultLauncher<Intent> fileChooser = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                fileChooserPending = false;
                java.util.List<Uri> selected = new androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
                        .parseResult(result.getResultCode(), result.getData());
                finishFileChoice(selected.isEmpty() ? null : selected.toArray(new Uri[0]));
            });
    private String loadedUrl;
    private String bridgeOrigin;
    private Button start;
    private boolean moving;
    private PageBridge bridge;

    private DshRuntime.Listener listener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fileChooserPending = savedInstanceState != null && savedInstanceState.getBoolean(FILE_CHOOSER_PENDING);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        setContentView(buildContentView());
        listener = (state, detail) -> runOnUiThread(() -> render(state, detail));
        requestNotificationPermission();
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (web != null && web.canGoBack()) web.goBack();
                else finish();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        DshApp.runtime().addListener(listener);
        if (DshApp.runtime().autoStart()) DshService.start(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        DshApp.runtime().removeListener(listener);
    }

    /** The bundled Harness UI requires scripts; navigation and native messages are origin restricted. */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private static void enableHarnessScripts(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
    }

    /** The WebView plus the status panel shown until the server answers. */
    private View buildContentView() {
        FrameLayout root = new FrameLayout(this);

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        enableHarnessScripts(settings);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        web.setId(R.id.web);
        bridge = new PageBridge(this, DshApp.runtime());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // The dead renderer cannot be reused. Recreate only the UI; the Harness keeps its run.
                removeBridge();
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) parent.removeView(view);
                view.destroy();
                if (web == view) web = null;
                loadedUrl = null;
                if (!isFinishing() && !isDestroyed()) recreate();
                return true;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (trusted(url)) return false;
                if (request.isForMainFrame() && "https".equals(request.getUrl().getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()).addCategory(Intent.CATEGORY_BROWSABLE)); }
                    catch (ActivityNotFoundException unavailable) { Toast.makeText(MainActivity.this, R.string.link_no_browser, Toast.LENGTH_SHORT).show(); }
                }
                return true;
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                if (trusted(request.getUrl().toString())) return null;
                return new android.webkit.WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                        java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (trusted(url) && bridgeOrigin != null) view.evaluateJavascript(PageBridge.SCRIPT, null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                // One system picker at a time. A restored picker result belongs to the old WebView,
                // whose callback is cancelled on destruction, never to a new upload request.
                if (fileChooserPending || isFinishing() || isDestroyed() || !trusted(view.getUrl())) {
                    callback.onReceiveValue(null);
                    return true;
                }
                fileCallback = callback;
                fileChooserPending = true;
                try {
                    fileChooser.launch(Intent.createChooser(params.createIntent(), getString(R.string.choose_file)));
                } catch (ActivityNotFoundException noPicker) {
                    fileChooserPending = false;
                    finishFileChoice(null);
                    Toast.makeText(MainActivity.this, R.string.file_picker_unavailable, Toast.LENGTH_LONG).show();
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
        start = startButton();
        start.setVisibility(View.GONE);
        status.addView(start);

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

        EdgeToEdge.apply(this, column);
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
        popup.getMenu().add(0, 9, 7, R.string.menu_execution);
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
                if (!trusted(url)) throw new IllegalArgumentException("download outside runtime origin");
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
        if (isDestroyed() || web == null) return;
        start.setVisibility(state == DshRuntime.State.STOPPED || state == DshRuntime.State.FAILED
                || state == DshRuntime.State.IDLE ? View.VISIBLE : View.GONE);
        start.setEnabled(!DshApp.runtime().isRunning());
        if (state != DshRuntime.State.READY) {
            removeBridge();
            if (loadedUrl != null) { loadedUrl = null; web.loadUrl("about:blank"); }
        }
        switch (state) {
            case READY: {
                status.setVisibility(View.GONE);
                String url = DshApp.runtime().baseUrl();
                if (url != null && !url.equals(loadedUrl)) {
                    loadedUrl = url;
                    attachBridge(url);
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
                break;
            }
            case IDLE:
            case STOPPING:
            default: {
                status.setVisibility(View.VISIBLE);
                statusTitle.setText(state == DshRuntime.State.INSTALLING ? R.string.state_installing
                        : state == DshRuntime.State.STOPPING ? R.string.state_stopping
                        : state == DshRuntime.State.IDLE ? R.string.state_stopped : R.string.state_starting);
                statusDetail.setText(detail);
                break;
            }
        }
    }

    private Button startButton() {
        Button start = new Button(this);
        start.setText(R.string.start_runtime);
        start.setOnClickListener(view -> {
            if (DshApp.runtime().isRunning()) return;
            DshApp.runtime().setAutoStart(true);
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
                DshApp.runtime().setAutoStart(false);
                DshApp.runtime().stop();
                DshService.stop(this);
                Toast.makeText(this, R.string.runtime_stopped, Toast.LENGTH_SHORT).show();
                return true;
            case 7:
                throw new IllegalStateException("simulated failure, to check the crash recorder");
            case 8:
                moveProjectToSharedStorage();
                return true;
            case 9:
                chooseExecutionMode();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /** Offers the two roots the application can actually write to. */
    private void chooseWorkspace() {
        if (DshApp.runtime().isRunning()) {
            Toast.makeText(this, R.string.workspace_stop_first, Toast.LENGTH_LONG).show();
            return;
        }
        final File[] roots = new File[] { DshApp.runtime().privateWorkspace(), sharedWorkspace() };
        String[] labels = new String[] {
                getString(R.string.workspace_private, roots[0].getAbsolutePath()),
                getString(R.string.workspace_shared, roots[1].getAbsolutePath()),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_workspace)
                .setItems(labels, (dialog, which) -> {
                    if (which == 1 && !DshApp.runtime().hasSharedStorage()) { requestSharedStorage(); return; }
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

    /** Explain the stop before taking a consistent, resumable project copy. */
    private void moveProjectToSharedStorage() {
        if (moving) return;
        if (!DshApp.runtime().hasSharedStorage()) {
            Toast.makeText(this, R.string.move_needs_storage, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_move)
                .setMessage(R.string.move_stop_message)
                .setPositiveButton(R.string.move_stop_copy, (dialog, which) -> copyProjects())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void copyProjects() {
        if (moving) return;
        moving = true;
        Toast.makeText(this, R.string.move_started, Toast.LENGTH_SHORT).show();
        try {
            DshApp.runtime().migrate(sharedWorkspace()).whenComplete((result, failure) -> runOnUiThread(() -> {
                moving = false;
                if (isDestroyed() || isFinishing()) return;
                render(DshApp.runtime().state(), DshApp.runtime().detail());
                String message = failure != null ? getString(R.string.move_failed, failure.getMessage())
                        : !result.complete() ? getString(R.string.move_failed, result.problems())
                        : getString(R.string.move_verified, result.copied, result.identical, sharedWorkspace().getAbsolutePath());
                new AlertDialog.Builder(this).setTitle(R.string.menu_move).setMessage(message)
                        .setPositiveButton(android.R.string.ok, null).show();
            }));
        } catch (IllegalStateException busy) {
            moving = false;
            Toast.makeText(this, R.string.move_busy, Toast.LENGTH_LONG).show();
        }
    }

    private void chooseExecutionMode() {
        new AlertDialog.Builder(this).setTitle(R.string.menu_execution)
                .setMessage(R.string.execution_explanation)
                .setPositiveButton(R.string.execution_confirm, (dialog, which) -> saveExecutionMode(false))
                .setNeutralButton(R.string.execution_autonomous, (dialog, which) ->
                        new AlertDialog.Builder(this).setTitle(R.string.execution_autonomous)
                                .setMessage(R.string.execution_warning)
                                .setPositiveButton(R.string.execution_enable, (confirm, button) -> saveExecutionMode(true))
                                .setNegativeButton(android.R.string.cancel, null).show())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void saveExecutionMode(boolean autonomous) {
        DshApp.runtime().setAutonomous(autonomous);
        Toast.makeText(this, R.string.execution_saved, Toast.LENGTH_LONG).show();
    }

    private boolean trusted(String url) {
        String origin = TrustedOrigin.from(DshApp.runtime().baseUrl());
        return TrustedOrigin.allows(origin, url);
    }

    private void removeBridge() {
        if (web != null && bridgeOrigin != null) {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) {
                androidx.webkit.WebViewCompat.removeWebMessageListener(web, PageBridge.NAME);
            }
        }
        bridgeOrigin = null;
    }

    private void attachBridge(String url) {
        removeBridge();
        String origin = TrustedOrigin.from(url);
        if (origin == null || !androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        bridgeOrigin = origin;
        androidx.webkit.WebViewCompat.addWebMessageListener(web, PageBridge.NAME, java.util.Collections.singleton(origin),
                (view, message, sourceOrigin, isMainFrame, reply) -> {
                    if (isMainFrame && trusted(sourceOrigin.toString()) && trusted(view.getUrl()) && message.getData() != null) {
                        bridge.openFile(message.getData());
                    }
                });
    }

    /** Report a failure the platform raised, instead of ending the process. */
    private void reportFailure(String what, RuntimeException failure) {
        android.util.Log.w("DshMain", what, failure);
        Toast.makeText(this, what, Toast.LENGTH_LONG).show();
    }

    /** Sends the user to the all-files-access grant when the platform has one. */
    private void requestSharedStorage() {
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, R.string.storage_older_android, Toast.LENGTH_LONG).show();
            return;
        }
        if (Environment.isExternalStorageManager()) {
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
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            Toast.makeText(this, R.string.battery_settings_hint, Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, R.string.battery_settings_missing, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(FILE_CHOOSER_PENDING, fileChooserPending);
        super.onSaveInstanceState(state);
    }

    private void finishFileChoice(Uri[] value) {
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        if (callback != null) callback.onReceiveValue(value);
    }

    @Override
    protected void onDestroy() {
        finishFileChoice(null);
        removeBridge();
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
