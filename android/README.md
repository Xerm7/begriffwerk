# Begriffwerk Android

The installable application is `../releases/Begriffwerk-1.0.0.apk`.

## Install on your phone

1. Copy the APK to the Android phone (USB, a private file transfer, or your usual file-sharing method).
2. Open the APK on the phone and allow your file manager to install this app if Android asks.
3. Tap Install, then open **Begriffwerk**.

Android **8.0 or newer** and an up-to-date **Android System WebView / Chrome** are required. The JavaScript interface uses modern WebView features (including modules, top-level await, optional chaining and `Array.at`); use WebView 100 or newer. The APK is universal, with no architecture-specific native libraries. It is signed with a locally generated personal release key, not a Play Store publishing identity.

Everything works offline: all 1,442 exact pairs from the supplied JSON files are bundled, and progress is stored in Android's native SQLite database in the app's private storage. No PC, npm, server, login or internet permission is needed. Desktop progress is not included or synchronized. The phone starts with a fresh learning history.

Closing the app or restarting the phone preserves committed answers. Installing a later APK with the same package name/signing key preserves data. Uninstalling the app or clearing its storage removes progress. Android cloud backup is disabled; this version has no phone database export feature.

## Architecture

- Native Java `Activity` hosts the existing responsive interface in an Android WebView.
- A separate private WebView runs the **same `service.js` and `learning.js` as desktop**. Build preparation only adapts UUID and database access; the learning algorithm is copied unchanged.
- An asynchronous request/response bridge replaces HTTP on Android. The UI can request API operations but cannot issue SQL or read the hidden correct choice.
- The private engine's synchronous database adapter uses native `android.database.sqlite.SQLiteDatabase`, with transactions and WAL.
- WebViews load only packaged resources at `https://appassets.androidplatform.net`. External navigation, file access and content access are blocked. No Internet permission is requested.
- A clean seed database is generated with the original JSON importer at build time. Updates merge source/term data without replacing existing progress.

## Build again

From the project root, with Node 24+ and Java 17 installed:

```powershell
.\android\build.cmd
```

`build.cmd` invokes the trusted project PowerShell script with a process-local execution policy; it does not change your system policy. Alternatively run `android/build.ps1` in a PowerShell environment that already permits project scripts.

The build uses downloaded, checksum-verified Google tools under `android/tools/`:

- Android build-tools 35.0.0 (`build-tools/android-15/`)
- Android platform 35 revision 2 (`platform/android-35/`)
- Platform-tools (`adb/platform-tools/`) for optional verification

These tools are ignored by Git. If rebuilding from a source-only copy, download/extract the official archives to those paths first. The build does not require Gradle, Android Studio, npm packages or a cloud build service. The tool versions and archive URLs/checksums are recorded in `tools-manifest.json`. The bundled SDK tools contain their applicable NOTICE/license files.

Build steps: prepare UI/engine/seed assets, compile Android resources, compile Java, convert to DEX, assemble APK, align, sign, verify and produce the SHA-256 checksum.

**Keep `android/signing/` private and backed up.** It contains the personal signing key and its password file. Never distribute that folder with the APK. Future updates must use the same key. Increase `android:versionCode` and `android:versionName` in the manifest when publishing updates, and adjust the output filename in `build.ps1`.

## Verification

```powershell
node android/prepare.mjs
node --test tests/android.test.js
npm.cmd test
```

The Android contract tests run the actual packaged engine and adapter against SQLite using a native-bridge emulator in Node. They cover clean import, quiz generation, answer checking, duplicate submission, progress, summaries, filters, and random-practice isolation. They do not emulate the Android Java/WebView runtime.

The built APK has passed `apksigner verify` (v2/v3 signatures), `zipalign`, and manifest inspection. No Android phone was connected to the build machine; a real Android launch/touch/lifecycle test has not been performed. Firmware virtualization is disabled on this machine, so a hardware-accelerated emulator was unavailable. Device verification remains the important final compatibility check.

Official platform reference: [Android local WebView content](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content).
