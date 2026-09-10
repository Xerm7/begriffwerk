# Android 1.0.2 — native service replacement

Install `releases/Begriffwerk-1.0.2.apk` over the existing app. Do not uninstall if you want to retain progress. The package name, SQLite schema/database filename, and signing key are unchanged. The app header now shows **ANDROID 1.0.2** and packaged files bypass WebView cache on load.

The 1.0.0/1.0.1 two-WebView implementation caused persistent startup timeouts on the user's Android 14 phone. Version 1.0.2 removes that architecture from the APK:

- `NativeActivity.java` hosts just one WebView for the existing UI.
- `QuizEngine.java` implements question selection, grading, mastery, sessions and statistics in Java.
- Native `SQLiteDatabase` persists progress; schema and source-import identities are unchanged.
- `direct-api.js` calls `AndroidApp.call` and receives JSON directly in the same native call. There is no engine-ready queue, timeout timer, second renderer, or JavaScript callback delivery.
- Errors return directly to the UI, tagged with 1.0.2. Startup database errors show a native dialog.
- Only interface assets and the clean JSON-derived seed are packaged. The previous engine assets and classes are not included. Source files for the previous implementation remain for reference but are unused.

Build: `android\build.cmd` from the project root.

Native validation: run `android/test-native.ps1` in a PowerShell that permits project scripts. It needs Java 17, Node 24, and the three test JARs already downloaded to `android/tools`: org.json 20240303, sqlite-jdbc 3.45.3.0, and slf4j-api 2.0.13. These test dependencies are not shipped in the APK. Android's built-in JSON and SQLite libraries are used on the phone.

The Java/SQLite test executes 100 learning questions and a free-practice question, checks six unique choices, hidden/randomized answer keys, independent directions, cooldown, duplicate submission, details, filtering, summary, progress after database reopen, and 40 step-by-step comparisons against the desktop mastery implementation. It completed **1,559 assertions**. APK signatures and alignment are verified by the build.

This tests the actual Java learning service using JDBC SQLite, not the Android WebView runtime. No Android device is connected; previous software-emulator attempts stalled before boot. Launch behavior on the user's phone still needs confirmation. We do not claim a verified device-level fix without that check.

Requires Android 8+ with an updated System WebView (modern JavaScript support). No PC or network needed. Desktop and phone histories remain separate. Uninstalling or clearing app data deletes phone progress.
