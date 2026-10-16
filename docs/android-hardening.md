# Android reliability and permission changes (0.1.4)

The Android carrier now owns cancellation from extraction through process exit. Project copying waits for that exit, verifies content, and changes the saved workspace only after complete success. Tool approval and WebView origin checks are independent of Android's application sandbox.

## Runtime lifecycle

`RuntimeController` admits one run synchronously before scheduling any work. Repeated starts coalesce. Stop marks that run cancelled, clears its serving URL immediately, interrupts blocking startup or process waits, and returns without waiting on the activity thread. The worker sends SIGTERM, gives the Harness five seconds for its existing disposal of managed subprocesses, escalates to a forced Node exit if necessary, waits for exit, and releases the wake lock on every terminal path. A new run cannot start until cleanup completes. A cleanup failure is reported as a failure even after explicit Stop and prevents copying projects or starting another run until the application is restarted. Arbitrary commands which deliberately leave unmanaged background daemons are not a process-confinement guarantee.

Each run owns its announced URL and selected port. Only a token URL with that exact loopback port can establish readiness. Old output cannot authorize a later run. New log entries redact URL access tokens, and notifications show the origin without its token. Existing historical log files are not rewritten.

Extraction writes `runtime.installing` while retaining the live runtime. Cancellation checks run between ZIP entries and copy chunks. Only a completed tree with its own payload stamp and CLI entry becomes live; the previous tree is retained during the rename and restored on the next start if activation was interrupted. User projects, sessions, settings, and credentials are outside those runtime directories. The first 0.1.1 start re-extracts once because older versions stored the stamp outside the runtime tree.

An explicit stop persists until the user presses Start. Merely returning from the file list, log view, or another application no longer restarts a stopped Harness. The foreground service registers one listener and ends on stopped/failed states. The CPU wake lock uses a five-minute timeout, renewed each minute while the run owns it. Stop invalidates queued renewals and releases the lock; renewal failure stops the run. The server still holds the CPU awake while idle; idle power optimization is deferred.

The battery menu opens Android's standard battery optimization settings with a short instruction. It no longer requests a direct exemption or declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. The user chooses the exemption in system settings; opening that screen does not imply a grant.

## Project copying and workspace changes

The copy action explains that it will interrupt the active turn and requires the user to choose **Stop and copy**. Runtime maintenance prevents concurrent starts and waits for the stopped process before reading projects. The app-private workspace and projects under the old private home are copied, excluding the home's `.dsh` state directory. Original files are retained.

New files are copied into temporary siblings, checked using SHA-256 against the source, and moved into place without replacement. Existing files count as complete only if their content matches; differing content is reported as a conflict. Directory/file collisions, unreadable paths, links in either tree, verification failures, and permission errors prevent switching the saved workspace. Completed copies remain available for a retry, which verifies and skips identical files. The dialog lists conflicts and failures instead of reporting partial success as a completed move. Sources can contain duplicate relative paths; differing files from the two source roots are conflicts too.

After a successful copy, press Start to launch with the new working directory and home. Existing sessions retain their recorded project locations and are not rewritten; resume them against the original retained files or create a new session for the copied project. Changing workspace directly is available only while the Harness is stopped. A configured but unavailable directory fails at startup instead of silently falling back to a different project.

All-files access is checked against the current Android grant on every use. Shared project folders currently require Android 11/API 30 or later; Android 8–10 retain app-private projects. The removed API call ordering bug no longer calls an API 30 method on those earlier systems.

## Tool execution permissions

The app's **Tool execution permissions** action defaults to **Require confirmation**. Its process-start overlay mounts `android-policy.mjs` at `tools/pre-execute`. It asks for each effectful or unclassified tool call, including shell, terminal, write/edit, custom tools, subagent launches, and the outer `run_code` program. Nested PTC tool calls pass through the same registry gate. The read/search and basic user-interaction allowlist is explicit in that file; newly introduced tools require approval by default.

The gate uses the existing Harness approval service, its Web UI answerer, cancellation, and durable `approval/asked` and `approval/decided` records. Missing answerers, rejected answers, and cancelled waits prevent execution. A stored per-session `approval/policy: never` still rejects requests; it is not an automatic allow. The native overlay sets the default service policy to `ask` independently of the filesystem policy. File tools default to `workspace-write`. Existing sessions keep their own recorded overrides.

**Autonomous execution** requires a separate warning and explicit selection in the native menu. It skips this additional gate after the next Stop/Start, while continuing to delegate to other tool policies. A preference change never silently changes an active run's authority. The overlay files are bundled separately from the large runtime ZIP, so updating approval behavior does not require rebuilding upstream libraries or editing user profiles. The launcher passes `--profile web --patch <overlay>` before the Web server's own flags. It materializes the module's literal file URI into the patch: loader entry names do not evaluate `!!js`. Readiness requires the approval module's activation message as well as the authenticated server URL; a partially loaded profile cannot expose a ready UI.

Approval is not process confinement. Approved shell commands and programs retain the application's authority, including access to private state and any shared storage Android grants. Neither a working directory nor `workspace-write` confines arbitrary shell or Node code. Plugins and profile files execute inside that same process and remain trusted code.

## WebView and file handoff

The native file bridge uses AndroidX WebKit's origin-restricted message listener rather than `addJavascriptInterface`. Its allowlist is the active run's exact `http://127.0.0.1:<port>` origin. The handler additionally requires the main frame, a matching source origin, and a currently trusted top-level URL. Same-origin iframes cannot trigger native file dialogs. The injected file-click script calls `postMessage`; WebViews lacking `WEB_MESSAGE_LISTENER` retain the Harness preview and Files screen without exposing a fallback native interface.

Top-level external HTTPS links open in the device browser. Other external navigations and non-runtime resource requests are refused. HTTP is allowed only for loopback in the Android network security configuration; Node's own model transport is separate from this platform WebView configuration. File URL access remains disabled, mixed content is disabled, and WebView inspection is enabled only for debuggable builds. JavaScript remains necessary for the bundled Harness and has one documented, method-scoped `SetJavaScriptEnabled` lint exemption. Stop removes the bridge and clears the displayed runtime page. Activity teardown closes pending upload callbacks and destroys the WebView. Renderer exit destroys the unusable WebView and recreates the activity while preserving the owned Harness process and its origin.

File paths are canonicalized and must remain inside the active or retained private workspace before any native file metadata or dialog is shown. The file provider remains read-only, supplies display-name/size metadata to viewers, and refuses paths outside its private workspace. URI construction now escapes filenames containing `#`, `%`, spaces, or non-ASCII characters. Files outside these served roots require opening their own project. Session-specific relative paths remain resolved against the active workspace, with the retained private workspace as fallback.

Both main and file-list activities use AndroidX's back dispatcher, including Android 16 predictive-back handling.

File uploads use a launcher registered with AndroidX Activity Result before activity creation. The original WebView picker intent (including MIME types and multiple-selection flags) is preserved inside the system chooser. AndroidX parses single `data` URIs and `ClipData` together, keeping order and removing duplicates; cancellation and a missing picker finish the callback once. Only one picker may be outstanding. Teardown cancels the old WebView callback, while saved state retains the outstanding-picker flag until its result arrives. A recreated activity therefore cannot give an old result to a new upload. Pages outside the active runtime origin cannot open the picker.

WebSQL enabling has been removed because that platform API is deprecated. DOM storage remains enabled; localStorage, sessionStorage and IndexedDB are checked separately. The main, file and log screens explicitly lay out edge to edge with AndroidX Core 1.19.1. They reserve the union of system bars, display cutout and IME insets, adding the original padding once. This replaces the four deprecated system-window-inset getters and keeps real keyboard space from being counted twice.

## Verification

The combined check is `scripts/verify-app.ps1` (add `-Offline` for cached dependencies and `-DeviceSerial <serial>` for the device runner). Run the Android checks individually from `android-app` with JDK 21 and the Android SDK configured:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --warning-mode=fail
```

The host policy tests use the real built Harness ToolRuntime, Session, approval service, and a Loader reading a fixture `cordis.yml`. They require the symlink-free `dist/payload` tree produced by `scripts/package-app.ps1`:

```powershell
node --expose-internals --test scripts/tests/android-policy.test.mjs
```

The keyless Android regression runner requires API 29+ for native window inspection. Install the debug app and test APK, then run:

```powershell
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
adb install -r android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.dsh.android.test/com.dsh.android.RegressionInstrumentation
```

Use a disposable/read-only emulator instance. The runner stops the original in-process runtime, temporarily points the activity at an isolated runtime/home under a random cache directory, uses no model credentials, restores the previous runtime and start preference, and removes its test files. It checks cancellation during actual APK extraction; packaged Node startup and approval-module activation; authenticated HTTP; token redaction; renderer exit/recovery; trusted/main-frame bridge admission; iframe and foreign-origin rejection; file containment; Android symbolic-link rejection; and real stop/restart. It does not send a live model request or modify session history.

The separate platform suite supports API 26+, without `WindowInspector` or model credentials:

```powershell
scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5584 -DeviceSuite PlatformCompatibility
```

It uses the same isolated-runtime and cleanup model, starts the real packaged Node/approval overlay, then checks three native screens, synthetic insets, real IME show/hide and geometry, DOM/IndexedDB storage, single/multiple/cancelled results, unavailable pickers, a real DOM file-input touch and content-URI read, and an actual system picker across activity recreation and a late result. A static test-only document is served at the trusted loopback origin; all other requests retain the production WebView origin restrictions. Framework result interception supplies deterministic URI/cancel/missing-picker cases. This does not claim that a person selected a document in a third-party picker. On API 26 synthetic checks cover bars; typed synthetic IME/cutout checks run on API 30+, while real IME checks run on every tested API.

The stock API 26 image's Chrome/WebView 69 cannot parse the bundled Harness JavaScript (`globalThis` and modern syntax). Full production-page and approval acceptance subsequently passed on the same x86_64 image with **Chrome/WebView 119.0.6045.194**, versionCode 604519427, installed as the existing `com.android.chrome` provider. Its APK v2/v3 signatures match the image's original Google Chrome signer. The tested 138.0.7204.179 and 132.0.6834.163 x86_64 native libraries use Android RELR tags and crash while loading on this API 26 image, before the Harness can start; 119 uses the older packed relocation format and runs successfully. This is a tested legacy environment, not a statement that 119 is current or that 138 fails on other ABIs. Chromium has ended Android 8/9 updates ([official announcement](https://groups.google.com/a/chromium.org/g/chromium-dev/c/vEZz0721rUY/m/pUIgqXxNBQAJ)); ARM64 Android 8 remains unverified.

The platform runner now adds a fifteenth check: real native-menu Stop while the IME is visible. The live runner additionally verifies full page boot, Settings touch open/close, workspace/composer/reload behavior and persisted conversation restoration. Send and approval buttons use injected native touch after viewport hit testing. Stop touches the real native overflow menu and its Stop row, waiting for popup geometry to settle before the second touch. Screenshots require a foreground app/popup accessibility root, with a bounded wait for the initial accessibility connection. No internal menu-handler call substitutes for that touch path.

The narrow-screen Settings fix is in `patches/android-mobile-settings.patch`. Below 600 CSS pixels it stacks the section rail above the full-width options pane, preserving the larger-screen rules. Actual Android 8 UI assertions check the options width and absence of horizontal overflow; screenshots verify the controls are readable. The compiled settings client bundle differs only in its Settings CSS; the rest of the packaged runtime and approval code is unchanged.

The separate opt-in live test sends billable requests to the official DeepSeek API, with a 1024 output-token cap per request and ancillary title/telemetry plugins disabled. It requires a disposable device with an already configured `DEEPSEEK_API_KEY` ref, Python with PyYAML, network access from the device, and an IANA device timezone such as `Asia/Shanghai` (or `UTC`). Current WebViews can report fixed-offset zones such as `+00:00` for a GMT-configured emulator; the upstream prompt API rejects those. The runner checks this precondition explicitly instead of silently waiting for a model request that was never sent.

```powershell
scripts/verify-live-approval.ps1 -DeviceSerial emulator-5580 -Python python -Offline
```

It stages only that key via stdin into private device cache, consumes and deletes the input, and uses an isolated Harness home/project. `-CredentialDeviceSerial <serial>` can read the configured ref from another explicitly selected disposable device, without copying its normal home or settings. The isolated credential is written only after a WebView has been constructed. `-OutputDirectory <path>` preserves separately named evidence runs; existing validation records need not be overwritten. Known screenshot cache names are cleared before each run, then the exported PNGs are deleted from device cache. The exporter validates a fixed label allowlist and PNG header and uses binary output without shell redirection. It sends three real prompts through the production WebView and tests **Allow once**, **Reject**, and native **Stop harness** while approval is pending. Assertions compare the pre-decision file absence, actual file effects, approval event pairs for the answered requests, and the absence of any additional grant after process shutdown. The test plugin only creates a workspace and projects event metadata; it does not replace the model, tool, approval service, or product UI. Successful results under `dist/live-approval` omit credentials, prompts, command arguments, and model output; failure diagnostics can include the page text containing the synthetic test prompt. The runner removes its temporary home and restores the prior start preference. An interrupted instrumentation process may require removal of its isolated `cache/live-approval-*` directory; do not remove the application's normal `files/dsh` home.

For the Android 8 setup, use the prepared legacy-provider emulator and an IANA timezone, then run:

```powershell
scripts/verify-live-approval.ps1 -Offline -DeviceSerial emulator-5584 -CredentialDeviceSerial emulator-5580 -OutputDirectory dist/android8-webview/live-run -Python python
```

The source device must already contain the configured DeepSeek ref; the target does not need a persistent credential. This remains an opt-in billable test. The report includes app/API/provider versions, four UI check groups, three interaction outcomes and screenshot names. Successful evidence contains only the synthetic test UI and an event projection; the raw credential is never printed or saved on the host.

The approval card replaces the visible composer, so **Stop generating** is not a reachable cancellation action in that state. The live regression uses the reachable native **Stop harness** action, which terminates the whole process; a pending `approval/asked` may remain in an unfinished crash tail, without a matching `approval/decided`. Treat that as interrupted work, never as approval. The tested pending command had no file effect after process shutdown. Signal cancellation with a paired `cancelled` outcome is covered by the host ToolRuntime/approval composition tests; it is not claimed as a live UI result. Runtime process stop/restart is also covered by the keyless runner.

The current build uses Gradle 9.8.1 (distribution SHA-256 pinned), **AGP 9.5.0-alpha09 (preview)**, WebKit 1.17.1, Activity 1.13.0, Core 1.19.1, and compile/target API 37. API 26 remains the installation floor. Use an SDK installation containing API 37; Android 17 runtime acceptance is checked separately from compilation. The preview AGP contains the upstream `Configuration.setVisible` removal ([issue 565740572](https://developer.android.com/build/releases/agp-9-5-0-release-notes?hl=en)). It is pinned rather than selected dynamically; the latest stable 9.4.1 still contains those calls.

AGP also initializes `KotlinBaseApiPlugin` even for a Java-only module. Its transitive KGP 2.2.10 has a remaining deprecated call, so the root build pins the buildscript dependency to KGP 2.4.21. No Kotlin Android plugin is applied to the app. The app uses the supported module-level `enableKotlin = false` setting, which removes unnecessary Kotlin compilation and automatic standard-library injection. The deprecated global `android.builtInKotlin=false` workaround is absent. If Kotlin sources are added later, revisit that module setting and test the resulting compiler tasks.

Both verification scripts use `--warning-mode=fail` and also reject AGP's plain `WARNING:` output, which is not always reported through Gradle's deprecation mechanism. Negative controls verify that both checks reject real deprecated calls/options. No warning suppression, internal Gradle hook, patched plugin JAR, or lint baseline is used. All production and test Java compilation tasks additionally enable `-Xlint:deprecation` and `-Werror`. The eight platform deprecation sites have been removed; a temporary deprecated-call negative control confirms that the compiler rejects regressions. No deprecation suppression is used.

The dated result is appended to [verification.md](verification.md). ARM64 hardware (including Android 8), API 27–29 runtime acceptance, prolonged background operation, and long conversations/compaction still require separate validation. Android 8 UI acceptance is limited to the exact x86_64/provider combination recorded above.

On 2026-10-16 the user authorized emulator verification because a physical device
could not be connected remotely. A fresh API 26 x86_64 run passed the 15 platform,
four production UI and three real-model approval groups. Screenshot review caught
a test-evidence race: WebView's visual callback can arrive before the changed
surface is presented, and a native popup may still be fading out. The live runner
now additionally waits for 350 ms of accessibility idle, with a 5-second bound,
before taking each display screenshot. The final rerun's Settings and stopped
screens were visually checked. This changes the test APK only; app 0.1.4 is
byte-identical to section 17's artifact.

ARM64 emulator attempts remain diagnostic evidence. Emulator 37.2.12 rejects an
ARM64 guest on this x86_64 host. Archived 31.3.14 reaches an audio-device failure;
a separate host-metadata workaround lets the unchanged Android 8/API 26 guest
start ADB, but its audio HAL repeatedly crashes and system boot does not complete.
Archived 28.0.25 fails to mount the system partition. No application or model
acceptance ran on these ARM64 guests. The ARM64 Chrome APK's verified signature
and the application's AArch64 ELF inventory are host checks only. Logs, exact
versions and remaining limits are recorded in verification section 18; no guest
framework/library or application security policy was patched to bypass startup.
