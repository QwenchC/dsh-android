package com.dsh.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Keyless device regressions, using an isolated home instead of existing sessions or credentials. */
@android.annotation.TargetApi(29)
public final class RegressionInstrumentation extends Instrumentation {
    private DshRuntime runtime;
    private Activity activity;
    private int passed;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        waitForIdleSync();
        if (android.os.Build.VERSION.SDK_INT < 29) {
            Bundle unsupported = new Bundle(); unsupported.putString("stream", "Device regressions require API 29+ for window inspection\n");
            finish(Activity.RESULT_CANCELED, unsupported); return;
        }
        Bundle result = new Bundle();
        DshRuntime original = DshApp.runtime();
        boolean originalAutoStart = original.autoStart();
        boolean success = false;
        java.lang.reflect.Field field = null;
        File isolated = null;
        try {
            original.setAutoStart(false);
            original.stop();
            waitStopped(original);
            isolated = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "regression-").toFile();
            final File files = isolated;
            Context isolatedContext = new ContextWrapper(getTargetContext()) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return files; }
                @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences("regression-" + files.getName() + "-" + name, mode);
                }
            };
            runtime = new DshRuntime(isolatedContext);
            runtime.setAutoStart(false);
            field = DshApp.class.getDeclaredField("runtime"); field.setAccessible(true); field.set(null, runtime);
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

            // Cancel a real APK extraction, then prove a retry can start the packaged Node runtime.
            runtime.start();
            waitState(runtime, DshRuntime.State.INSTALLING, 10000);
            runtime.stop(); waitStopped(runtime);
            check(runtime.baseUrl() == null, "cancelled extraction cannot publish readiness");
            pass("cancel real extraction before launch");

            runtime.start(); waitState(runtime, DshRuntime.State.READY, 180000);
            check(runtime.baseUrl() != null, "ready origin is available");
            check(runtime.logText().contains("[android-policy] active") && !runtime.logText().contains("failed to import"), "production approval module must load successfully");
            HttpURLConnection connection = (HttpURLConnection) new URL(runtime.baseUrl()).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            check(connection.getResponseCode() == 303, "server accepts its run token"); connection.disconnect();
            check(!runtime.logText().matches("(?s).*token=[A-Za-z0-9_-]{20,}.*"), "logs redact access tokens");
            pass("packaged Node, policy overlay, authenticated HTTP, redacted log");

            WebView web = activity.findViewById(R.id.web);
            waitJavascript(web, "typeof window.DSHAndroid", "\"object\"", 30000);
            check("true".equals(js(web, "typeof window.DSHAndroid.postMessage === 'function'")), "restricted bridge exists");
            pass("trusted page receives the message bridge");

            String originBeforeRendererExit = runtime.baseUrl();
            ActivityMonitor recovery = addMonitor(MainActivity.class.getName(), null, false);
            final WebView initialWeb = web;
            runOnMainSync(() -> check(initialWeb.getWebViewRenderProcess() != null
                    && initialWeb.getWebViewRenderProcess().terminate(), "renderer termination must be available"));
            Activity recovered = recovery.waitForActivityWithTimeout(15000);
            removeMonitor(recovery);
            check(recovered != null, "activity must recover after renderer exit");
            activity = recovered;
            web = activity.findViewById(R.id.web);
            waitJavascript(web, "typeof window.DSHAndroid", "\"object\"", 30000);
            check(originBeforeRendererExit.equals(runtime.baseUrl()), "UI recovery must retain the same Harness process");
            pass("renderer exit recreates the UI while retaining the Harness run");

            // Same-origin frames receive the object but must not be admitted by the native handler.
            File bridgeFile = new File(runtime.servedWorkspace(), "regression-bridge.txt");
            Files.write(bridgeFile.toPath(), "bridge fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String quotedPath = org.json.JSONObject.quote(bridgeFile.getAbsolutePath());
            js(web, "var f=document.createElement('iframe');f.srcdoc='<script>window.DSHAndroid.postMessage(' + "
                    + org.json.JSONObject.quote(quotedPath) + " + ')<\\/script>';document.body.appendChild(f);true");
            Thread.sleep(500); waitForIdleSync();
            check(!visibleText("Open regression-bridge.txt"), "iframe cannot open a native file dialog");
            js(web, "window.DSHAndroid.postMessage(" + quotedPath + ");true");
            waitText("Open regression-bridge.txt", 5000);
            runOnMainSync(() -> {
                for (android.view.View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) clickText(root, "Preview in DSH");
            });
            pass("main frame admitted; iframe messages refused");

            File outside = new File(files, "outside-project.txt"); Files.write(outside.toPath(), "not shareable".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            js(web, "window.DSHAndroid.postMessage(" + org.json.JSONObject.quote(outside.getAbsolutePath()) + ");true");
            Thread.sleep(300); waitForIdleSync();
            check(!visibleText("Open outside-project.txt"), "bridge refuses paths outside the project");
            pass("native file path containment");

            final WebView recoveredWeb = web;
            runOnMainSync(() -> recoveredWeb.loadDataWithBaseURL("https://untrusted.invalid/", "<html><body>untrusted fixture</body></html>", "text/html", "UTF-8", null));
            waitJavascript(web, "typeof window.DSHAndroid", "\"undefined\"", 5000);
            pass("untrusted origin has no native bridge");

            // Run the host-skipped symbolic-link test against Android's actual filesystem.
            File source = new File(files, "migration-source"), home = new File(files, "migration-home"), target = new File(files, "migration-target");
            source.mkdirs(); home.mkdirs(); target.mkdirs();
            Files.createSymbolicLink(new File(source, "link").toPath(), outside.toPath());
            ProjectMigration.Result migration = ProjectMigration.copy(source, home, target);
            check(!migration.complete() && !new File(target, "link").exists(), "symlink is rejected without copying its contents");
            pass("Android migration symlink rejection");

            File providerFile = new File(new File(getTargetContext().getFilesDir(), ProjectFilesProvider.SERVED_ROOT),
                    "regression-" + System.nanoTime() + " #100% 中文.txt");
            providerFile.getParentFile().mkdirs();
            try {
                Files.write(providerFile.toPath(), "provider fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                android.net.Uri uri = new android.net.Uri.Builder().scheme("content")
                        .authority(getTargetContext().getPackageName() + ".files").path("/" + providerFile.getName()).build();
                try (java.io.InputStream input = getTargetContext().getContentResolver().openInputStream(uri)) {
                    byte[] bytes = new byte[32]; int count = input.read(bytes);
                    check("provider fixture".equals(new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8)), "escaped filename must resolve to its original file");
                }
                try (android.database.Cursor cursor = getTargetContext().getContentResolver().query(uri, null, null, null, null)) {
                    check(cursor != null && cursor.moveToFirst(), "provider exposes openable metadata");
                    check(providerFile.getName().equals(cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))), "display name matches");
                }
                android.net.Uri traversal = uri.buildUpon().path("/../home/.dsh/credentials.yaml").build();
                try {
                    getTargetContext().getContentResolver().openInputStream(traversal); throw new AssertionError("provider accepted traversal");
                } catch (java.io.FileNotFoundException refused) { /* Expected containment refusal. */ }
            } finally { Files.deleteIfExists(providerFile.toPath()); }
            pass("file provider metadata, escaped names, and traversal rejection");

            runtime.stop(); waitStopped(runtime);
            check(runtime.baseUrl() == null && runtime.port() == 0, "stop clears serving authority");
            check(!visibleText("Open regression-bridge.txt"), "dialog does not survive stop");
            File live = new File(files, "dsh/runtime");
            File cli = new File(live, "lib/bin.js");
            long oldSize = cli.length();
            Files.write(new File(live, RuntimeLayout.STAMP_FILE).toPath(), new byte[] { 'x' });
            runtime.start(); waitState(runtime, DshRuntime.State.INSTALLING, 10000);
            runtime.stop(); waitStopped(runtime);
            check(cli.isFile() && cli.length() == oldSize, "cancelled update retains the old runtime");
            pass("cancelled runtime update retains the installed CLI");
            runtime.start(); waitState(runtime, DshRuntime.State.READY, 180000);
            runtime.stop(); waitStopped(runtime);
            pass("real stop and restart settle cleanly");
            result.putString("stream", "\nPASS: " + passed + " device regressions\n");
            success = true;
        } catch (Throwable failure) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(failure));
            if (runtime != null) result.putString("runtimeLog", runtime.logText());

        } finally {
            try {
                if (runtime != null) { runtime.setAutoStart(false); runtime.stop(); waitStopped(runtime); }
                if (activity != null) runOnMainSync(() -> activity.finish());
                if (field != null) field.set(null, original);
                original.setAutoStart(originalAutoStart);
                // The test creates only this random cache subtree; project/session roots are untouched.
                if (isolated != null) {
                    getTargetContext().deleteSharedPreferences("regression-" + isolated.getName() + "-dsh");
                    delete(isolated);
                }
            } catch (Exception cleanupFailure) { android.util.Log.e("DshRegression", "test cleanup failed", cleanupFailure); }
        }
        finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void pass(String name) {
        passed++; Bundle status = new Bundle(); status.putString("stream", "PASS: " + name + "\n"); sendStatus(0, status);
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void waitState(DshRuntime value, DshRuntime.State desired, long millis) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + millis;
        while (value.state() != desired && android.os.SystemClock.elapsedRealtime() < deadline) {
            if (value.state() == DshRuntime.State.FAILED) throw new AssertionError(value.detail() + "\n" + value.logText());
            Thread.sleep(25);
        }
        check(value.state() == desired, "expected " + desired + ", got " + value.state());
    }
    private void waitStopped(DshRuntime value) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        while (value.isRunning() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
        check(!value.isRunning(), "runtime cleanup must settle");
    }
    private String js(WebView view, String script) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        runOnMainSync(() -> view.evaluateJavascript(script, result -> { value.set(result); ready.countDown(); }));
        check(ready.await(5, TimeUnit.SECONDS), "JavaScript callback timed out"); return value.get();
    }
    private void waitJavascript(WebView view, String script, String expected, long millis) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + millis;
        while (android.os.SystemClock.elapsedRealtime() < deadline) { if (expected.equals(js(view, script))) return; Thread.sleep(100); }
        throw new AssertionError("JavaScript did not return " + expected);
    }
    private boolean visibleText(String text) {
        java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
        runOnMainSync(() -> { for (android.view.View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
            if (hasText(root, text)) found.set(true);
        } });
        return found.get();
    }
    private void waitText(String text, long millis) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + millis;
        while (android.os.SystemClock.elapsedRealtime() < until) { if (visibleText(text)) return; Thread.sleep(50); }
        throw new AssertionError("native dialog not shown");
    }
    private boolean hasText(android.view.View view, String text) {
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText())) return true;
        if (view instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) {
            if (hasText(((android.view.ViewGroup) view).getChildAt(i), text)) return true;
        }
        return false;
    }
    private void clickText(android.view.View view, String text) {
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText())) view.performClick();
        if (view instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) clickText(((android.view.ViewGroup) view).getChildAt(i), text);
    }
    private void delete(File file) throws Exception {
        if (Files.isSymbolicLink(file.toPath())) { Files.delete(file.toPath()); return; }
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child);
        Files.deleteIfExists(file.toPath());
    }
}
