package com.dsh.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.EditText;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** API 26+ native compatibility checks; no model credentials or WindowInspector dependency. */
public final class PlatformCompatibilityInstrumentation extends Instrumentation {
    private MainActivity activity;
    private DshRuntime runtime;
    private File files;
    private int passed;
    private final AtomicReference<AssertionError> monitorFailure = new AtomicReference<>();
    @Override public void runOnMainSync(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        super.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError("main-thread check failed", failure.get());
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        waitForIdleSync();
        DshRuntime original = DshApp.runtime(); boolean auto = original.autoStart();
        java.lang.reflect.Field field = null; Bundle report = new Bundle(); boolean success = false;
        try {
            original.setAutoStart(false); original.stop(); waitStopped(original);
            files = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "platform-api-").toFile();
            Context context = new ContextWrapper(getTargetContext()) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return files; }
                @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences(files.getName() + "-" + name, mode);
                }
            };
            runtime = new DshRuntime(context); runtime.setAutoStart(false);
            field = DshApp.class.getDeclaredField("runtime"); field.setAccessible(true); field.set(null, runtime);
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runtime.start(); waitReady();
            pass("packaged runtime and approval module ready on API " + android.os.Build.VERSION.SDK_INT);
            checkInsets(activity); pass("main screen reserves real system bars");
            syntheticInsets(); pass("compat insets combine bars, cutout and IME without accumulating padding");
            keyboard(); pass("real keyboard insets reserve visible content and restore after hiding");
            screen(FilesActivity.class); pass("file screen reserves real system bars");
            screen(LogActivity.class); pass("log screen reserves real system bars");
            fixturePage();
            storage(); pass("DOM storage and IndexedDB work without enabling WebSQL");
            Uri first = Uri.parse("content://com.dsh.android.files/platform-first.txt");
            Uri second = Uri.parse("content://com.dsh.android.files/platform-second.txt");
            resultCase(new Intent().setData(first), Activity.RESULT_OK, new Uri[] {first});
            pass("single URI returns once through the real Activity Result registry");
            Intent multiple = new Intent(); ClipData clip = ClipData.newRawUri("files", first); clip.addItem(new ClipData.Item(second)); multiple.setClipData(clip);
            resultCase(multiple, Activity.RESULT_OK, new Uri[] {first, second});
            resultCase(new Intent(multiple).setData(first), Activity.RESULT_OK, new Uri[] {first, second});
            pass("multiple URIs return once through the real Activity Result registry");
            realWebUpload(); pass("real WebView file input reads selected content URI through the modern result flow");
            resultCase(null, Activity.RESULT_CANCELED, null); pass("picker cancellation settles its callback once");
            missingPicker(); pass("unavailable picker cancels once and allows a later request");
            realPickerLifecycle(); pass("real picker, overlapping request, recreation and late result keep callback ownership");
            final WebView web = web();
            runOnMainSync(() -> web.loadUrl("about:blank"));
            waitUntil(() -> "\"about:blank\"".equals(js("location.href")), 5000, "non-runtime page loads");
            Choice denied = request(); check(denied.calls.get() == 1 && denied.value.get() == null, "foreign page cannot launch a picker");
            pass("non-runtime page cannot launch a native file picker");
            nativeMenuStop(); pass("native menu stops the real runtime while the keyboard is visible");
            report.putString("stream", "\nPASS: " + passed + " platform compatibility checks on API " + android.os.Build.VERSION.SDK_INT + "\n");
            success = true;
        } catch (Throwable failure) {
            report.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(failure));
            try { report.putString("stream", report.getString("stream") + "\nPage: " + js("document.body ? document.body.innerText.slice(0,1000) : 'NO BODY'")); }
            catch (Exception ignored) { /* Keep the original failure. */ }
            if (runtime != null) report.putString("runtimeLog", runtime.logText());
        } finally {
            try {
                if (runtime != null) { runtime.stop(); waitStopped(runtime); }
                if (activity != null) runOnMainSync(() -> activity.finish());
                if (field != null) field.set(null, original);
                original.setAutoStart(auto);
                if (files != null) { getTargetContext().deleteSharedPreferences(files.getName() + "-dsh"); delete(files); }
            } catch (Exception failure) { success = false; report.putString("cleanup", failure.toString()); }
        }
        finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
    }
    private void syntheticInsets() {
        runOnMainSync(() -> {
            View view = new View(activity); view.setPadding(1, 2, 3, 4); EdgeToEdge.apply(activity, view);
            boolean typed = android.os.Build.VERSION.SDK_INT >= 30;
            WindowInsetsCompat.Builder builder = new WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(5, 15, 7, 19));
            if (typed) builder.setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(40, 0, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 200));
            WindowInsetsCompat shown = builder.build();
            ViewCompat.dispatchApplyWindowInsets(view, shown); ViewCompat.dispatchApplyWindowInsets(view, shown);
            check(view.getPaddingLeft() == (typed ? 41 : 6) && view.getPaddingTop() == 17 && view.getPaddingRight() == 10
                    && view.getPaddingBottom() == (typed ? 204 : 23), "inset union preserves original padding without duplication: "
                    + view.getPaddingLeft() + "," + view.getPaddingTop() + "," + view.getPaddingRight() + "," + view.getPaddingBottom());
            ViewCompat.dispatchApplyWindowInsets(view, new WindowInsetsCompat.Builder(shown)
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE).build());
            check(view.getPaddingBottom() == 23, "hiding IME restores navigation inset plus original padding");
        });
    }
    private void checkInsets(Activity target) throws Exception {
        View root = ((ViewGroup) target.findViewById(android.R.id.content)).getChildAt(0);
        waitUntil(() -> {
            AtomicReference<Boolean> result = new AtomicReference<>(false);
            runOnMainSync(() -> {
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
                if (insets == null) return;
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                result.set(root.getPaddingLeft() >= bars.left && root.getPaddingTop() >= bars.top
                        && root.getPaddingRight() >= bars.right && root.getPaddingBottom() >= bars.bottom
                        && bars.top > 0 && root.getHeight() > root.getPaddingTop() + root.getPaddingBottom());
            });
            return result.get();
        }, 10000, "screen must reserve system bars");
    }
    private void keyboard() throws Exception {
        ViewGroup container = activity.findViewById(android.R.id.content);
        View root = container.getChildAt(0); EditText editor = new EditText(activity);
        runOnMainSync(() -> {
            editor.setSingleLine(true); editor.setText("keyboard fixture");
            container.addView(editor, new ViewGroup.LayoutParams(300, 120)); editor.requestFocus();
            new WindowInsetsControllerCompat(activity.getWindow(), editor).show(WindowInsetsCompat.Type.ime());
        });
        try {
            waitUntil(() -> {
                AtomicReference<Boolean> shown = new AtomicReference<>(false);
                runOnMainSync(() -> {
                    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
                    shown.set(insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())
                            && root.getPaddingBottom() >= insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
                }); return shown.get();
            }, 10000, "real keyboard must produce reserved IME insets");
            android.graphics.Bitmap shot = getUiAutomation().takeScreenshot();
            check(shot != null, "screen geometry must be available"); int screenHeight = shot.getHeight(); shot.recycle();
            runOnMainSync(() -> {
                Insets ime = ViewCompat.getRootWindowInsets(root).getInsets(WindowInsetsCompat.Type.ime());
                int[] position = new int[2]; root.getLocationOnScreen(position);
                int usableBottom = position[1] + root.getHeight() - root.getPaddingBottom();
                check(Math.abs(usableBottom - (screenHeight - ime.bottom)) < 8,
                        "keyboard space must be reserved once, not twice: " + usableBottom + " vs " + (screenHeight - ime.bottom));
            });
        } finally {
            runOnMainSync(() -> { new WindowInsetsControllerCompat(activity.getWindow(), editor).hide(WindowInsetsCompat.Type.ime()); container.removeView(editor); });
        }
        waitUntil(() -> {
            AtomicReference<Boolean> hidden = new AtomicReference<>(false);
            runOnMainSync(() -> { WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
                hidden.set(insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())
                        && root.getPaddingBottom() == insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).bottom);
            }); return hidden.get();
        }, 10000, "keyboard hiding restores bar insets");
    }
    private void screen(Class<? extends Activity> type) throws Exception {
        Activity opened = startActivitySync(new Intent(getTargetContext(), type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try { checkInsets(opened); } finally {
            runOnMainSync(() -> { opened.finish(); activity.startActivity(new Intent(activity, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); });
            waitUntil(() -> activity.hasWindowFocus(), 10000, "main activity regains focus");
        }
    }
    private void fixturePage() throws Exception {
        WebView web = web();
        String fixtureUrl = TrustedOrigin.from(runtime.baseUrl()) + "/__platform_test__";
        runOnMainSync(() -> {
            android.webkit.WebViewClient original = web.getWebViewClient();
            // Only a static test document is supplied here. Other requests keep the production origin rules.
            web.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                    if (fixtureUrl.equals(request.getUrl().toString())) return new android.webkit.WebResourceResponse("text/html", "UTF-8",
                            new java.io.ByteArrayInputStream("<html><meta name=viewport content='width=device-width,initial-scale=1'><body>platform fixture<input type=file multiple></body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    return original.shouldInterceptRequest(view, request);
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                    return original.shouldOverrideUrlLoading(view, request);
                }
                @Override public void onPageFinished(WebView view, String url) { original.onPageFinished(view, url); }
            });
            web.loadUrl(fixtureUrl);
        });
        waitUntil(() -> "true".equals(js("document.body && document.body.textContent.indexOf('platform fixture')>=0")), 10000, "trusted HTML fixture");
    }
    private void storage() throws Exception {
        String key = "platform-api-" + System.nanoTime();
        String q = org.json.JSONObject.quote(key);
        js("localStorage.setItem(" + q + ",'stored');sessionStorage.setItem(" + q + ",'session');"
                + "window.storageResult='pending';var r=indexedDB.open(" + q + ",1);r.onupgradeneeded=function(){r.result.createObjectStore('values')};"
                + "r.onerror=function(){window.storageResult='error'};r.onsuccess=function(){var db=r.result;"
                + "var t=db.transaction('values','readwrite');t.objectStore('values').put('indexed','k');t.oncomplete=function(){"
                + "var v=db.transaction('values').objectStore('values').get('k');v.onsuccess=function(){"
                + "window.storageResult=(v.result==='indexed'&&localStorage.getItem(" + q + ")==='stored'&&sessionStorage.getItem(" + q + ")==='session')?'ok':'bad';"
                + "db.close();indexedDB.deleteDatabase(" + q + ");localStorage.removeItem(" + q + ");sessionStorage.removeItem(" + q + ")}}};true");
        waitUntil(() -> "\"ok\"".equals(js("window.storageResult")), 10000, "DOM and IndexedDB storage");
    }
    private final class Choice {
        final AtomicInteger calls = new AtomicInteger(); final AtomicReference<Uri[]> value = new AtomicReference<>();
    }
    private Choice request() {
        Choice choice = new Choice();
        runOnMainSync(() -> {
            WebView web = web();
            boolean handled = web.getWebChromeClient().onShowFileChooser(web, value -> { choice.value.set(value); choice.calls.incrementAndGet(); },
                    new WebChromeClient.FileChooserParams() {
                        @Override public int getMode() { return MODE_OPEN_MULTIPLE; }
                        @Override public String[] getAcceptTypes() { return new String[] {"text/plain"}; }
                        @Override public boolean isCaptureEnabled() { return false; }
                        @Override public CharSequence getTitle() { return "Compatibility file choice"; }
                        @Override public String getFilenameHint() { return null; }
                        @Override public Intent createIntent() { return new Intent(Intent.ACTION_GET_CONTENT).setType("text/plain")
                                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); }
                    });
            check(handled, "file choice must be handled");
        }); return choice;
    }
    private ActivityMonitor intercept(java.util.function.Function<Intent, ActivityResult> response) {
        return intercept(response, true);
    }
    private ActivityMonitor intercept(java.util.function.Function<Intent, ActivityResult> response, boolean controlledParams) {
        ActivityMonitor monitor = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                try {
                    check(Intent.ACTION_CHOOSER.equals(intent.getAction()), "system chooser wrapper must remain intact");
                    Intent nested = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent.class);
                    check(nested != null, "WebView picker intent must remain intact");
                    // Old WebViews can omit ALLOW_MULTIPLE. Assert exact extras on our controlled params;
                    // the real DOM-input case proves the platform-generated intent can return readable content.
                    if (controlledParams) check(Intent.ACTION_GET_CONTENT.equals(nested.getAction())
                            && "text/plain".equals(nested.getType())
                            && nested.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false), "picker MIME/multiple intent must be preserved");
                } catch (AssertionError failure) {
                    monitorFailure.set(failure);
                    return new ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return response.apply(intent);
            }
        }; addMonitor(monitor); return monitor;
    }
    private void resultCase(Intent data, int code, Uri[] expected) throws Exception {
        ActivityMonitor monitor = intercept(intent -> new ActivityResult(code, data));
        try {
            Choice choice = request(); waitUntil(() -> choice.calls.get() == 1, 5000, "picker result callback");
            waitForIdleSync(); check(choice.calls.get() == 1 && java.util.Arrays.equals(expected, choice.value.get()), "picker result must match exactly once");
        } finally { removeMonitor(monitor); }
    }
    private void missingPicker() throws Exception {
        ActivityMonitor monitor = intercept(intent -> { throw new android.content.ActivityNotFoundException("test missing picker"); });
        try { Choice choice = request(); check(choice.calls.get() == 1 && choice.value.get() == null, "launch failure must cancel once"); }
        finally { removeMonitor(monitor); }
        resultCase(null, Activity.RESULT_CANCELED, null);
    }
    private void realWebUpload() throws Exception {
        File file = new File(new File(getTargetContext().getFilesDir(), ProjectFilesProvider.SERVED_ROOT), "platform-upload-" + System.nanoTime() + ".txt");
        file.getParentFile().mkdirs(); Files.createFile(file.toPath());
        Uri uri = new Uri.Builder().scheme("content").authority(getTargetContext().getPackageName() + ".files").path("/" + file.getName()).build();
        AtomicInteger launches = new AtomicInteger();
        ActivityMonitor monitor = intercept(intent -> { launches.incrementAndGet(); return new ActivityResult(Activity.RESULT_OK, new Intent().setData(uri)); }, false);
        try {
            Files.write(file.toPath(), "native upload fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            js("window.uploadResult='pending';document.querySelector('input').onchange=function(){var r=new FileReader();"
                    + "r.onload=function(){window.uploadResult=r.result};r.onerror=function(){window.uploadResult='error'};r.readAsText(this.files[0])};true");
            String encoded = js("(function(){var r=document.querySelector('input').getBoundingClientRect();return JSON.stringify({x:(r.left+r.width/2)*devicePixelRatio,y:(r.top+r.height/2)*devicePixelRatio})})()");
            org.json.JSONObject point = new org.json.JSONObject(new org.json.JSONArray("[" + encoded + "]").getString(0));
            int[] origin = new int[2]; runOnMainSync(() -> web().getLocationOnScreen(origin));
            float x = origin[0] + (float) point.getDouble("x"), y = origin[1] + (float) point.getDouble("y");
            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 80, android.view.MotionEvent.ACTION_UP, x, y, 0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try { check(getUiAutomation().injectInputEvent(down, true) && getUiAutomation().injectInputEvent(up, true), "file-input touch must inject"); }
            finally { down.recycle(); up.recycle(); }
            waitUntil(() -> "\"native upload fixture\"".equals(js("window.uploadResult")), 10000, "real WebView upload reads file contents");
            check(launches.get() == 1 && !pending(), "real file input completes exactly one picker request");
        } finally { removeMonitor(monitor); Files.deleteIfExists(file.toPath()); }
    }
    private void realPickerLifecycle() throws Exception {
        Choice old = request();
        waitUntil(() -> {
            android.view.accessibility.AccessibilityNodeInfo node = getUiAutomation().getRootInActiveWindow();
            return node != null && node.getPackageName() != null && !getTargetContext().getPackageName().contentEquals(node.getPackageName());
        }, 15000, "real system chooser appears");
        Choice overlap = request(); check(overlap.calls.get() == 1 && overlap.value.get() == null && old.calls.get() == 0, "overlap cannot steal picker ownership");
        ActivityMonitor recreation = addMonitor(MainActivity.class.getName(), null, false);
        try {
            runOnMainSync(() -> activity.recreate());
            MainActivity recovered = (MainActivity) recreation.waitForActivityWithTimeout(15000);
            check(recovered != null, "activity recreation completes"); activity = recovered;
            check(old.calls.get() == 1 && old.value.get() == null, "destroyed WebView callback cancels exactly once");
            check(pending(), "pending picker ownership must survive activity recreation");
            Choice premature = request(); check(premature.calls.get() == 1 && premature.value.get() == null, "restored pending result blocks new upload");
        } finally { removeMonitor(recreation); }
        for (int attempt = 0; attempt < 4 && pending(); attempt++) {
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            Thread.sleep(400);
        }
        waitUntil(() -> !pending(), 10000, "late picker cancellation clears restored ownership");
        check(old.calls.get() == 1, "late result cannot call destroyed WebView again");
        fixturePage(); resultCase(new Intent().setData(Uri.parse("content://com.dsh.android.files/new-request.txt")), Activity.RESULT_OK,
                new Uri[] {Uri.parse("content://com.dsh.android.files/new-request.txt")});
    }
    private void nativeMenuStop() throws Exception {
        ViewGroup content = activity.findViewById(android.R.id.content);
        EditText editor = new EditText(activity);
        runOnMainSync(() -> {
            content.addView(editor, new ViewGroup.LayoutParams(300, 120)); editor.requestFocus();
            new WindowInsetsControllerCompat(activity.getWindow(), editor).show(WindowInsetsCompat.Type.ime());
        });
        waitUntil(() -> {
            AtomicReference<Boolean> visible = new AtomicReference<>(false);
            runOnMainSync(() -> { WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(editor);
                visible.set(insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())); }); return visible.get();
        }, 5000, "IME must be visible before opening native menu");
        tapNative(getTargetContext().getString(R.string.action_more));
        // PopupWindow animates after accessibility nodes appear. Wait for its geometry to settle.
        Thread.sleep(350);
        android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
        if (image != null) try (java.io.OutputStream out = Files.newOutputStream(new File(getTargetContext().getCacheDir(), "platform-native-menu.png").toPath())) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { image.recycle(); }
        tapNative(getTargetContext().getString(R.string.menu_stop)); waitStopped(runtime);
        check(runtime.baseUrl() == null && runtime.port() == 0, "native stop revokes serving authority");
        runOnMainSync(() -> content.removeView(editor));
    }
    private void tapNative(String text) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 5000;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                if (text.contentEquals(node.getText() == null ? "" : node.getText()) && node.isVisibleToUser()) {
                    android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
                    if (bounds.isEmpty()) continue;
                    Bundle status = new Bundle(); status.putString("stream", "Native menu touch: " + text + " at " + bounds + "\n"); sendStatus(0, status);
                    long now = android.os.SystemClock.uptimeMillis();
                    android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0);
                    android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 80, android.view.MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0);
                    down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                    try { check(getUiAutomation().injectInputEvent(down, true) && getUiAutomation().injectInputEvent(up, true), "native touch must inject"); }
                    finally { down.recycle(); up.recycle(); }
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("native menu action must be visible: " + text);
    }
    private boolean pending() throws Exception {
        java.lang.reflect.Field field = MainActivity.class.getDeclaredField("fileChooserPending"); field.setAccessible(true);
        AtomicReference<Boolean> value = new AtomicReference<>(); runOnMainSync(() -> { try { value.set(field.getBoolean(activity)); } catch (IllegalAccessException e) { throw new AssertionError(e); } });
        return value.get();
    }
    private WebView web() { return activity.findViewById(R.id.web); }
    private String js(String code) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        runOnMainSync(() -> web().evaluateJavascript(code, result -> { value.set(result); latch.countDown(); }));
        check(latch.await(5, TimeUnit.SECONDS), "JavaScript callback must arrive"); return value.get();
    }
    private interface Condition { boolean ready() throws Exception; }
    private void waitUntil(Condition condition, long millis, String message) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + millis;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            if (monitorFailure.get() != null) throw monitorFailure.get();
            if (condition.ready()) return; Thread.sleep(50);
        }
        throw new AssertionError(message);
    }
    private void waitReady() throws Exception {
        waitUntil(() -> { if (runtime.state() == DshRuntime.State.FAILED) throw new AssertionError(runtime.detail() + "\n" + runtime.logText());
            return runtime.state() == DshRuntime.State.READY; }, 180000, "runtime readiness");
    }
    private void waitStopped(DshRuntime value) throws Exception { waitUntil(() -> !value.isRunning(), 15000, "runtime cleanup"); }
    private void pass(String message) { passed++; Bundle status = new Bundle(); status.putString("stream", "PASS: " + message + "\n"); sendStatus(0, status); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void delete(File path) throws Exception {
        if (!Files.isSymbolicLink(path.toPath())) { File[] children = path.listFiles(); if (children != null) for (File child : children) delete(child); }
        Files.deleteIfExists(path.toPath());
    }
}
