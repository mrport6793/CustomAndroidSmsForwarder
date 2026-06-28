# CustomAndroidSmsForwarder

A minimal, self-hosted Android app (Kotlin) that forwards incoming SMS to **email** (default, via the [Resend](https://resend.com) API) or to **another phone via SMS**. Built to run on a dedicated, plugged-in **Google Pixel 4a (Android 13)** as a set-and-forget forwarding box.

## Why build instead of using an app

- The good off-the-shelf forwarders have degraded (ads/subscriptions) and are closed source — you can't audit what they do with your texts, **including bank OTPs**.
- Building it yourself means the forwarding logic is **auditable source you control**, with the **only** outbound calls being the email API (email mode) or a plain SMS (phone mode) — no analytics, no accounts, no hidden backend.
- The Pixel 4a is close to ideal here: it's **out of OS support**, so the background-execution rules can never change out from under the app — the #1 cause of home-rolled forwarders silently breaking. Stock Pixel means no aggressive OEM battery-killer either.

> **Privacy note — email mode uses a third party.** Email is sent through Resend's API, so Resend's servers see each forwarded message (including OTP/2FA codes). That's a deliberate trade for a scoped, sending-only credential and proper HTTPS, instead of storing a full Gmail password on the device. If you want **no** third party in the path, use **SMS→SMS mode**, which goes phone→carrier with nothing in between. See [Security](#security-note).

## How it works (one sentence)

A manifest-declared `BroadcastReceiver` wakes on `SMS_RECEIVED` (this still works even when the app isn't running), hands the message to **WorkManager**, and a worker forwards it — an HTTPS `POST` to the Resend API (email mode) or `SmsManager` (SMS mode) — retrying with backoff if the network blips. Retries carry an idempotency key so a retried email never delivers twice. No persistent foreground service, no accounts, no server.

## Language & target

- **Kotlin** (this is a hard requirement — see `CLAUDE.md`)
- `minSdk` 24, target the Pixel 4a's **Android 13 (API 33)**; `compileSdk` 34+

## Install

Not eligible for the Play Store (Google restricts `RECEIVE_SMS` for this use case), so it's a sideloaded debug APK:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Play Protect will warn on install (it can't tell a user-configured forwarder from SMS-stealing malware) — choose **Install anyway**. This is expected for every app in this category.

## Setup

Open the app, grant the SMS + notification permissions, tap **Disable battery optimization**, then configure a mode and **Save**.

**Email mode (Resend):**
1. Create a free [Resend](https://resend.com) account — sign up with the inbox you want forwards to land in (e.g. your Proton/Gmail address).
2. Create a **sending-only** API key at [resend.com/api-keys](https://resend.com/api-keys).
3. In the app: paste the `re_…` key, leave **From** as `onboarding@resend.dev`, and set **Forward to** to your Resend account email.
   - The `onboarding@resend.dev` sandbox only delivers to **your own account email**. To forward elsewhere, verify a domain in Resend and use a `From` on it.

**SMS→SMS mode:** just enter the destination phone number. No third party — phone→carrier. (Carrier SMS charges may apply per text.)

**Filters (optional):** restrict by sender, required keyword, or body regex. Blank = forward everything.

## Repo layout

```
CustomAndroidSmsForwarder/
├── README.md          ← you are here
├── CLAUDE.md          ← original handoff spec for Claude Code
├── RESEARCH.md        ← survey of existing GitHub projects + technical findings
├── settings.gradle.kts, build.gradle.kts, gradle/   ← Gradle project
└── app/src/main/
    ├── AndroidManifest.xml
    └── java/dev/local/smsforwarder/
        ├── SmsReceiver.kt    ← manifest receiver → enqueues WorkManager job
        ├── ForwardWorker.kt  ← forwards with backoff retry
        ├── ResendSender.kt   ← email via Resend HTTPS API (idempotent)
        ├── SmsSender.kt      ← SMS→SMS via SmsManager
        ├── Prefs.kt          ← config in EncryptedSharedPreferences
        └── MainActivity.kt   ← one Compose settings screen
```

## Credits / prior art

Architecture and edge-case handling borrow from the open-source projects surveyed in `RESEARCH.md` — most directly **bogkonstantin/android_income_sms_gateway_webhook** (receiver → WorkManager → retry pattern). See `RESEARCH.md` for licenses and links.

## Security note

This app reads every SMS — **including 2FA/OTP codes** — so treat it and its destination as sensitive:

- **Secrets are encrypted at rest.** The Resend API key lives in `EncryptedSharedPreferences` (AES-256, key in the hardware-backed Android Keystore) — not readable even via `adb run-as` on the debug build.
- **Use a sending-only Resend key**, scoped to a single verified domain where possible. If the device is lost, that key can only *send* mail, not read your account.
- **Email mode trusts Resend.** Resend's servers see each forwarded message. For zero third parties in the path, use **SMS→SMS mode**.
- **Lock down the destination inbox** (strong unique password + 2FA) — it will receive your OTP codes.
- The app requests only `RECEIVE_SMS`, `SEND_SMS`, `INTERNET`, `POST_NOTIFICATIONS`. It deliberately does **not** request `READ_SMS` (no access to your existing inbox).
