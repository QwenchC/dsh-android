# Verification record

What was actually run, on what, and what it reported. Host: Windows 11, Ryzen 9
9950X3D, WSL2 Ubuntu 24.04. Device: the `dsh-android` Android Virtual Device
(Pixel 7, Android 16 / API 36, x86_64, Google APIs image, emulator 36.5.10).

## 1. Every runtime primitive, on the device

Run in the application's own environment, against the extracted runtime:

```
node    : v24.18.0 on android-x64
ripgrep : ripgrep 15.2.0
bash    : bash 5.3.9(1)-release
flock   : acquired the Session write lock
publish : published (no-clobber)
publish : second attempt refused with EEXIST
pty     : interactive shell answered on the PTY
```

| Line | What it proves |
|---|---|
| `node v24.18.0 on android-x64` | The Node executable runs from the APK's native library directory, resolves its shared libraries, and reports the Android platform |
| `ripgrep 15.2.0` | The bundled ripgrep executes, so `glob` and `grep` have a search binary |
| `bash 5.3.9` | The bundled bash executes, so the shell executor and the terminal have a real Bash rather than the platform's mksh |
| `flock : acquired` | The Node-API addon built against bionic loads and takes the Session write lock |
| `publish : published (no-clobber)` | The port's publication primitive creates a target Android's policy would not let `link(2)` create |
| `publish : … refused with EEXIST` | It still refuses to replace an existing file, which is the guarantee every caller depends on |
| `pty : answered` | node-pty, built for Android by this port, allocates a working pseudo-terminal and an interactive shell answers on it |

## 2. The Harness boots and serves

```
[runtime] installed 14620 entries
[runtime] …/lib/x86_64/libnode.so --expose-internals …/runtime/lib/bin.js web --host 127.0.0.1 --port 35237 --no-open
dsh web: http://127.0.0.1:35237/?token=PkZHO4hykvd6_F1HYxC3iJj0SSHV4bfuZO4KDf8twaA
```

and the process list while it runs:

```
u0_a216  6093   481  … com.dsh.android
u0_a216  6193  6093  … libnode.so
```

DSH's browser application renders inside the application's WebView from that
loopback origin — sidebar, workspace picker, agent-mode row, and composer:

![The Harness UI on the emulator](images/harness-on-emulator.png)

## 3. A real agent turn, on the device

`dsh --profile headless` was given a task that requires the model, a shell, a
file write, and a search:

> Create a file named notes.txt whose only line is the output of the pwd command.
> Then use your search tool to confirm notes.txt contains that path.

The device's transcript, abbreviated:

```
dsh: reasoning: The user wants me to create notes.txt with the pwd output. Then
    "use your search tool" to confirm. My search tool is grep. Let me do it.
dsh: reasoning: Now create notes.txt with write tool. The only line is the path.
    Then grep to confirm.
notes.txt contains the single line
/data/user/0/com.dsh.android/files/dsh/workspace, confirmed with grep.
```

A second run, after the terminal was added, used a pipeline and the search tool
again: *"Ran `echo terminal | tee terminal.txt` … and grep confirmed
`terminal.txt` line 1 contains "terminal"."*

What it left behind, read back over `adb`:

```
files/dsh/workspace/notes.txt                    49 bytes
  /data/user/0/com.dsh.android/files/dsh/workspace

files/dsh/home/.dsh/sessions/--…--/session-1efad7b8-…/session.v3.jsonl.zstd
files/dsh/home/.dsh/sessions/--…--/session-5b8d83e0-…/session.v3.jsonl.zstd
```

One run exercises every layer at once: the model adapter streamed over the
device's own network, the agent loop drove three tool calls, the shell executor
spawned the bundled bash, the search tool spawned the bundled ripgrep, the file
tools wrote through the publication path, and the session log was published and
compressed to disk.

## 4. What each failure taught the port

In the order the problems appeared. Each is a property of the platform.

| Symptom | Cause | Resolution |
|---|---|---|
| The linker crashed inside `split_path` while preloading a library | Rewriting an ELF's dynamic string table to rename libraries (so the platform would extract them from `lib/`) produces an object Android's linker rejects | The executable ships as `libnode.so`; everything else keeps the file name its SONAME requires and is found through `LD_LIBRARY_PATH` |
| `Cannot find package '@deepseek-ai/cosmokit'` | pnpm keeps a `link:` workspace dependency as a symbolic link, and an APK cannot carry one | The vendored Cordis packages are copied in as real directories |
| `Cannot find module 'node-addon-require-builtin/…/require_builtin.node'` | That family publishes no Android binding, and its entry builds the binding during import | The loader reaches Node internals through `--expose-internals`; the entry is replaced with one that reports unavailability |
| 29 plugins "failed to import" | `pnpm deploy --prod` installs the deployed package's dependency closure, and the Harness declares its service definitions as peer dependencies | `scripts/complete-deployment.mjs` completes the peer closure from the workspace |
| `flock is not supported on android-x64` | The binding rejected every platform it had no payload for | `android-arm64` and `android-x64` payloads are built with the NDK and resolved like the other platforms' |
| `/…/nativelib/libz.so.1 is for EM_AARCH64 instead of EM_X86_64` | One archive serves every ABI, so an ABI's libraries cannot sit at its root | Each ABI's libraries live under `nativelib/<abi>/`, and the application points `LD_LIBRARY_PATH` at its own |
| `permission: the mounted bash executor does not confine` | The permission presets bundle a sandbox mode, so the plugin refuses to compose over an executor that does not confine — the only kind Android has | The row is gated off on Android; the file-effect modes stay selectable per session |
| `EACCES: permission denied, link …` on the first append and on the first guarded write | Android denies `link(2)` inside application data — the shell user cannot hard-link there either — and the Harness published session logs, attachments, and guarded writes by hard link | `publishNoClobber` in `@deepseek-ai/dsh-atomic-write` falls back to an exclusive create plus copy, keeping the no-clobber guarantee and the `EEXIST` answer |
| `grep` failed with "ripgrep launch failed" under `--profile headless` | Only the agent presets restated the deployment's ripgrep path, and the headless profile mounts the base row | The base row reads `DSH_RIPGREP_PATH`, so every profile gets it |
| The status and navigation bars covered the interface | Android 15 draws every application edge to edge; the activity placed the WebView across the whole window | The activity applies the system-bar and keyboard insets to its root view |
| `run_code` failed: "libnode.so needs libz.so.1, which is missing" | The `run_code` worker deliberately drops the ambient environment and keeps only the startup variables the child needs — and `LD_LIBRARY_PATH` is how an Android Node executable finds its libraries, so the child could not link | `LD_LIBRARY_PATH` and `TMPDIR` join that startup set, the same class as the `PATH` already there |
| The WebView showed "dsh web authentication required" | The application treated an answered request as readiness, and the server answers an unauthenticated request with 401 — so the port probe could win the race against the printed URL and load an origin with no token | Readiness is the printed URL itself, which is the only origin that activation authorizes |

## 5. The `run_code` worker, on the device

The PTC worker is a second Node process the Harness starts with a deliberately
emptied environment. Given only the startup variables the port now preserves, it
links and runs:

```
worker env: PATH,LD_LIBRARY_PATH,TMPDIR,TMP,TEMP
status : 0
stdout : worker linked: v24.18.0 on android
```

and a real turn that must use it — `DSH_TOOLS_MODE=ptc`, asked to print the
working directory and the value of six times seven — answered from inside the
worker:

```
dsh: reasoning: The user wants me to use run_code to print the current working
    directory and the value of six times seven. Let me do that.
(node:4076) ExperimentalWarning: stripTypeScriptTypes is an experimental feature …
The current working directory is /data/user/0/com.dsh.android/files/dsh/workspace,
and six times seven is 42.
```

## 6. With the screen off

The application holds a partial wake lock while the Harness runs, and the
platform confirms it:

```
PARTIAL_WAKE_LOCK  'dsh:harness' ACQ=-6m56s969ms LONG (uid=10216 pid=3014)
```

The Node process is alive after two minutes of a dark screen, and its processor
time advances while it is dark — 412 to 416 ticks over 90 seconds — so it is
being scheduled rather than frozen.

More to the point, the server still answers. Requested from the device itself,
with the screen off, twice, 105 seconds apart:

```
http    : 303 /
bytes   : 0
authenticated: true
```

The 303 is the token exchange the Web UI performs on load: the server accepted
the run's token and redirected, which is a full round trip through the runtime
while the device was dark.

## 7. Reaching the generated files

The workspace browser lists what the agent wrote — the two files from the turns
above — and the shortcut sits in the page's top-right corner:

![The workspace browser](images/workspace-files.png)

Another application can read a project file through this application's provider,
which serves the workspace and refuses everything beside it:

```
content://com.dsh.android.files/notes.txt
  -> /data/user/0/com.dsh.android/files/dsh/workspace          (served)

content://com.dsh.android.files/../home/.dsh/.credentials.yaml
  -> FileNotFoundException: path outside the served subtree    (refused)

content://com.dsh.android.files/../runtime/lib/bin.js
  -> FileNotFoundException: path outside the served subtree    (refused)
```

The emulator image installs no application that handles `text/plain`
(`cmd package resolve-activity -a android.intent.action.VIEW -t text/plain`
answers "No activity found"), so opening a file end to end could not be observed
there; the browser falls back to sharing, which is what that device can do.

## 8. Opening the project from the application

The application's own row sits above the page, and the file dialog opens from a
file reference in a conversation:

![The application bar and the file dialog](images/file-open-dialog.png)

The bridge between page and application was checked through the WebView's own
debug socket rather than by eye:

```
bridge installed   : true
DSHAndroid present : object
renderer saw the click: false      (the Harness's own handler did not receive it)
pending recorded: true             (kept for "Preview in DSH")
```

The click test used the markup the Harness renders for a file reference — a
`button` carrying the `fileLink` class with the path in `title` — which the
production stylesheet confirms survives bundling as `_fileLink_uddqf_59`.

`Project folder` reaches the device's file manager, inside the folder:

![The project folder in the device's file manager](images/project-folder.png)

```
I DshMain : opening project folder /storage/emulated/0/DSH as
            vnd.android.document/directory resolvable=true
top: com.google.android.documentsui/com.android.documentsui.files.FilesActivity
breadcrumb: sdk_gphone64_x86_64 > DSH          "Files in DSH"
```

The same action reported `resolvable=false` until the manifest declared
`<queries>`: Android 11 hides packages an application has not asked about, so
the intent that the platform accepts from a shell resolved to nothing from the
application. No amount of intent tuning substitutes for that declaration.

## 9. Failure reporting

A fatal failure reaches the application's own log screen, checked by raising one
on purpose from the debug menu:

```
=== crash 2026-10-14 02:35:58 in main ===
java.lang.IllegalStateException: simulated failure, to check the crash recorder
	at com.dsh.android.MainActivity.onOptionsItemSelected(MainActivity.java:383)
	at android.widget.PopupMenu$1.onMenuItemSelected(PopupMenu.java:109)
	...
```

The same trace appears at the bottom of the **Log** screen, which is what makes
a failure on a device the developer does not hold reportable at all.

## 10. Moving a project to shared storage

Accepting the offer, or the *Move project to shared storage* action, copies the
private workspace into `/sdcard/DSH` without replacing anything:

```
before    files/dsh/workspace:        after    /sdcard/DSH:
          demo  notes.txt                      demo  notes.txt
          demo/a.txt  demo/sub/                demo/a.txt  demo/sub/
          demo/sub/b.txt                       demo/sub/b.txt
```

The copy is what makes the offer meaningful: changing the setting alone left the
new folder empty and the user's files in a directory no file manager reaches.

## 11. Where a new project actually goes

A project created through the Harness's directory picker does not land in the
application's workspace: the picker offers the Harness process home as *Home*,
so projects made there live under `files/dsh/home`. Granting shared storage now
moves the process home itself, read back from the running runtime:

```
HOME              = /storage/emulated/0/DSH
DSH_HOME          = /data/user/0/com.dsh.android/files/dsh/home/.dsh
TMPDIR            = /data/user/0/com.dsh.android/files/dsh/tmp
LD_LIBRARY_PATH   = /data/user/0/com.dsh.android/files/dsh/runtime/nativelib/x86_64
```

and the move action covers both places, keeping each project's own name:

```
/sdcard/DSH:            app-project  demo  myproject  notes.txt
/sdcard/DSH/app-project: index.ts                       (from the workspace)
/sdcard/DSH/myproject:   readme.md  src/main.js         (from the harness home)
```

The Harness's state directory is excluded by construction: it is a sibling of
those projects named `.dsh`, and it stays in the application's private storage.

## 12. Not verified

- **A physical Android device.** Only the x86_64 emulator was run. The arm64
  payload is built from the same sources by the same scripts, and the archive
  carries both ABIs' libraries and prebuilt bindings, but no phone was attached
  to this machine.
- **Image attachments and Office conversion**, which the Android surface does
  not mount; see the limitations in [../README.md](../README.md).
- **Multi-turn conversations and compaction** on the device. Two complete turns
  were run end to end; the surfaces those features use are the ones they
  exercised.


## 13. Reliability and permission regressions — 2026-10-15

Version **0.1.1** (versionCode **3**) was checked with `scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5580`. The emulator ran Android 16/API 36 on x86_64 with `-read-only -no-snapshot -no-window`, preserving the original virtual-device disk. Device tests booted the real packaged Node runtime with an isolated temporary Harness home and did not use model credentials or alter session history.

| Check | Result |
|---|---|
| Java lifecycle, copying, and origin tests | 20 passed; 1 symbolic-link case skipped because Windows denies link creation, then covered by the Android runner |
| Real Harness Loader/ToolRuntime/approval composition | 10 passed, including rejected/unavailable answers, cancellation, stored never policy, autonomous opt-in, and outer/nested PTC admission |
| Device regressions | 10 passed: real extraction cancellation; packaged Node + approval overlay + authenticated HTTP + redacted logs; trusted bridge; main-frame/iframe separation; canonical file containment; foreign-origin rejection; Android symlink refusal; provider metadata/escaped filenames/traversal refusal; cancelled-update retention; stop/restart |
| Android lint | 0 errors, 15 warnings; no baseline or blanket suppression added |
| Debug + Release assembly | Passed; Release remains unsigned |
| Debug signature | APK Signature Scheme v2 verified |
| APK contents | arm64-v8a and x86_64 Node/bash/ripgrep; exact current approval assets; network security configuration |
| Diff whitespace | Passed |

The remaining lint warnings concern dependency/toolchain update notices, the intentionally continuous wake lock, the existing battery-exemption request, required WebView JavaScript, obsolete SDK/resource guards, and diagnostic text formatting. The three original compatibility errors are resolved. See [android-hardening.md](android-hardening.md) for current behavior and test commands.

The device tests also caught an invalid ordering of the new `--patch` launch argument during development; the final launcher explicitly supplies `--profile web --patch <file>` before Web-specific flags, and the final device startup/restart checks passed with that invocation.

Local debug artifact: `dist/DSH-Android-0.1.1-debug.apk` (115458748 bytes).

```text
SHA256 d05e70b3becbdddecb3ef6e0eb0a344f92136568ba7a8d82807ee5eadcb9c312
```

`dist/revision-tests/final-verification.txt` retains the combined run output, and `dist/revision-tests/verification-summary.json` records the artifact identity and result counts. The preceding emulator evidence remains historical. This run does not establish ARM64 hardware acceptance, Android 8–10 runtime behavior, a live model turn through the new approval UI, prolonged idle/background behavior, or multi-turn compaction.

## 14. Warning cleanup and live approval interactions — 2026-10-15

Version **0.1.2** (versionCode **4**) uses Gradle **9.8.1** with a pinned distribution SHA-256, AGP **9.4.1**, AndroidX WebKit **1.17.1**, Activity **1.13.0**, and compile/target SDK **37**. The installation floor remains API 26. The original 15 lint findings are addressed as follows:

| Original findings | Disposition |
|---|---|
| Six dependency/toolchain notices | Updated Gradle/AGP/WebKit/Activity; removed unused AppCompat/Core catalog entries |
| BatteryLife | Removed direct battery-exemption request and its permission; open standard system settings with instructions |
| WakeLockTimeout | Five-minute bounded CPU lease, renewed each minute; stop invalidates stale renewals and releases ownership; renewal failure stops the run |
| Four obsolete SDK guards | Removed branches below the application's API 26 floor |
| Obsolete resource qualifier | Moved the identical adaptive icon into `mipmap-anydpi` |
| Diagnostic text concatenation | Append crash text separately |
| SetJavaScriptEnabled | One documented method-scoped exemption for the required Harness JavaScript; origin/resource/bridge restrictions remain enforced |

No lint baseline or global disable was added. Updated lint also required renderer-exit handling and an immediately checked WebKit feature guard. Renderer recovery now destroys the dead WebView and recreates the UI without replacing the live Harness process. The verification script fails if lint reports any issue.

**Correction to section 13:** passing an overlay to the CLI and seeing its HTTP server start did not prove that the approval module activated. Live interaction testing exposed an unsupported `!!js` expression in the loader entry's `name`, which left the module unimported. Version 0.1.2 materializes a literal, encoded file URI and requires the module's activation acknowledgement before reporting READY. The host test now reads the actual patch template, unit tests check materialization, and device regressions require successful module activation. Section 13 must not be used as evidence that 0.1.1's Android approval gate was active.

| Check | Final result |
|---|---|
| Java unit tests | **25 passed, 1 skipped**, 0 failures/errors; the Windows-denied symlink case passes on Android |
| Real Harness Loader/ToolRuntime/approval composition | **10 passed**, including signal cancellation and rejection of late grants |
| Android 16/API 36, x86_64 | **11 device regressions passed**, WebView 133.0.6943.137 |
| Android 17/API 37.0, x86_64 | **11 device regressions passed**, WebView 145.0.7632.218 |
| Live DeepSeek WebView interactions | **3 passed**, official provider / `deepseek-flash`, no model mock |
| Android lint | **0 errors, 0 warnings** after the one reviewed JavaScript exemption |
| Debug and Release assembly | Passed; Release remains unsigned |
| Debug signature and archive | v2 signature verified; both native ABIs and exact current approval assets present |
| Credential handling | Current configured key absent from all 47 inspected text evidence files; input and temporary test homes removed |
| Whitespace | `git diff --check` passed |

Both device runs used the same debug APK hash below. API 36 ran a read-only, no-snapshot instance of the existing AVD. API 37 ran a separate new AVD from Google's `x86_64-37.0_r06.zip`; its published SHA-1 `629e507fd5b737c2c836b12b52c81cd0e3b12399` was verified before extraction. Existing virtual-device disks and session history were not used as test homes.

The live runner booted a temporary real Harness, selected its workspace through the production page, sent three prompts through its composer, and activated the actual approval buttons. A fixture plugin only created the test workspace and wrote a credential-free event projection. It did not replace the model, tool execution, or approval service. The official route is also present in the recorded `request/context` and `request/header` events. Ancillary title/telemetry plugins were disabled and the output-token cap was 1024 per request.

| Real interaction | Before decision | After decision / stop | Recorded authority |
|---|---|---|---|
| Allow once | Marker absent; approval visible | Marker contains exactly `allow` | `approval/asked` → `allowed-once` |
| Reject | Marker absent; approval visible | Marker absent | `approval/asked` → `rejected` |
| Native Stop harness while approval waits | Marker absent; approval visible | Process joined; origin/port cleared; marker absent | Pending request received no grant; unfinished process tail |

The native Stop result is **not** represented as a durable `cancelled` approval: shutdown can leave an unmatched `approval/asked` in the unfinished tail. The approval card replaces the visible composer, so its hidden Stop-generating control is not counted as a tested user action. Signal cancellation with a paired outcome remains covered by the real ToolRuntime host tests. Physical touch geometry was not manually accepted; WebView tests activate actual DOM controls, and the native stop case invokes the product menu handler.

The disposable API 36 instance was configured to `Asia/Shanghai` before the final live run. Its initial GMT setting made WebView report `+00:00`, which the upstream prompt API rejects before a turn starts. The live runner now checks for `UTC` or an IANA zone explicitly. Fixed-offset-only client timezone handling is still an upstream limitation, not a passed model result.

Repeatable commands:

```powershell
scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5580
scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5582
scripts/verify-live-approval.ps1 -Offline -DeviceSerial emulator-5580 -Python python
```

The live command requires Python/PyYAML, a configured device DeepSeek ref, and network access; it makes billable model requests. The normal verification command is keyless.

Evidence: `dist/warning-audit/verification-api36-final.txt`, `verification-api37.txt`, `policy-final.txt`, `lint-results-debug.xml`, `signature.txt`, `live-final.txt`, `live-model-approval.json`, and `verification-summary.json`. `dist/live-approval/result.json` is the reusable live script's latest report. Early failed diagnostic runs remain separate development evidence. AGP still emits an upstream Gradle `Configuration.setVisible` deprecation notice, identified as `com.android.internal.application` in `gradle-problems.json`; it is separate from the zero-issue lint result and has not been hidden.

Local debug artifact: `dist/DSH-Android-0.1.2-debug.apk` (**115830087 bytes**).

```text
SHA256 ad188e5f14483c26ad5f45f138d3ff9b859294876e67c4a8b819e4f5398cd993
```

ARM64 physical hardware, Android 8–10 runtime acceptance, long idle/background operation and battery consumption, long conversations/compaction, and manual touch/accessibility acceptance remain unverified. These checks do not establish a process confinement boundary for approved commands.

## 15. AGP and Kotlin build deprecations — 2026-10-15

The section 14 upstream build-tool notice is now resolved. The final build uses **AGP 9.5.0-alpha09 (preview)**, a pinned **KGP 2.4.21 buildscript dependency**, and the existing Gradle 9.8.1/JDK 21/SDK 37 setup. Application version remains 0.1.2 / versionCode 4; this revision changes build configuration and verification, with a separately named rebuilt APK.

### Origin and fix

`help --warning-mode all --stacktrace` on the previous AGP 9.4.1 setup reported **60 calls** to `Configuration.setVisible`: **51** originated in AGP's JDK-image, source-set, variant-dependency, lint, desugaring, and test configurations; **9** originated in its Kotlin build dependency. These were plugin calls, not application Gradle scripts.

The official [AGP 9.5 release notes](https://developer.android.com/build/releases/agp-9-5-0-release-notes?hl=en) identify the fix for issue 565740572 in alpha09. Google's Maven metadata still lists 9.4.1 as the latest stable release. Inspection of the published source archives found 28 setter/assignment sites in 9.4.1 and none in 9.5.0-alpha09. The project therefore pins the preview version explicitly; this is a tested preview toolchain, not a claim that AGP 9.5 is stable.

The pure Java app now uses the supported module-level `android { enableKotlin = false }` setting ([Android documentation](https://developer.android.com/build/migrate-to-built-in-kotlin#option-to-selectively-disable-built-in-kotlin)). The global `android.builtInKotlin=false` workaround was rejected because AGP emits a separate deprecated-option warning for it. The module setting alone still leaves one deprecated call during `KotlinBaseApiPlugin` initialization, before AGP consults the module DSL. Pinning the root **buildscript** classpath to KGP 2.4.21 removes that call. `buildEnvironment` confirms AGP's requested KGP 2.2.10 resolves to 2.4.21. No Kotlin Android plugin is applied to the module, and no plugin binary/cache was patched.

Both verification scripts now pass `--warning-mode=fail` and reject plain AGP `WARNING:` output. These are separate checks: Gradle's failure mode alone did not reject the deprecated AGP option. No warning suppression was added.

### Final validation

| Check | Result |
|---|---|
| Final `help --warning-mode=fail` | Passed; **0 Gradle deprecations and 0 AGP warnings** |
| Full verification with final AGP/KGP/module DSL | Passed with the strict warning checks |
| Java unit tests | **25 passed, 1 skipped**, 0 failures/errors; skipped symlink case covered on Android |
| Harness permission composition | **10 passed** |
| Android lint | **0 errors, 0 warnings**; existing reviewed JavaScript exemption retained |
| Debug / unsigned Release / test APK assembly | Passed |
| Android 16/API 36 device regressions | **11 passed** |
| Android 17/API 37 device regressions | **11 passed** |
| Gradle negative control | Injected `Configuration.setVisible(false)` in a temporary init script; strict Gradle command failed with exit code 1 as expected |
| AGP negative control | Injected the deprecated global Kotlin option through a temporary process environment; build completed, then the verification script rejected its warning as expected |
| Package/signature | Debug v2 signature verified; both ABIs present; all **10 asset/native-library entries** byte-identical to section 14's APK |
| Diff whitespace | Passed |

The final build reproduced the exact APK hash installed by both device runs, including after replacing the rejected global Kotlin setting and pinning KGP. Device checks cover real runtime extraction/cancellation, approval-module activation, authenticated HTTP, renderer recovery with the same Harness process, bridge/frame/origin restrictions, file containment/provider handling, symlink refusal, and stop/restart. Both emulator instances were read-only and did not persist virtual-device disk changes.

This turn did not send additional billable model requests. Section 14's live-model approval evidence retains its original APK identity; the bundled Harness archive and approval assets are byte-identical in the rebuilt APK. The existing Java compiler notes about deprecated Android platform APIs remain visible and are separate from the cleared Gradle/AGP diagnostics.

Evidence under `dist/agp-deprecation/`: `baseline-9.4.1.txt`, `source-calls-9.4.1.json`, `source-calls-9.5.0-alpha09.json`, `module-kotlin-help.txt` (the remaining old-KGP call), `fixed-kgp-help.txt`, `build-environment.txt`, `final-help.txt`, `final-verification.txt`, `negative-probe-final.txt`, `negative-agp-option.txt`, `device-emulator-5580.txt`, `device-emulator-5582.txt`, `archive-comparison.json`, `signature.txt`, and `verification-summary.json`. Earlier candidate and failed diagnostic logs are development evidence, not final acceptance results.

Rebuilt debug artifact: `dist/DSH-Android-0.1.2-agp9.5-alpha09-debug.apk` (**115766498 bytes**). The preceding 0.1.2 artifact and verification files are retained.

```text
SHA256 40056eb4acddbb9b1cd50a98acc262e911424d0738dceebb80892604eacf8873
```

Remaining limits: AGP is a preview release; revalidate when replacing it with a stable fixed release. ARM64 physical hardware, API 26–29 runtime acceptance, prolonged background/battery behavior, compaction, and manual touch/accessibility acceptance remain outside this run.


## 16. Android platform API deprecations and compatibility — 2026-10-15

Version **0.1.3 / versionCode 5** removes the eight Java deprecation sites left visible in section 15. Compile/target SDK 37 and minSdk 26 are unchanged. AndroidX Core **1.19.1** is now an explicit dependency for the insets APIs used directly by the carrier. The pinned preview AGP/KGP setup from section 15 remains in use.

| Baseline compiler sites | Change |
|---|---|
| Four `WindowInsets.getSystemWindowInset*` getters | AndroidX typed insets; reserve the union of bars, cutout and IME with original padding retained; explicit edge-to-edge layout on all three screens, including Logs |
| `WebSettings.setDatabaseEnabled` | Remove obsolete WebSQL enabling; retain DOM storage and verify localStorage/sessionStorage/IndexedDB |
| `startActivityForResult`, `onActivityResult` override and super call | Registered Activity Result launcher, exactly-once callback completion, saved pending-picker ownership across recreation |

All Java compile tasks, including production Debug/Release and tests, now enable **`-Xlint:deprecation -Werror`**. Baseline logging identifies all eight sites. A temporary init script added a real deprecated getter to the app's JavaCompile task; it failed with exit code **1** due to `-Werror`. The subsequent positive builds passed without that injection, and the probe class is absent from the final APK. No deprecation suppression or lint baseline was added. The previous strict Gradle/AGP warning checks remain active.

The migration tests also caught a real upload issue: `WebChromeClient.FileChooserParams.parseResult` discarded a multi-selection returned only as `ClipData`. The final code uses AndroidX's multiple-content result parser, accepting both data and clip URIs and removing duplicates. The WebView's original picker intent remains intact inside the system chooser. An outstanding picker blocks overlapping requests; destroying a WebView cancels its callback while preserving the outstanding result's ownership until delivery, so a late result cannot complete a newer upload. Missing picker and non-runtime page cases cancel safely.

### Final validation

| Check | Result |
|---|---|
| Production/test Java compilation | **0 deprecation warnings**; compiler negative control rejected the deprecated call |
| Gradle/AGP diagnostics | **0 deprecations / 0 warnings**, strict verification passed |
| Java unit tests | **25 passed, 1 skipped**, 0 failures/errors; Windows symlink limitation remains covered on Android |
| Harness Loader/ToolRuntime/approval composition | **10 passed** |
| Android lint | **0 errors, 0 warnings** |
| Debug / unsigned Release / test APK assembly | Passed |
| Android 8.0 / API 26 x86_64 | **14 platform compatibility check groups passed** |
| Android 16 / API 36 x86_64 | **14 platform compatibility groups + 11 runtime/security regressions passed** |
| Android 17 / API 37 x86_64 | **14 platform compatibility groups + 11 runtime/security regressions passed** |
| Package/signature | Debug v2 signature verified; both ABIs; all **10 asset/native entries** byte-identical to section 15's APK; no probe class |
| Diff whitespace | Passed |

The 14 platform groups cover real packaged Node startup and policy-module activation; main/file/log system-bar insets; repeated insets without padding accumulation; real keyboard show/hide with screen geometry proving keyboard space is reserved once; DOM and IndexedDB storage; single and multiple URI results (including clip-only and duplicate data+clip); real WebView file-input touch through the production native chooser callback and FileReader reading a content URI; cancellation; unavailable picker and recovery; a real system chooser across overlapping requests, activity recreation and a late cancellation; and rejection from a non-runtime page. Synthetic typed IME/cutout cases run on API 30+ because the older platform's synthetic inset roundtrip cannot represent them; real IME checks passed on all three versions.

Deterministic URI/cancel/missing-picker cases intercept the framework's activity launch/result path; they do not claim human document selection in third-party software. The browser test uses a static ES5 document at a trusted loopback test URL; all other requests retain the original production client restrictions. The actual system chooser is opened and cancelled during the lifecycle test. Test assertion failures are transferred back to the instrumentation thread instead of throwing through Chromium JNI.

**Android 8 browser limitation:** the official API 26 image ships Chrome/WebView **69.0.3497.100**. Packaged Node, the approval overlay and native platform checks pass, but that provider cannot parse the bundled Harness UI (`globalThis` and modern JavaScript syntax errors are recorded). Full Harness UI acceptance on Android 8 with an updated compatible WebView remains pending. This is partial Android 8 native/runtime acceptance, not full product acceptance. API 27–29 and ARM64 physical devices were not run. The API 26 image download's official SHA-1 was checked (`0eb13cc91cf164b617eaf85e4030289cb18189de`); a new isolated AVD was used. The API 36/37 instances were read-only, with snapshot saving disabled.

Repeatable commands (use disposable attached devices):

```powershell
scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5584 -DeviceSuite PlatformCompatibility
scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5580 -DeviceSuite PlatformCompatibility
scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5582 -DeviceSuite PlatformCompatibility
scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5580
scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5582
```

The default runner remains the API 29+ runtime/security suite. `-DeviceSuite PlatformCompatibility` builds/selects the API 26+ runner. Both use isolated temporary runtime/home state and no model credentials. This turn sent **no additional billable model requests**; section 14's live-model evidence retains its original APK identity. Its runtime and approval assets are unchanged here. Prolonged background/battery behavior, long-conversation compaction, and manual accessibility acceptance remain pending.

Final evidence under `dist/platform-api/`: `baseline.txt`, `negative-compiler-probe.txt`, `verify-platform-api26.txt` (full verification and API 26 platform runner), `compat-final-emulator-5580.txt`, `compat-final-emulator-5582.txt`, `verify-regression-api36.txt`, `regression-api37.txt`, `webview26-version.txt`, `webview26-log.txt`, `lint-results-debug.xml`, `archive-comparison.json`, `signature.txt`, and `verification-summary.json`. Earlier failed diagnostic runs remain separate development evidence, including overly strict synthetic-inset/old-WebView intent assumptions. Their interrupted temporary files were removed without deleting the normal Harness home or historical logs.

Local debug artifact: `dist/DSH-Android-0.1.3-debug.apk` (**115988490 bytes**), with a SHA-256 sidecar. Earlier artifacts are retained.

```text
SHA256 2f03960e3da2a98c74c1ed825d1b2d11140ccc42999823c604d21a46fc7221ca
```


## 17. Android 8 WebView and production UI acceptance — 2026-10-15

Version **0.1.4 / versionCode 6** completes the production-page and real-model approval acceptance left pending in section 16 on **Android 8.0 / API 26 x86_64**. The same isolated Google APIs r16 AVD now uses **Chrome/WebView 119.0.6045.194**, selected through the platform's normal `com.android.chrome` WebView provider. The application's minSdk 26, target/compile SDK 37, permission policy and native executables are unchanged.

### Provider selection and provenance

Installing a newer version is insufficient without an actual WebView load test. Google Chrome **138.0.7204.179** (720417927) and **132.0.6834.163** (683416327) both installed and passed signature checks, but their x86_64 `libmonochrome.so` uses Android-private RELR tags (`0x6fffe000`, `0x6fffe001`, `0x6fffe003`). The API 26 linker logged unhandled dynamic entries and the app crashed during provider construction, before the Harness page or any model request. This diagnosis is consistent with [AOSP's linker documentation](https://android.googlesource.com/platform/bionic/+/master/android-changes-for-ndk-developers.md#relative-relocations-relr): Android-private RELR starts at API 28, while the earlier packed relocation format starts at API 23. The tested 119 provider uses the earlier packed relocation tags and successfully loads. This result concerns these x86_64 artifacts and this API 26 image; it does not establish a failure on ARM64 or on later Android releases. No browser binary or Android linker was patched.

The compatible provider APK was obtained from the [Google Chrome 119.0.6045.194 x86+x86_64 artifact mirror](https://www.apkmirror.com/apk/google-inc/chrome/chrome-119-0-6045-194-release/google-chrome-fast-secure-119-0-6045-194-9-android-apk-download/). Its Google **v2/v3 signatures and SourceStamp** were verified. The signer SHA-256 matches the original `/system/app/Chrome/Chrome.apk` signer, and Android accepted it as an ordinary signed update. Provider allowlists and signature validation were not changed. `aapt2` confirms minSdk 24, maxSdk 28 and versionCode **604519427**; `dumpsys webviewupdate` confirms the running provider is **119.0.6045.194**.

```text
Provider APK SHA256 e275ff76639dc8d876c733c489213dcf2ef003e1bf112b82a33c25e766cb9b52
Google signer SHA256 f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83
```

The verified APK is retained as `dist/android8-webview/chrome-119.0.6045.194-x86_64.apk`. To reproduce on the disposable API 26 AVD, verify the hash/signature first, install that pinned APK using `adb -s emulator-5584 install -r -d <apk>`, select it with `adb -s emulator-5584 shell cmd webviewupdate set-webview-implementation com.android.chrome`, and inspect the provider state. The downgrade flag only replaces the rejected newer development candidate on this owned test emulator. Set the test timezone to `Asia/Shanghai`; the live runner requires an IANA timezone. ADB root was used only to provision the old emulator's timezone and was disabled before application tests. The app and Node tests ran with the normal application UID.

This is a **frozen compatibility-test browser**, not a currently security-supported version or a recommended downgrade for personal devices. Chromium's [official Android 8/9 end-of-support announcement](https://groups.google.com/a/chromium.org/g/chromium-dev/c/vEZz0721rUY/m/pUIgqXxNBQAJ) identifies 138 as the final series for those operating systems. Neither a compatible engine nor this acceptance run restores platform/browser security support.

### Fixes and final checks

Phone-width screenshots exposed a genuine Settings layout issue: the fixed left rail squeezed the options into a narrow, horizontally scrolling column. `patches/android-mobile-settings.patch` now stacks the navigation rail above the options below **600 CSS pixels**, keeps section navigation horizontally scrollable and gives the controls the full dialog width. Wider-screen rules remain unchanged. The actual Android UI test asserts the options width and absence of horizontal overflow, then opens/closes Settings by injected touch. The final screenshot shows readable Language/Appearance controls instead of clipped labels.

Only that settings client module was rebuilt with the installed tsdown Client pass, synchronized into the prepared payload, then archived with the existing JDK jar/stamp process. Comparing the old/new compiled client bundles with the Settings CSS removed gives byte-identical output. Comparing runtime archives finds the same **14,614 entries**, with exactly **one changed file**: `node_modules/@deepseek-ai/dsh-client-ui-settings-general/lib/client.js`. All **eight native/approval asset entries** outside the runtime ZIP/stamp remain byte-identical to 0.1.3. The patch is retained in the outer repository because the upstream checkout is ignored; reverse-apply checking confirms the working source includes it. A fresh checkout should apply the mobile patch before the normal client build/package pipeline.

The live runner now drives Send, approval buttons and the native overflow menu with real injected touch. DOM controls must be within the viewport and pass hit testing. The native menu needs its popup animation to settle before the Stop row's coordinates are used; an earlier development run exposed this test timing problem. A separate keyless regression opens the real menu with the IME visible and stops the real runtime. Initial accessibility-root readiness is awaited, and foreign/system crash dialogs cannot be mistaken for app UI. Workspace startup accepts either the actual picker or the legitimate automatic selection of its one registered workspace. These are test fixes; no internal menu-handler invocation substitutes for the final native touch path.

| Final check | Result |
|---|---|
| API 26 native/platform suite with updated provider | **15 groups passed**, including the new native-menu Stop with IME visible |
| Full production Harness UI | **4 groups passed**: boot/workspace, Settings touch open/close, enabled composer and page reload, persisted conversation restoration |
| Real DeepSeek `Allow once` | Approval visible and marker absent before decision; one allowed-once event; expected file/content created |
| Real DeepSeek `Reject` | Approval visible and marker absent before decision; one rejected event; marker remains absent |
| Real DeepSeek pending approval + native `Stop harness` | Real menu touch; process stops and serving origin/port clear; marker absent; no additional grant |
| Final approval audit | **3 asked, 1 allowed-once, 1 rejected**; pending Stop does not claim a durable cancelled decision pair |
| Android 16 / API 36 regressions on 0.1.4 | **11 passed** |
| Android 17 / API 37 regressions on 0.1.4 | **11 passed** |
| Java unit tests / real Harness policy composition | **25 passed + 1 Windows symlink skip / 10 passed**; Android covers symbolic links |
| Java / Gradle / AGP diagnostics and Android lint | **0 deprecations, 0 AGP warnings, 0 lint issues** under strict verification |
| Debug / unsigned Release / test APK | Built; Debug v2 signature verified |
| UI evidence | **11 PNGs** exported and hash-recorded; Settings, approval states, real native menu and stop transition visually inspected |
| Runtime inventory / diff whitespace | Passed |

The final live report identifies **app 0.1.4, API 26, com.android.chrome 119.0.6045.194, Asia/Shanghai, deepseek-official / deepseek-flash, mockModel=false**. All three outcomes use the production WebView, real model adapter, real approval service and real bash/file effects in an isolated test project. The test fixture only creates the workspace and projects event metadata. Stop terminates the process; as before, its pending approval can remain an unfinished crash tail. This is not a live UI claim of paired signal cancellation.

The test's configured DeepSeek ref was read from the existing disposable API 36 device, held in host memory and piped directly to API 26 private cache. The original home/settings were not copied. The transient input was consumed/deleted, and the isolated credential/home/project were removed after the run. Successful reports contain no credential, model prose or command arguments; screenshots show synthetic test content. The final run submitted **three prompt turns**. An earlier 0.1.3 development run also submitted three turns (allow/reject succeeded, Stop hit the popup-animation race); other failed setup attempts stopped before model submission. Do not interpret three interactions as an exact API billing-request count, because a tool round trip can invoke the model again.

Repeatable checks with the prepared disposable devices:

```powershell
scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5584 -DeviceSuite PlatformCompatibility
scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5580
scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5582
scripts/verify-live-approval.ps1 -Offline -DeviceSerial emulator-5584 -CredentialDeviceSerial emulator-5580 -OutputDirectory dist/android8-webview/live-run -Python python
```

The live command makes billable model requests and requires the selected credential source, Python/PyYAML and device network access. `-OutputDirectory` keeps separate evidence runs. The screenshot exporter uses fixed labels and validates PNG bytes, clears old test screenshots before a run and removes the exported device-cache files. The existing API 26 AVD and compatible provider are retained for reproduction; owned emulator processes were shut down without saving snapshots. Earlier APKs, validation sections, failed diagnostic logs and historical crash records are preserved.

Final evidence under `dist/android8-webview/`: `provider119-state.txt`, `stock-chrome-signature.txt`, `chrome119-signature.txt`, `chrome119-badging.txt`, `relocation-tags.json`, `settings-bundle-final.txt`, `platform119-final.txt`, `regression-api36.txt`, `regression-api37.txt`, `live-final2.txt`, `live-final2/result.json`, `live-final2/live-approval-ui-*.png`, `ui-screenshot-hashes.json`, `lint-results-debug.xml`, `archive-comparison.json`, `signature.txt`, and `verification-summary.json`. The `live-attempt*`, rejected-provider logs and the initial failed `live-final.txt` are development evidence, not final acceptance.

Local debug artifact: `dist/DSH-Android-0.1.4-debug.apk` (**115988599 bytes**), with its SHA-256 sidecar.

```text
SHA256 ad2a972cca00d821fc6542066db8bde6fa4b50777dee489ffe3203e81e6d8574
```

Acceptance is limited to the recorded Android 8 x86_64/provider combination and the listed flows. ARM64 physical Android 8 hardware, API 27–29, prolonged background/battery behavior, long-conversation compaction and full accessibility acceptance remain unverified. The pinned AGP is still a preview release.

## 18. Emulator follow-up and ARM64 startup diagnostics — 2026-10-16

The user could not attach a physical device while controlling this Windows host
remotely and explicitly authorized emulator verification. The completed acceptance
below is **Android 8.0 / API 26 x86_64**, using a new read-only/no-snapshot session
of `dsh-platform-api26`, current Emulator **37.2.12**, and the previously verified
**com.android.chrome 119.0.6045.194 / 604519427** provider. The guest reports
`sys.boot_completed=1`, `ro.product.cpu.abi=x86_64` and `uname -m=x86_64`.
Its test timezone was provisioned as `Asia/Shanghai`; ADB was returned to the
normal shell UID before the app/model tests. The configured model ref was read
from the separate disposable API 36 emulator, as in section 17.

### Actual fix and final acceptance

The first live run passed every functional assertion, but manual PNG review found
that the Settings screenshot showed the preceding page and the stopped screenshot
still contained the fading native popup. `postVisualStateCallback` precedes display
presentation; it alone did not make those captures reliable. The live instrumentation
now also waits for **350 ms of accessibility idle, bounded at 5 seconds**, after its
visual fence and before `takeScreenshot`. No extra permission, production handler
invocation or change to the app/runtime was introduced. The final full live rerun
passed and the new Settings, approval states, native menu and stopped screenshots
were visually inspected. Both runs and their original PNGs are retained separately;
**`live-x64-final2` is the accepted screenshot set**.

| This turn's final check | Result |
|---|---|
| API 26 platform suite | **15 groups passed**; real Node/approval startup, three native screens, IME geometry, storage, upload/picker ownership and native Stop |
| Production Harness UI | **4 groups passed**; boot/workspace, Settings touch/layout, composer/reload and restored conversation |
| Real DeepSeek Allow once | Marker absent while pending; one allowed-once decision; expected marker created |
| Real DeepSeek Reject | Marker absent while pending and after rejection; one rejected decision |
| Pending approval + native Stop harness | Real menu touch stops the process and revokes serving authority; marker absent; no extra grant |
| Final audit | **3 asked, 1 allowed-once, 1 rejected**; pending process stop remains an unfinished tail, not a claimed cancelled decision pair |
| Model identity | **deepseek-official / deepseek-flash**, `mockModel=false`, production WebView and injected native touch |
| Screenshot evidence | **11 PNGs**, dimensions and SHA-256 recorded; final visual checks passed |
| Java unit tests / Harness policy composition | **25 passed, 1 Windows symlink skip / 10 passed** |
| Strict Java/Gradle/AGP checks and lint | Passed; **0 warnings/deprecations and 0 lint issues** |
| Debug / unsigned Release / test APK assembly | Passed, including a final verification after the screenshot fix |
| Production APK | Still **0.1.4 / versionCode 6**, **115988599 bytes**; SHA-256 unchanged |

There were **six billable prompt turns this turn**: three in the first functional
run and three in the final evidence rerun. This is a turn count, not an exact model
API request or billing count. The transient input, isolated home/project/credential,
isolated preferences and exported screenshot cache files were cleaned; the normal
Harness home and source-device credential were retained. Host reports omit the key,
model prose and command arguments. Screenshots contain synthetic test content.

### ARM64 experiments: incomplete system boot, no app acceptance

The Windows host is x86_64. The ARM64 experiments used separate AVD directories and
side-by-side archived emulator installations; the normal SDK emulator and original
system images were not replaced or patched.

| Attempt | Observed result |
|---|---|
| Emulator 37.2.12 + official API 26 Google APIs ARM64 r03 | Exits with `QEMU2 emulator does not support arm64 CPU architecture` |
| Archived Emulator 31.3.14 / build 9322596 | Stops at `PCI bus not available for hda`; disabling host audio does not remove that device |
| 31.3.14 + separate host launcher metadata set to API 25 | Unchanged guest starts ADB and reports **Android 8.0.0 / API 26, arm64-v8a, aarch64**; audio HAL/audioserver crash repeatedly and boot never completes |
| Archived Emulator 28.0.25 / build 5395263 | Kernel starts, but `/system` mount at `a003800.virtio_mmio` fails and init halts; drive-option experiments did not repair it |

The host-metadata experiment changes only the external AVD target, external
`source.properties` API level and external `build.prop` SDK value. It bypasses the
31.3.14 launcher audio branch, whose [upstream implementation](https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-31-release/android-qemu2-glue/main.cpp)
selects HDA at API 26. It does **not** change Android's internal API level.
`system.img`, `vendor.img`, `ramdisk.img` and `kernel-ranchu` match the official
archive, as recorded in `guest-image-integrity.json`. Reaching ADB and reporting
API 26 is partial boot evidence only. **No platform suite, Harness UI, native app
runtime or model approval test ran on ARM64.** The launch workaround is retained
for diagnosis and is not a recommended acceptance environment.

Both official API 26 ARM64 archives were retained. The r03 SDK SHA-1 matches
**7a9764f1856119c4ceb311f6c76ff975a4c1e65d**; r01 SHA-256 is
**3ba0c7fb990542d7ffa9af09a3963484802d6e698d4d557fc0487bdf65582d63**.
Emulator archives were obtained from Google's repository via the
[official emulator archive](https://developer.android.com/studio/emulator_archive).
Their SHA-256 values are recorded in the evidence summary.

The [ARM64 Chrome 119 artifact](https://www.apkmirror.com/apk/google-inc/chrome/chrome-119-0-6045-194-release/google-chrome-fast-secure-119-0-6045-194-android-apk-download/)
was downloaded and its Google v2/v3 signatures and SourceStamp verified on the host:
versionCode **604519421**, minSdk 24/maxSdk 28, arm64-v8a + armeabi-v7a,
**244362316 bytes**, SHA-256
**a1df6a06ae9e6a7cdd1b0b22b38b98214f105fbe0755893189d239e45a37b3e7**.
Its signer matches section 17's Google fingerprint. It was **not installed or loaded
in a usable ARM64 guest**. Host inspection also confirms that the app's three
`lib/arm64-v8a` executable entries are 64-bit AArch64 ELF (`e_machine=183`);
this does not establish ARM64 runtime compatibility. Chrome 119 remains a frozen
compatibility-test provider with the support limitations already stated in section 17.

Repeat the accepted checks on the prepared disposable emulators:

```powershell
scripts/verify-app.ps1 -Offline -DeviceSerial emulator-5584 -DeviceSuite PlatformCompatibility
scripts/verify-live-approval.ps1 -Offline -DeviceSerial emulator-5584 -CredentialDeviceSerial emulator-5580 -OutputDirectory dist/android8-emulator-followup/live-rerun -Python D:/anaconda3/python.exe
```

Evidence is under `dist/android8-emulator-followup/`: `verify-host-final.txt`,
`platform-x64-final.txt`, `accepted-guest-identity.json`, `provider-x64-final.txt`,
`app-install-state.txt`, `live-x64-final2.txt`, `live-x64-final2/result.json`,
`live-x64-final2/live-approval-ui-*.png`, `ui-screenshot-hashes.json`,
`lint-results-debug.xml`, `cleanup.json`, `arm64-diagnostics.json`,
`arm64-apk-inventory.json`, `guest-image-integrity.json`, archived-engine startup
logs and `verification-summary.json`. Owned emulator processes were shut down;
diagnostic AVDs/images/logs and previous evidence remain available.

```text
APK SHA256 ad2a972cca00d821fc6542066db8bde6fa4b50777dee489ffe3203e81e6d8574
```

The emulator acceptance is complete for the listed **x86_64/provider/flow**
combination. Android 8 ARM64 emulator runtime and ARM64 physical hardware remain
**unverified**; physical battery/background behavior, API 27–29, long-conversation
compaction and full accessibility acceptance also remain outside these results.
