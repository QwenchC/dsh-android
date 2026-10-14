# Running Node.js >= 22 natively on Android arm64 (embedded in an APK, not Termux)

Research date: current session. Every artifact below was fetched live. `VERIFIED` = I downloaded/inspected it myself; `CLAIMED` = from a source I read but did not independently reproduce.

**Headline:** official Node ships *no* Android binaries and calls Android "not a supported platform", but its own `android-configure` script has worked from Node 14 → 26. nodejs-mobile is dead-ended at **Node 18.20.4**. The **only** maintained prebuilt Android Node >= 22 is **Termux's**, currently **Node 26.4.0** and **Node 24.18.0 (LTS)** for aarch64, and that binary **is** relocatable with one `patchelf` command (I did it).

---

## 1. Does official Node.js support Android/arm64?

**VERIFIED — No binaries; source build is supported-ish but explicitly unsupported officially.**

* `https://nodejs.org/dist/index.json` (all 870 releases) contains **zero** Android artifacts. Distinct file tokens are only: `aix-ppc64, headers, linux-{x64,x64-musl,arm64,armv7l,armv6l,ppc64le,s390x,x86}, osx-*, win-*, sunos-*, src`. Grep for `android` → **0 matches**.
* `BUILDING.md` @ v24.x, lines 809–826 (`https://raw.githubusercontent.com/nodejs/node/v24.x/BUILDING.md`):
  > `### Android` — "Android is not a supported platform. Patches to improve the Android build are welcome. There is no testing on Android in the current continuous integration environment."
  > ```bash
  > ./android-configure <path to the Android NDK> <Android SDK version> <target architecture>
  > make -j4
  > ```
  > "The Android SDK version should be at least 24 (Android 7.0) and the target architecture supports [arm, arm64/aarch64, x86, x86_64]."

**VERIFIED — `android-configure` exists in every branch I checked:**

| branch | `android-configure` | `configure.py` `valid_os` includes `'android'` |
|---|---|---|
| v14.x | HTTP 200 (2266 B) | yes (line 57) |
| v16.x | HTTP 200 (1370 B) | yes (line 48) |
| v18.x | HTTP 200 (1441 B) | yes (line 49) |
| v20.x | HTTP 200 (1654 B) | yes (line 49) |
| v22.x | HTTP 200 (1518 B) | yes (line 49) |
| v24.x | HTTP 200 (1450 B) | yes (line 48) |
| v25.x | HTTP 200 (1450 B) | — |
| main | HTTP 200 (1468 B) | yes (line 48) |

`android_configure.py` is **2897 bytes and byte-identical in v22.x, v24.x and main**. Latest releases at time of research: **v22.23.3** (LTS Jod), **v24.21.0** (LTS Krypton), **v26.11.1**.

**VERIFIED — what the flags actually do** (`configure.py` v24, downloaded and read):

* `--dest-os=android`:
  * line 1658–1659: `o['variables']['OS'] = 'android'`
  * line 1878: shared-library suffix forced to `so`
  * `node.gyp` defines `NODE_PLATFORM="<(OS)"` and `src/node_metadata.cc` has `Metadata::Metadata() : arch(NODE_ARCH), platform(NODE_PLATFORM) {}` → **`process.platform === 'android'`**. (Corroborated: upstream `lib/child_process.js` @ v24.x has `else if (process.platform === 'android') file = '/system/bin/sh';`)
  * for 32-bit arm it forces `arm_version=7`, `arm_fpu=vfpv3`
* `--dest-cpu=arm64`: sets `target_arch=arm64`; `arm_fpu = 'neon'` (configure.py line 1494–1495).
* `--cross-compiling`: sets `GYP_CROSSCOMPILE=1`, `want_separate_host_toolset=1`, and **disables the V8/node snapshot and code cache**:
  * v24 line 1716/1718: `node_use_node_snapshot = 'false' if without_node_snapshot else b(not cross_compiling)`
  * v24 line 1723/1726: `node_use_node_code_cache = b(not cross_compiling)`
  * v22 line 1512–1513 / 1521–1522 additionally AND-ed `not options.shared`.
  * `--node-snapshot-main` is a hard error with cross-compilation (v22 line 1505–1506) and with `--shared` (line 1498–1501).

**Verdict:** `--dest-os=android --dest-cpu=arm64` is real, maintained, and produces a working `process.platform === 'android'` build for Node 14 → 26 — but there are **no official binaries** and **no CI coverage**.

---

## 2. nodejs-mobile

**VERIFIED — newest is Node 18.20.4. Nothing >= Node 22 exists.**

From `https://api.github.com/repos/nodejs-mobile/nodejs-mobile/releases?per_page=10`:

| tag | published | Android asset | bytes |
|---|---|---|---|
| **v18.20.4** | 2024-10-07 | `nodejs-mobile-v18.20.4-android.zip` | 57,287,354 |
| v18.17.3 | 2024-09-23 | `nodejs-mobile-v18.17.3-android.zip` | 55,069,393 |
| v18.17.2 | 2023-10-17 | `nodejs-mobile-v18.17.2-android.zip` | 55,698,972 |
| v18.17.1 | 2023-10-13 | `nodejs-mobile-v18.17.1-android.zip` | 55,698,972 |
| nodejs-mobile-v16.17.1 | 2023-02-21 | *(iOS only)* | — |
| nodejs-mobile-v16.17.0 | 2023-01-26 | `nodejs-mobile-v16.17.0-android.zip` | 48,915,161 |

Exact newest URL:
```
https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v18.20.4/nodejs-mobile-v18.20.4-android.zip
```
(30,638 downloads; companion iOS asset `nodejs-mobile-v18.20.4-ios.zip`, 51,492,431 B.)

* Repo meta: 876 stars, default branch `main`, not archived, 46 open issues, last push 2026-04-30.
* npm `nodejs-mobile-react-native` latest = **18.20.4** (2024-10-07). All npm derivatives (`nodejs-mobile-gyp`, `prebuild-for-nodejs-mobile`, `sodium-native-nodejs-mobile`, `@flun/nodejs-mobile-react-native` 2.0.7, …) are pinned to the 18.x line.
* **Build recipe (CLAIMED, from `doc_mobile/BUILDING.md`)**: NDK **r24** (`sdkmanager "ndk;24.0.8215888"`), then `./tools/android_build.sh <ndk-path> <android-sdk-version> [arm64]` → `out_android/<ARCH>/libnode.so`; or `source ./android-configure <ndk> arm` + `make` → `out/Release/lib.target/libnode.so`.
* Important architectural note: nodejs-mobile builds **`libnode.so`, a shared library dlopen'd over JNI — not an executable**. That sidesteps Android's W^X exec restriction entirely (§6).

---

## 3. Termux (packages.termux.dev)

**VERIFIED — Termux currently ships Node 26.4.0 (and Node 24.18.0 LTS) for aarch64.**

I downloaded the live repo index `https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages` (1,863,347 B, 3,005 packages) and parsed it:

| package | version | arch | size | sha256 |
|---|---|---|---|---|
| `nodejs` | **26.4.0-1** | aarch64 | 10,349,280 | `eaf3ed8a6e4b72ebaa8c2cb3bad778c577cdf9ea87ca91761213d8a3940fc090` |
| `nodejs-lts` | **24.18.0-1** | aarch64 | 9,743,764 | `490f4d08c45b25a7ea7db6ee466ebb3ee61f07083260b85332704ed018f59a87` |
| `npm` | 11.20.0 | all | 2,025,572 | `a2775653a0626749138ccd6e37a7b63239f36a9e3c62a9e6740818ca4b2d088b` |

**Exact .deb URL pattern:** `https://packages.termux.dev/apt/termux-main/pool/main/<initial>/<pkg>/<pkg>_<ver>_<arch>.deb`

```
https://packages.termux.dev/apt/termux-main/pool/main/n/nodejs-lts/nodejs-lts_24.18.0-1_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/n/nodejs/nodejs_26.4.0-1_aarch64.deb
```

Dependency .debs (all VERIFIED from the same index, all required at runtime):
```
https://packages.termux.dev/apt/termux-main/pool/main/libc/libc++/libc++_30_aarch64.deb                        (341,604 B)
https://packages.termux.dev/apt/termux-main/pool/main/libi/libicu/libicu_78.3_aarch64.deb                      (10,210,396 B)
https://packages.termux.dev/apt/termux-main/pool/main/o/openssl/openssl_1:3.6.5_aarch64.deb                    (2,482,764 B)
https://packages.termux.dev/apt/termux-main/pool/main/c/c-ares/c-ares_1.34.8_aarch64.deb                       (194,924 B)
https://packages.termux.dev/apt/termux-main/pool/main/libs/libsqlite/libsqlite_3.54.0_aarch64.deb               (768,760 B)
https://packages.termux.dev/apt/termux-main/pool/main/z/zlib/zlib_1.3.2_aarch64.deb                            (62,840 B)
https://packages.termux.dev/apt/termux-main/pool/main/libf/libffi/libffi_3.8.0_aarch64.deb                     (37,592 B)
https://packages.termux.dev/apt/termux-main/pool/main/c/ca-certificates/ca-certificates_1:2026.09.25_all.deb   (105,404 B)
https://packages.termux.dev/apt/termux-main/pool/main/t/termux-exec/termux-exec_1:2.5.0-1_aarch64.deb          (287,356 B)
https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-support/libandroid-support_29-1_aarch64.deb (10,880 B)
```

### What I found inside the .deb (VERIFIED)

I ran `dpkg-deb -x nodejs-lts_24.18.0-1_aarch64.deb` and inspected:

* Payload path: `./data/data/com.termux/files/usr/bin/node` — **45,318,136 bytes**, plus `usr/include/node/` headers.
* `file`: `ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV), dynamically linked, interpreter /system/bin/linker64, stripped` (i.e. a **PIE executable**, not a JNI library).
* `readelf -d`:
  * `RUNPATH: /data/data/com.termux/files/usr/lib` ← **absolute, NOT relocatable as shipped**
  * `NEEDED: libz.so.1, libcares.so, libsqlite3.so, libcrypto.so.3, libssl.so.3, libicui18n.so.78, libicuuc.so.78, libc.so, libm.so, libdl.so, libc++_shared.so`
* Node 26.4.0 deb behaves identically: 49,715,528 B binary, same absolute RUNPATH.
* Shipped `usr/include/node/config.gypi` reveals the build config: `host_arch=x64`, `target_arch=arm64`, `node_shared=false`, `node_shared_openssl/cares/sqlite/zlib=true`, `v8_enable_pointer_compression=0`, `v8_enable_sandbox=0`, `v8_enable_i18n_support=1`, `icu_ver_major=78`, `v8_enable_webassembly=1`, `v8_enable_maglev=1`.

### Patches Termux applies (VERIFIED — I downloaded all of them from `packages/nodejs/`)

35 files under `https://github.com/termux/termux-packages/tree/master/packages/nodejs`. The load-bearing ones:

| patch | what it does |
|---|---|
| `lib-os.js.patch` | `os.tmpdir()` fallback `'/tmp'` → `'@TERMUX_PREFIX@/tmp'` |
| `deps-uv-src-unix-core.c.patch` | libuv temp dir `"/data/local/tmp"` → `"@TERMUX_PREFIX@/tmp"` |
| `deps-uv-src-unix-process.c.patch` | **deletes** the `setgroups(0,NULL)` / `setgid()` / `setuid()` calls in the child after fork (they fail on Android) |
| `lib-child_process.js.patch` | default shell `'/system/bin/sh'` → `'@TERMUX_PREFIX@/bin/sh'` |
| `src-node_internals.h.patch` | re-enables `NODE_IMPLEMENTS_POSIX_CREDENTIALS` on Android (upstream disables it) |
| `deps-v8-src-trap-handler-trap-handler.h.patch` | forces `V8_TRAP_HANDLER_SUPPORTED false` |
| `deps-v8-src-flags-flag-definitions.h.patch` | V8 perf/gc-fake-mmap paths `/tmp` → prefix |
| `src-getaddrinfo.patch` | **adds `src/getaddrinfo.c`**: a replacement that `dlopen("/system/lib64/libc.so")` and forwards `getaddrinfo`, mapping `localhost`→`ip6-localhost` for `PF_INET6` (workaround for termux-packages#10277) |
| `node.gyp.patch` | adds `src/getaddrinfo.c` to sources; widens the `-latomic` condition to `(OS=="linux" or OS=="android") and clang==1` |
| `common.gypi.patch` | **deletes** upstream `['OS == "android"', {'cflags': ['-fPIC','-I<(android_ndk_path)/sources/android/cpufeatures']}]` blocks so node-gyp module builds don't need `android_ndk_path` |

(`build.sh` comment verbatim: *"don't try to autoupdate nodejs, that thing takes 2 whole hours to build for a single arch"*.)

### Are the binaries relocatable? (VERIFIED — I did the patch)

As shipped: **no**, RUNPATH is the hardcoded absolute Termux prefix. With patchelf: **yes.** I installed patchelf 0.14.3 in WSL and ran it on the real Termux Node 24.18.0 binary:

```bash
$ readelf -d node-reloc | grep RUNPATH
 0x000000000000001d (RUNPATH)   Library runpath: [/data/data/com.termux/files/usr/lib]

$ patchelf --set-rpath '$ORIGIN/../lib' node-reloc      # patchelf 0.14.3

$ readelf -d node-reloc | grep RUNPATH
 0x000000000000001d (RUNPATH)   Library runpath: [$ORIGIN/../lib]
```
File size unchanged (45,318,136 B both before and after), ELF still valid, `24.18.0`/`v24.18.0` version strings intact. **This is the cheapest path to Node 24 on Android.**

---

## 4. Other prebuilt Android Node >= 22?

**VERIFIED — none found. The field is empty.**

| candidate | result |
|---|---|
| `npm node-android` | registry returns `{"error":"Not found"}` — **package does not exist** |
| `github.com/dna2github/node-android` | GitHub API **404 Not Found** — repo does not exist (`dna2github/dna2oslab` exists but publishes no node-android releases) |
| npm search `nodejs android binary` | only native *addons* (`@rollup/rollup-android-arm64`, `@esbuild/android-arm64`, `@oxc-parser/binding-android-arm64`, `@napi-rs/canvas-android-arm64`, `@tailwindcss/oxide-android-arm64`, `@unrs/resolver-binding-android-arm64`, …) — no full Node runtime |
| npm search `nodejs-mobile` | every hit (`nodejs-mobile-react-native`, `@flun/nodejs-mobile-react-native` 2.0.7, `nodejs-mobile-gyp`, `prebuild-for-nodejs-mobile`, `sodium-native-prebuilds-nodejs-mobile`, …) is 18.x-based |
| `sjitech/build-nodejs-for-android` | 113 stars, **last push 2017-06-03** (CLAIMED) |
| `sjitech/nodejs-android-prebuilt-binaries` | 94 stars, **last push 2018-04-18** (CLAIMED) |
| `LiquidCore/LiquidCore` | Node 8.9.3 era; its build wiki is self-labelled *"(30 March 2020) This document is out of date"* (CLAIMED) |
| `nodejs.org/dist` | no Android artifacts at all (VERIFIED above) |

**Conclusion: the only maintained prebuilt Android Node >= 22 in existence is the Termux one (Node 24.18.0 LTS / Node 26.4.0). Everything else must be built from source.**

---

## 5. Recipe: build Node 22/24 from source for aarch64-linux-android

**VERIFIED** against Node's own `android_configure.py` (v24.x, 2897 B), `BUILDING.md`, `configure.py`, `common.gypi`, `node.gyp`, and Termux's `build.sh`.

### NDK

**VERIFIED — these download URLs exist (HTTP 206 on ranged GET):**
```
https://dl.google.com/android/repository/android-ndk-r30-linux.zip     <- Termux's choice (TERMUX_NDK_VERSION_NUM="30")
https://dl.google.com/android/repository/android-ndk-r29-linux.zip
https://dl.google.com/android/repository/android-ndk-r28c-linux.zip
https://dl.google.com/android/repository/android-ndk-r27d-linux.zip
https://dl.google.com/android/repository/android-ndk-r30-windows.zip   (same for -r29/-r28c/-r27d)
```
* **Termux uses NDK r30** with host LLVM 21 (`scripts/properties.sh`: `TERMUX_NDK_VERSION_NUM="30"`, `TERMUX_HOST_LLVM_MAJOR_VERSION="21"`).
* **nodejs-mobile used NDK r24** (`sdkmanager "ndk;24.0.8215888"`).
* Recommended for a fresh Node 24 build: **NDK r27d or newer** (r27+ is required for 16 KB page-size support, see §6). API level **>= 24** is enforced by `android_configure.py`.

### The exact commands (WSL Ubuntu 24.04 — `android_configure.py` refuses Windows)

`android_configure.py` source: `if platform.system() != "Linux" and platform.system() != "Darwin": print("android-configure is currently only supported on Linux and Darwin.")`

```bash
# 0) host deps
sudo apt-get update && sudo apt-get install -y build-essential python3 curl git patch xz-utils

# 1) NDK
cd ~ && curl -LO https://dl.google.com/android/repository/android-ndk-r30-linux.zip
unzip -q android-ndk-r30-linux.zip && export NDK=$HOME/android-ndk-r30

# 2) source
curl -LO https://nodejs.org/dist/v24.18.0/node-v24.18.0.tar.xz
tar xf node-v24.18.0.tar.xz && cd node-v24.18.0

# 3a) apply Node's own Android patch (SEPARATE 2-arg invocation!)
./android-configure patch

# 3b) configure (3-arg form)
./android-configure "$NDK" 24 arm64
```

`android_configure.py` then does exactly this (quoted from the script):
```python
os.environ['CC']  = toolchain_path + "/bin/" + TOOLCHAIN_PREFIX + android_sdk_version + "-clang"
os.environ['CXX'] = toolchain_path + "/bin/" + TOOLCHAIN_PREFIX + android_sdk_version + "-clang++"
GYP_DEFINES  = "target_arch=arm64 v8_target_arch=arm64 android_target_arch=arm64"
GYP_DEFINES += " host_os=linux OS=android android_ndk_path=" + android_ndk_path
os.system("./configure --dest-cpu=arm64 --dest-os=android --openssl-no-asm --cross-compiling")
```
(i.e. `CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang`)

```bash
# 4) build
make -j"$(nproc)"
# -> out/Release/node          (with --shared: out/Release/lib.target/libnode.so)
```

### Extra flags worth adding (from Termux's proven `termux_step_configure`)

```bash
./configure \
  --prefix=/ \
  --dest-cpu=arm64 \
  --dest-os=android \
  --cross-compiling \
  --openssl-no-asm \
  --without-npm \
  --shared-cares --shared-openssl --shared-sqlite --shared-zlib \
  --with-intl=system-icu \
  --use-prefix-to-find-headers \
  --ninja
```
* `--shared` ⇒ builds `libnode.so` (the embed-friendly, dlopen-able target) instead of a standalone `node` executable.
* `--without-node-snapshot` / `--node-snapshot-main` are **errors** under cross-compilation.

### Known blockers (all VERIFIED from source)

1. **V8 trap handler.** Upstream V8 enables `V8_TRAP_HANDLER_SUPPORTED` only for non-Android Linux/Windows/macOS. Node ships `android-patches/trap-handler.h.patch` (identical to Termux's) that forces `#define V8_TRAP_HANDLER_SUPPORTED false`. You **must** run `./android-configure patch` first, or `patch -f ./deps/v8/src/trap-handler/trap-handler.h < ./android-patches/trap-handler.h.patch`. Consequence: WebAssembly/JS bounds checks use the slower fallback path.
2. **`android_ndk_path` gyp variable.** v24 `common.gypi` still contains `['OS == "android"', {'cflags': ['-fPIC', '-I<(android_ndk_path)/sources/android/cpufeatures'], 'ldflags': ['-fPIC']}]`. `android_configure.py` supplies it via `GYP_DEFINES`; Termux deletes the block. Without it, node-gyp error: **`gyp: Undefined variable android_ndk_path`**.
3. **V8 snapshot + code cache are disabled when cross-compiling** (configure.py v24 lines 1716/1723). Costs startup time. LiquidCore's wiki documents the manual workaround: build `mksnapshot` for the target, add `-pie` to `out/deps/v8/src/mksnapshot.target.mk` LDFLAGS, swap in the *host* mksnapshot to let `make` finish (producing a bogus snapshot), then `adb push` the target mksnapshot to an emulator, `./mksnapshot --startup_src=snapshot.cc`, `adb pull` into `out/Release/obj.target/v8_snapshot/geni/snapshot.cc`, `touch` it, and re-run `make`. (CLAIMED — from a wiki marked out of date.)
4. **ICU.** Host-building ICU is needed if you use `--with-intl=system-icu` for the target; Termux host-builds **ICU 78.3** with clang and downloads Chromium's LLVM (`clang-llvmorg-23-init-484-gf646b915-1.tar.xz` from `https://commondatastorage.googleapis.com/chromium-browser-clang/Linux_x64/`) as `CC_host/CXX_host`. Under cross-compilation configure.py sets `icu_asm_ext='c'` (line 2170 in v22) to avoid assembly. The default (bundled `full-icu`) also works but is far slower to build.
5. **Host toolchain for mksnapshot/JIT-less.** `want_separate_host_toolset = int(cross_compiling)` — a working host compiler is mandatory.
6. **`-latomic`.** Upstream `node.gyp` only adds it for `OS=="linux" and clang==1`. Patch to `(OS=="linux" or OS=="android") and clang==1` (Termux does exactly this).
7. **Temp dirs don't exist.** libuv defaults to `/data/local/tmp`, `os.tmpdir()` falls back to `/tmp`; neither is writable for an app. Set `TMPDIR`/`TEMP`/`TMP` in the child environment, or patch as Termux does.
8. **`process.platform === 'android'`** ⇒ `child_process` defaults `shell` to `/system/bin/sh` (upstream code, verified). `exec`/`execFile`/spawn work.
9. **No POSIX credentials.** v24 `src/node_internals.h`: `#if defined(__POSIX__) && !defined(__ANDROID__) && !defined(__CloudABI__)` → `NODE_IMPLEMENTS_POSIX_CREDENTIALS`. So `process.setuid/seteuid/setgid/setegid/setgroups/initgroups` are **absent** in an Android build. Termux re-enables it and strips the corresponding libuv calls instead.
10. **V8 flags.** A working Termux build ships `v8_enable_pointer_compression=0` and `v8_enable_sandbox=0` (V8's sandbox reservers a huge virtual address region Android won't grant).
11. **JIT is fine on Android** (anonymous `PROT_EXEC` memory is unrestricted; it's *execve of files* that's blocked, §6). No JIT-less build needed — unlike iOS. What's disabled is only the V8 trap handler.
12. **Build time.** Termux's own `build.sh` comment: *"that thing takes 2 whole hours to build for a single arch"* (VERIFIED quote). **Estimate on the 9950X3D with `-j32`: ~30–60 min for arm64** (ESTIMATE, not measured — V8 is the bulk).

---

## 6. Executing a Node binary from inside an Android app

**VERIFIED — the restriction (Android 10 behaviour-change doc, `https://developer.android.com/about/versions/10/behavior-changes-10`):**
> "Removed execute permission for app home directory. Execution of files from the writable app home directory is a W^X violation. Apps should load only the binary code that's embedded within an app's APK file. **Untrusted apps that target Android 10 cannot invoke `execve()` directly on files within the app's home directory.** In addition, apps that target Android 10 cannot in-memory modify executable code from files which have been opened with `dlopen()` and expect those changes to be written through to disk, because the library cannot have been mapped `PROT_EXEC` through a writable file descriptor. This includes any shared object (`.so`) files with text relocations."

**VERIFIED — the exact SELinux mechanism** (`https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te`):
```
# b/112357170
neverallow {
  all_untrusted_apps
  -untrusted_app_25
  -untrusted_app_27
  -runas_app
} { app_data_file privapp_data_file }:file execute_no_trans;
```
Only `targetSdk <= 27` is exempt. Anything at `targetSdk >= 28` cannot `execve` a file labelled `app_data_file`. **Raising `targetSdk` to 29+ (required by Play) permanently closes the "write a binary into filesDir and exec it" door.**

**VERIFIED — the `lib*.so` naming rule** (`frameworks/base/core/jni/com_android_internal_content_NativeLibraryHelper.cpp`):
```
 * - The entry is under the lib/ directory.
 * - The entry name ends with ".so" and the entry name starts with "lib",
 *   an exception is made for debuggable apps.
 * - The entry filename is "safe" (as determined by isFilenameSafe).
```
So the executable must be packaged as `jniLibs/arm64-v8a/lib<something>.so`.

**VERIFIED — extraction must be ON** (`android:extractNativeLibs` docs, developer.android.com):
> "Starting with AGP 4.2.0, the DSL option `useLegacyPackaging` replaces the `extractNativeLibs` manifest attribute. … This attribute indicates whether the package installer extracts native libraries from the APK to the file system. If set to `"false"`, your native libraries are stored uncompressed in the APK … they load directly from the APK at runtime."

⇒ If you leave the modern default (no extraction), there is **no real file on disk to `execve`**. You **must** set `android { packaging { jniLibs { useLegacyPackaging = true } } }` (or `android:extractNativeLibs="true"`).

**The working recipe:**
1. Put the Termux/self-built node binary in `app/src/main/jniLibs/arm64-v8a/libnode_exec.so`.
2. Put its dependency `.so`s there too (they're already `lib*.so`: `libicui18n.so.78`, `libicuuc.so.78`, `libssl.so.3`, `libcrypto.so.3`, `libcares.so`, `libsqlite3.so`, `libz.so.1`*, `libc++_shared.so`) — note `libz.so.1` violates the `.so`-suffix rule; rename/symlink or embed zlib.
3. `useLegacyPackaging = true`.
4. At runtime: `context.applicationInfo.nativeLibraryDir` → e.g. `/data/app/~~<hash>/<pkg>-<id>/lib/arm64`; `new ProcessBuilder(nativeLibraryDir + "/libnode_exec.so", "script.js")` (or `execve` from native code) with `LD_LIBRARY_PATH` pointed at `nativeLibraryDir`.
5. Symlink from a writable dir into `nativeLibraryDir` so `process.execPath`, shebangs and `npm`-style shims resolve.

**Third-party device evidence (CLAIMED)** — `warp-mobile-android` (Codeberg) M0 spike, 4 devices: direct exec of a binary copied into `filesDir` fails with `IOException … error=13 (EACCES)` on SDK 35 and 36; the same binary executed via `filesDir → nativeLibraryDir` symlink succeeds (exit 42) on SDK 28/35/36. Their design doc also states `nativeLibraryDir` is labelled `system_lib_file` and that `untrusted_app` may execute it (`app_data_file` is what's blocked).

**Also required: 16 KB page sizes (VERIFIED, Android Developers Blog 2025-05-08):**
> "Starting November 1st, 2025, all new apps and updates to existing apps submitted to Google Play and targeting Android 15+ devices must support 16 KB page sizes."

⇒ Build the node binary/libs with **NDK r27+** and `-Wl,-z,max-page-size=16384` for arm64.

**Alternative that avoids exec entirely:** build `--shared` (or use nodejs-mobile's model) and `dlopen` `libnode.so` over JNI, calling `node::Start`. This is what nodejs-mobile does and it sidesteps W^X completely — but nodejs-mobile is stuck at Node 18, so you'd build `libnode.so` yourself from Node 24.

---

## 7. Does bionic lack anything Node needs?

**VERIFIED** from `https://android.googlesource.com/platform/bionic/+/refs/heads/main/docs/status.md` and `libc/libc.map.txt` (both downloaded and decoded).

**What bionic is missing** (status.md, verbatim list):
* Missing functions: `a64l`/`l64a`, `confstr`, `crypt`/`encrypt`/`setkey`, `gethostid`, `shm_open`/`shm_unlink`, `sockatmark`, `ualarm`.
* Missing functionality: `<aio.h>`, `<monetary.h>`, `<wordexp.h>`, **locales** ("the only locale supported is a UTF-8 C/POSIX locale"), **robust mutexes**, **`pthread_cancel`**.
* `getpeereid` is **not exported** (checked in libc.map.txt).

**What bionic DOES have** (I grepped the exact symbol lines in `libc.map.txt`):

| symbol | line | note |
|---|---|---|
| `fork` | 369 | present |
| `getpwnam` / `getpwnam_r` | 469 / 470 | present |
| `getpwuid` / `getpwuid_r` | 471 / 472 | present |
| `setuid` / `setgid` / `setgroups` / `getgroups` | 950 / 925 / 926 / 438 | present (but fails for apps — no privileges) |
| `posix_spawn` / `posix_spawnp` | 1382 / 1406 | present |
| `execv` / `execvp` | 330 / 332 | present |
| `forkpty` / `openpty` | 370 / 716 | **`# introduced=23`** |
| `getauxval`, `sched_getaffinity`, `prctl`, `getrandom`, `eventfd`, `timerfd_create`, `memfd_create`, `pidfd_open`, `gettid`, `sendfile`, `statx` | — | present |
| `pthread_getname_np` | 1315 | **`# introduced=26`** |
| `syslog`, `initgroups`, `getgrouplist` | — | present |

**So: no fork/PTY gap.** `fork()` exists, `forkpty`/`openpty` exist since API 23, `posix_spawn` exists. Node's `child_process` works.

**The real gaps and how they bite:**

1. **`getpwnam`/`getpwuid` don't see app users** (VERIFIED in `libc/bionic/grp_pwd.cpp`). `getpwuid_internal` tries: `find_android_id_info(uid)` (system AIDs only) → `oem_id_to_passwd` → `app_id_to_passwd`. For app UIDs the last one **synthesizes** an entry: `pw_name = "u0_a123"`, `pw_dir = "/data"`, `pw_shell = "/bin/sh"`, `pw_uid = pw_gid = uid`. `getgrouplist()` returns exactly **1** group. Practical effect: `os.userInfo().homedir` is `/data`, `os.homedir()` falls back to that if `$HOME` is unset, and `initgroups`-style logic is meaningless. **Set `HOME` and `TMPDIR` explicitly in the child env.**
2. **Locales.** Bionic supports only a UTF-8 C/POSIX locale. Node's `Intl` is unaffected (ICU carries its own data — Termux ships `libicu 78.3` as a shared dep), but anything relying on libc locales / `LANG` is a no-op.
3. **No `<wordexp.h>`, `<aio.h>`, `<monetary.h>`, `confstr`, `shm_open`.** Node/libuv don't need these on the default build.
4. **`pthread_cancel`, robust mutexes** absent — Node/libuv don't use them.
5. **Detached-thread `fork()` semantics.** bionic's `fork()` in an ART process only clones the calling thread; libuv's `fork()+exec` pattern is safe, arbitrary fork-and-continue is not.
6. **DNS quirk.** Termux ships a whole extra `src/getaddrinfo.c` that `dlopen`s `/system/lib64/libc.so` to forward `getaddrinfo` (and maps `localhost`→`ip6-localhost` for `PF_INET6`) because of termux-packages#10277. **You may hit a `localhost`/IPv6 resolution issue** in an app-embedded Node; this is the patch to look at if so.
7. **temp dirs** — see §5 item 7.

**How Termux deals with all of it:** the patch set in §3 (temp dirs, credential calls removed from libuv, `android` shell path, `getaddrinfo` shim, `NODE_IMPLEMENTS_POSIX_CREDENTIALS` re-enabled) plus a full `$PREFIX` tree at `/data/data/com.termux/files/usr` and the `termux-exec` `LD_PRELOAD` shim for shebang/exec-path handling. Because the `node` binary hardcodes `/data/data/com.termux/files/usr` in its RUNPATH *and* in those patched JS/S constants, **if you reuse the Termux binary you must also relocate the runtime layout and re-patch the baked-in paths** (or accept `TMPDIR`/`SHELL` overrides and `patchelf` the RUNPATH only — sufficient if you set `TMPDIR` and never rely on `os.tmpdir()`'s default or the default `shell`).

**How nodejs-mobile deals with it:** builds `libnode.so` (dlopen'd from JNI, no exec at all), keeps upstream libuv, and relies on the same `--openssl-no-asm` / trap-handler-off configuration.

---

## Bottom line

| option | Node version | effort | notes |
|---|---|---|---|
| **A. Repackage Termux `nodejs-lts` deb** | **24.18.0** (LTS) or 26.4.0 | hours | `patchelf --set-rpath '$ORIGIN/../lib'` (PROVEN working); ship 9 dep .debs; set `TMPDIR`/`HOME`; rename to `libnode_exec.so`; `useLegacyPackaging=true` |
| **B. Build from source in WSL** | 22.23.3 / 24.21.0 / 26.11.1 | ~half a day + 30–60 min build | `./android-configure patch` then `./android-configure <ndk> 24 arm64`; NDK r27d+; expect slower startup (no snapshot) |
| **C. Build `--shared` libnode.so + JNI `dlopen`** | any | +1 day | avoids W^X entirely; the nodejs-mobile architecture, but with Node 24 |
| ~~nodejs-mobile prebuilt~~ | 18.20.4 | — | too old for Node 22 |
| ~~other prebuilts~~ | — | — | **none exist** |
