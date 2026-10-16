package com.dsh.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONArray;

/** Opt-in real DeepSeek turns through the production WebView approval UI, in a temporary home. */
public final class LiveApprovalInstrumentation extends Instrumentation {
    private DshRuntime runtime;
    private Activity activity;
    private WebView web;
    private File files;
    private File workspace;
    private final JSONArray checks = new JSONArray();
    private final JSONArray uiChecks = new JSONArray();
    private final JSONArray screenshots = new JSONArray();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        waitForIdleSync();
        Bundle result = new Bundle(); boolean success = false;
        DshRuntime original = DshApp.runtime(); boolean auto = original.autoStart();
        java.lang.reflect.Field field = null;
        File input = new File(getTargetContext().getCacheDir(), "live-approval-input.json");
        try {
            JSONObject configuration = new JSONObject(read(input));
            Files.delete(input.toPath());
            String key = configuration.getString("apiKey");
            if (key.isEmpty()) throw new IllegalStateException("a configured DeepSeek credential is required");
            original.setAutoStart(false); original.stop(); waitStopped(original);
            files = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "live-approval-").toFile();
            Context isolated = new ContextWrapper(getTargetContext()) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return files; }
                @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences(files.getName() + "-" + name, mode);
                }
            };
            runtime = new DshRuntime(isolated); runtime.setAutoStart(false);
            File home = RuntimeLayout.dshHome(isolated); home.mkdirs();
            write(new File(home, "settings.yaml"), new JSONObject().put("ui-onboarding", new JSONObject()
                    .put("welcomeNoticeVersion", "2026-08-13.1")).toString());
            File fixture = new File(files, "live-approval-fixture.mjs");
            try (java.io.InputStream stream = getContext().getAssets().open("live-approval-fixture.mjs")) {
                Files.copy(stream, fixture.toPath());
            }
            write(new File(home, "cordis.patch.yml"),
                    "- id: session-log-deepseek\n  disabled: true\n- id: session-telemetry-otel\n  disabled: true\n"
                    + "- id: session-title-llm\n  disabled: true\n- id: llm-deepseek\n  config:\n"
                    + "    protocol: chat-completions\n    thinking: disabled\n    reasoningEffort: off\n    maxTokens: 1024\n"
                    + "    baseURL: https://api.deepseek.com\n"
                    + "- insert:\n    - id: live-approval-fixture\n      name: " + fixture.toURI() + "\n");
            field = DshApp.class.getDeclaredField("runtime"); field.setAccessible(true); field.set(null, runtime);
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // Do not persist the isolated credential until the provider has constructed a real WebView.
            write(new File(home, ".credentials.yaml"), new JSONObject().put("version", 1)
                    .put("refs", new JSONObject().put("DEEPSEEK_API_KEY", key)).put("records", new JSONObject()).toString());
            runtime.start(); waitReady(); web = activity.findViewById(R.id.web); workspace = runtime.servedWorkspace();
            String zone = new JSONArray("[" + js("Intl.DateTimeFormat().resolvedOptions().timeZone") + "]").getString(0);
            check("UTC".equals(zone) || zone.matches("[A-Za-z_]+/[A-Za-z_+/.-]+"),
                    "the test device must use an IANA timezone such as Asia/Shanghai; WebView reported " + zone);
            waitJS("!!document.querySelector('[aria-label=\"Choose workspace\"]')", 30000);
            check(TrustedOrigin.allows(TrustedOrigin.from(runtime.baseUrl()), new JSONArray("[" + js("location.href") + "]").getString(0)), "real Harness must load at its authenticated runtime origin");
            capture("initial"); uiPass("real Harness boot and workspace picker");
            // Startup may select the single registered workspace before the first touch arrives.
            if (!"true".equals(js("!!document.querySelector('[data-composer-input][contenteditable=true]')"))) {
                click("document.querySelector('[aria-label=\"Choose workspace\"]')");
                waitJS("!!document.querySelector('[data-composer-input][contenteditable=true]') || Array.from(document.querySelectorAll('[role=menuitem]')).some(e=>e.textContent.includes('Live approval regression'))", 10000);
                if (!"true".equals(js("!!document.querySelector('[data-composer-input][contenteditable=true]')")))
                    click("Array.from(document.querySelectorAll('[role=menuitem]')).find(e=>e.textContent.includes('Live approval regression'))");
            }
            waitJS("!!document.querySelector('[data-composer-input][contenteditable=true]')", 10000);
            settings();
            reloadHarness(false); capture("reloaded"); uiPass("workspace selection, enabled composer and page reload");
            exercise("allow", "Allow once", "allowed-once", true);
            reloadHarness(true); capture("resumed"); uiPass("persisted conversation restored after page reload");
            exercise("reject", "Reject", "rejected", false);
            exercise("stop", null, "process-stopped", false);
            JSONArray events = audit();
            check(count("approval/asked", null) == 3 && count("approval/decided", "allowed-once") == 1
                    && count("approval/decided", "rejected") == 1, "only the explicitly allowed command may receive a grant");
            boolean actualRoute = false;
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                if ("request/context".equals(event.optString("type")) && "deepseek-official".equals(event.optString("provider"))
                        && "deepseek-flash".equals(event.optString("model"))) actualRoute = true;
            }
            check(actualRoute, "the recorded route must be the real configured DeepSeek model");
            JSONObject report = new JSONObject().put("provider", "deepseek-official").put("model", "deepseek-flash")
                    .put("mockModel", false).put("ui", "production Android WebView").put("timeZone", zone).put("cases", checks)
                    .put("audit", events).put("androidApi", android.os.Build.VERSION.SDK_INT)
                    .put("appVersion", getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(), 0).versionName)
                    .put("webviewPackage", WebView.getCurrentWebViewPackage().packageName)
                    .put("webviewVersion", WebView.getCurrentWebViewPackage().versionName)
                    .put("uiChecks", uiChecks).put("screenshots", screenshots).put("interactionInput", "native injected touch");
            write(new File(getTargetContext().getCacheDir(), "live-approval-result.json"), report.toString(2));
            result.putString("stream", "\nPASS: 3 real-model approval interactions\n"); success = true;
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(error));
            try {
                JSONObject failure = new JSONObject().put("error", String.valueOf(error)).put("cases", checks).put("uiChecks", uiChecks).put("screenshots", screenshots);
                try { if (workspace != null) failure.put("audit", audit()); } catch (Exception detail) { failure.put("auditError", String.valueOf(detail)); }
                try { if (web != null) failure.put("pageText", js("document.body.innerText.slice(-6000)")); } catch (Exception detail) { failure.put("pageError", String.valueOf(detail)); }
                result.putString("diagnostic", failure.toString(2));
                write(new File(getTargetContext().getCacheDir(), "live-approval-result.json"), failure.toString(2));
            } catch (Exception ignored) { /* Preserve the original failure even if diagnostic projection fails. */ }
        } finally {
            try {
                if (runtime != null) { runtime.stop(); waitStopped(runtime); }
                if (activity != null) runOnMainSync(() -> activity.finish());
                if (field != null) field.set(null, original);
                original.setAutoStart(auto);
                Files.deleteIfExists(input.toPath());
                if (files != null) {
                    getTargetContext().deleteSharedPreferences(files.getName() + "-dsh"); delete(files);
                }
            } catch (Exception cleanup) { success = false; result.putString("cleanup", cleanup.getClass().getSimpleName()); }
        }
        finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private void exercise(String kind, String answer, String expected, boolean exists) throws Exception {
        progress("starting " + kind);
        File marker = new File(workspace, "live-approval-" + kind + ".txt");
        int priorTurns = count("turn/end", null);
        String prompt = "Approval regression " + kind + ": use exactly one bash tool call to run `printf '" + kind
                + "' > " + marker.getName() + "`. Do not use any other tool. If permission is denied, stop and do not retry or use another route. After the tool result reply only DONE.";
        js("(()=>{let e=document.querySelector('[data-composer-input][contenteditable=true]');e.focus();document.execCommand('insertText',false,"
                + JSONObject.quote(prompt) + ");return true})()");
        waitJS("!!document.querySelector('button[aria-label=\"Send message\"]:not(:disabled)')", 5000);
        click("document.querySelector('button[aria-label=\"Send message\"]')");
        progress("sent " + kind);
        waitJS("!document.querySelector('[data-composer-input]').textContent.trim()", 10000);
        long started = android.os.SystemClock.elapsedRealtime() + 10000;
        while (count("turn/start", null) <= priorTurns && android.os.SystemClock.elapsedRealtime() < started) Thread.sleep(100);
        check(count("turn/start", null) > priorTurns, "message must start a real turn");
        waitJS("!!document.querySelector('[data-approval-key]')", 120000);
        check(!marker.exists(), "tool must not execute before approval");
        capture(kind + "-pending");
        if (answer != null) {
            click("Array.from(document.querySelectorAll('[data-approval-key] button')).find(e=>e.textContent.trim()==="
                    + JSONObject.quote(answer) + ")");
            long until = android.os.SystemClock.elapsedRealtime() + 120000;
            while (count("turn/end", null) <= priorTurns && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(100);
            check(count("turn/end", null) > priorTurns, "real model turn must settle");
            waitJS("!document.querySelector('[data-approval-key]')", 5000);
        } else {
            // Approval replaces the visible composer. Use the native action users can actually reach.
            clickNative(getTargetContext().getString(R.string.action_more));
            Thread.sleep(350); // PopupWindow geometry settles after its nodes become visible.
            capture("native-menu");
            clickNative(getTargetContext().getString(R.string.menu_stop));
            waitStopped(runtime);
            check(runtime.baseUrl() == null && runtime.port() == 0, "process stop must clear serving authority");
        }
        capture(answer == null ? "stopped" : kind + "-finished");
        check(marker.exists() == exists, "file effect must match " + kind);
        if (exists) check(kind.equals(read(marker)), "allowed command content must match");
        if (answer != null) check(count("approval/decided", expected) == 1, "durable approval outcome must be " + expected);
        else check(count("approval/decided", "allowed-once") == 1, "stopping pending approval must not add a grant");
        checks.put(new JSONObject().put("case", kind).put("approvalVisible", true).put("fileAbsentBeforeDecision", true)
                .put("outcome", expected).put("fileExistsAfterDecision", marker.exists()));
        Bundle status = new Bundle(); status.putString("stream", "PASS: real DeepSeek " + kind + " interaction\n"); sendStatus(0, status);
    }
    private JSONArray audit() throws Exception {
        JSONArray result = new JSONArray(); File path = new File(workspace, "live-audit.jsonl");
        if (path.isFile()) for (String line : read(path).split("\n")) if (!line.isEmpty()) result.put(new JSONObject(line));
        return result;
    }
    private int count(String type, String outcome) throws Exception {
        int count = 0; JSONArray rows = audit();
        for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.getJSONObject(i);
            if (type.equals(row.optString("type")) && (outcome == null || outcome.equals(row.optString("outcome")))) count++;
        }
        return count;
    }
    private String js(String script) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<String> answer = new AtomicReference<>();
        runOnMainSync(() -> web.evaluateJavascript(script, value -> { answer.set(value); latch.countDown(); }));
        check(latch.await(5, TimeUnit.SECONDS), "JavaScript callback timed out"); return answer.get();
    }
    private void click(String element) throws Exception {
        check("true".equals(js("(()=>{let e=" + element + ";if(!e||e.disabled||!e.getClientRects().length)return false;"
                + "e.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});return true})()")), "visible UI action must be available");
        visualFence();
        String point = js("(()=>{let e=" + element + ";let r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;"
                + "let hit=document.elementFromPoint(x,y);return JSON.stringify({x:x*devicePixelRatio,y:y*devicePixelRatio,"
                + "reachable:!!hit&&(e===hit||e.contains(hit))})})()");
        JSONObject coordinates = new JSONObject(new JSONArray("[" + point + "]").getString(0));
        check(coordinates.getBoolean("reachable"), "UI action must be inside the viewport and reachable by touch");
        int[] origin = new int[2]; runOnMainSync(() -> web.getLocationOnScreen(origin));
        tap(origin[0] + (float) coordinates.getDouble("x"), origin[1] + (float) coordinates.getDouble("y"));
    }
    private void tap(float x, float y) {
        android.view.accessibility.AccessibilityNodeInfo foreground = getUiAutomation().getRootInActiveWindow();
        check(foreground != null && getTargetContext().getPackageName().contentEquals(foreground.getPackageName()),
                "test activity must own the foreground; dismiss old system crash dialogs before testing");
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 80, android.view.MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { check(getUiAutomation().injectInputEvent(down, true) && getUiAutomation().injectInputEvent(up, true), "native touch injection must succeed"); }
        finally { down.recycle(); up.recycle(); }
    }
    private void clickNative(String text) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 5000;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                if (text.contentEquals(node.getText() == null ? "" : node.getText()) && node.isVisibleToUser()) {
                    android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) { tap(bounds.exactCenterX(), bounds.exactCenterY()); return; }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("native menu action must be reachable: " + text);
    }
    private void visualFence() throws Exception {
        CountDownLatch frame = new CountDownLatch(1);
        runOnMainSync(() -> web.postVisualStateCallback(android.os.SystemClock.uptimeMillis(), new WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) { frame.countDown(); }
        }));
        check(frame.await(5, TimeUnit.SECONDS), "WebView must commit a visual frame"); waitForIdleSync();
    }
    private void capture(String label) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 5000;
        String foreground = "no accessibility root";
        while (android.os.SystemClock.elapsedRealtime() < end) {
            android.view.accessibility.AccessibilityNodeInfo node = getUiAutomation().getRootInActiveWindow();
            if (node != null && node.getPackageName() != null) {
                foreground = node.getPackageName().toString();
                if (getTargetContext().getPackageName().equals(foreground)) break;
            }
            Thread.sleep(50);
        }
        check(getTargetContext().getPackageName().equals(foreground),
                "test activity or its native popup must own the foreground before capturing UI evidence: " + foreground);
        visualFence();
        // WebView's visual callback precedes SurfaceFlinger presentation. Allow dialog/popup
        // transitions and accessibility updates to settle before reading the display surface.
        getUiAutomation().waitForIdle(350, 5000);
        android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
        check(image != null, "UI screenshot must be available");
        String name = "live-approval-ui-" + label + ".png";
        try (java.io.OutputStream stream = Files.newOutputStream(new File(getTargetContext().getCacheDir(), name).toPath())) {
            check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream), "UI screenshot must encode");
        } finally { image.recycle(); }
        screenshots.put(name);
    }
    private void settings() throws Exception {
        click("document.querySelector('button[aria-label=\"Settings\"]')");
        waitJS("!!document.querySelector('[role=dialog][aria-modal=true]')", 10000);
        waitJS("document.querySelector('[role=dialog]').textContent.includes('General')", 5000);
        check("true".equals(js("(()=>{let d=document.querySelector('[role=dialog]'),n=d.querySelector('nav'),c=n.nextElementSibling;"
                + "let o=c.lastElementChild;return innerWidth>600||(getComputedStyle(d).flexDirection==='column'&&"
                + "o.clientWidth>=d.clientWidth-40&&o.scrollWidth<=o.clientWidth+1)})()")), "phone settings controls must fit without horizontal scrolling");
        capture("settings");
        click("Array.from(document.querySelectorAll('[role=dialog] button')).find(e=>e.textContent.trim()==='Close')");
        waitJS("!document.querySelector('[role=dialog][aria-modal=true]')", 5000);
        uiPass("settings dialog opens and closes through touch");
    }
    private void reloadHarness(boolean history) throws Exception {
        js("window.__liveReload=true;true"); runOnMainSync(() -> web.reload());
        waitJS("!window.__liveReload && !!document.querySelector('[data-composer-input][contenteditable=true]')", 30000);
        if (history) waitJS("document.body.innerText.includes('Approval regression allow')", 10000);
    }
    private void uiPass(String message) throws Exception { uiChecks.put(message); progress("UI passed: " + message); }
    private void progress(String message) { Bundle status = new Bundle(); status.putString("stream", "Live approval: " + message + "\n"); sendStatus(0, status); }
    private void waitJS(String expression, long millis) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + millis;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            if ("true".equals(js(expression))) return; Thread.sleep(100);
        }
        throw new AssertionError("UI condition timed out: " + expression);
    }
    private void waitReady() throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 180000;
        while (runtime.state() != DshRuntime.State.READY && android.os.SystemClock.elapsedRealtime() < until) {
            if (runtime.state() == DshRuntime.State.FAILED) throw new AssertionError(runtime.detail() + "\n" + runtime.logText());
            Thread.sleep(50);
        }
        check(runtime.state() == DshRuntime.State.READY, "runtime must start");
    }
    private void waitStopped(DshRuntime value) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 15000;
        while (value.isRunning() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50);
        check(!value.isRunning(), "runtime must stop");
    }
    private static String read(File path) throws Exception { return new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8); }
    private static void write(File path, String text) throws Exception { Files.write(path.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void delete(File path) throws Exception {
        if (!Files.isSymbolicLink(path.toPath())) { File[] children = path.listFiles(); if (children != null) for (File child : children) delete(child); }
        Files.deleteIfExists(path.toPath());
    }
}
