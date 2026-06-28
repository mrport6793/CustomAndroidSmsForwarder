# CLAUDE.md — build instructions for Claude Code

This file is the handoff spec. Read `RESEARCH.md` first for context and prior art. **The app is written in Kotlin.**

## Mission

Scaffold and build a minimal Android app, `CustomAndroidSmsForwarder`, that forwards incoming SMS to email (default) or to another phone via SMS. Target a dedicated **Pixel 4a on Android 13**. Then publish it as a **public** repo on the user's personal GitHub.

The `starter-src/` folder contains reference Kotlin source already written for this design. Integrate it into a proper Gradle project (don't just copy blindly — reconcile package names, versions, and Compose/theme boilerplate against a current Android Studio template).

## Hard requirements / scope guardrails

- **Language: Kotlin.** Not Java, not MAUI/Flutter/React Native.
- **Keep it minimal.** Email + SMS→SMS toggle, sender/keyword/regex filter, one settings screen. No analytics, no telemetry, no backend, no accounts, no call/notification forwarding.
- **Only network call is SMTP** (email mode). No other outbound traffic.
- Architecture: manifest `SMS_RECEIVED` receiver → WorkManager job → forward with backoff retry. **No persistent foreground service**, no `BOOT_COMPLETED` receiver needed for the core path (WorkManager persists across reboots).

## Project setup

1. Create a standard single-module Android app (Empty Compose Activity template is fine).
   - `applicationId` / package: `dev.local.smsforwarder` (matches starter source; change consistently if you prefer).
   - `minSdk = 24`, `targetSdk = 34` (or current), `compileSdk = 34` (or current).
   - Generate the **Gradle wrapper** (`gradle wrapper`) so `./gradlew` works.
2. Move `starter-src/AndroidManifest.xml` → `app/src/main/AndroidManifest.xml` (merge in the template's application/theme attributes).
3. Move `starter-src/java/dev/local/smsforwarder/*.kt` → `app/src/main/java/dev/local/smsforwarder/`.
4. Add dependencies (verify/upgrade to current stable — these are starting points, generated without a build):
   ```kotlin
   // app/build.gradle.kts
   dependencies {
       implementation("androidx.work:work-runtime-ktx:2.9.1")
       implementation("com.sun.mail:android-mail:1.6.7")
       implementation("com.sun.mail:android-activation:1.6.7")
       // Compose (use the BOM the template provides):
       implementation(platform("androidx.compose:compose-bom:2024.09.00"))
       implementation("androidx.activity:activity-compose:1.9.2")
       implementation("androidx.compose.material3:material3")
       implementation("androidx.compose.ui:ui")
   }
   ```
5. Generate any missing `res/` (strings, theme) the template expects. The settings screen in `MainActivity.kt` is intentionally barebones — flesh out validation and layout as needed, but keep it one screen.

## Build & deploy

```bash
./gradlew assembleDebug
adb devices                 # confirm the Pixel 4a is connected (USB debugging on)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
A self-signed **debug** APK is fine for personal use — no Play signing, no developer account.

## On-device verification checklist (the part only a human + real phone can do)

The user must do this loop; you can't text the phone yourself. Tell them to:
1. Open the app, grant SMS (+ notification) permissions, set destination (email or number) and any filter, save.
2. Disable battery optimization for the app when prompted.
3. Send the phone a test SMS → confirm it arrives at the destination.
4. **Swipe the app away** from recents, send another → confirm it still forwards (proves the manifest receiver works while stopped).
5. **Reboot** the phone, send another → confirm forwarding resumes with no manual launch (proves WorkManager persistence).
6. If on Google Messages, confirm RCS is off so messages arrive as SMS (see RESEARCH.md §3.3).

## Gotchas to handle in code (details in RESEARCH.md §3)

- Concatenate **multipart** SMS bodies.
- `SmsManager` via `getSystemService(SmsManager::class.java)` on API 31+.
- Runtime-request `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS` (SMS mode), `POST_NOTIFICATIONS` (API 33).
- Email: Gmail **App Password**, `smtp.gmail.com:587` STARTTLS; never store the user's main password.
- Expect Play Protect to flag the install (false positive) — document "Install anyway" in the README.

## Create the public GitHub repo (run from the project root)

The user wants this in `~/Documents/GitHub/CustomAndroidSmsForwarder` as a **public** repo on their **personal** account. Requires the GitHub CLI authenticated as them (`gh auth status`). Confirm the account is the personal one before pushing.

```bash
cd ~/Documents/GitHub/CustomAndroidSmsForwarder
git init
git add .
git commit -m "Initial commit: Kotlin SMS forwarder (email + SMS), spec + scaffold"
gh repo create CustomAndroidSmsForwarder --public --source=. --remote=origin --push
```

> Publishing a public repo and any auth are deliberately left to run on the user's machine with their credentials — do not hardcode tokens. If `gh` isn't installed/authed, stop and ask the user to run `gh auth login` first.

## Note from the planning step

This spec and `starter-src/` were prepared in a sandbox **without an Android build environment**, so dependency versions and Compose boilerplate are best-effort, not build-verified. Your job: scaffold against a current template, get `./gradlew assembleDebug` green, and fix any version/import drift before handing back.
