# Research — Android SMS Forwarder (Kotlin)

Compiled to give Claude Code full context before scaffolding. Goal: a minimal Kotlin app that forwards incoming SMS to **email** (default) or to **another phone**, running set-and-forget on a dedicated **Pixel 4a (Android 13)**.

---

## 1. What already exists on GitHub

There are many SMS-forwarder repos. None is a perfect drop-in for "Kotlin + email + dead simple," but several are excellent references. Ranked by usefulness to this project:

### Primary reference — reliability/architecture
**`bogkonstantin/android_income_sms_gateway_webhook`** — ~610★, ~168 forks, actively maintained (commits within weeks), public, on F-Droid.
- Forwards incoming SMS → **URL (JSON POST)** and **Telegram**. Not email, but the *plumbing* is exactly what we want.
- Does the hard parts right: **WorkManager with exponential-backoff retry**, optional **persistence of messages that exhaust retries** (manual re-send), **regex/sender filtering**, optional **heartbeat ping** (dead-man's-switch), built-in test sender + error log.
- No cloud, no accounts; destination is whatever you configure.
- Its README is the best plain-English documentation of the category's gotchas (RCS, Play Protect, Android 13 notification permission) — all captured in §3 below.
- **Use it for:** the receiver → WorkManager → retry skeleton, filtering, and failure handling. Swap the "POST to URL" destination for our email/SMS sender.

### Primary reference — email/SMTP + feature completeness
**`pppscn/SmsForwarder`** (package `com.idormy.sms.forwarder`) — very large, very popular.
- Forwards SMS / incoming calls / app notifications → DingTalk, WeCom, Feishu, **Email (SMTP)**, Bark, Webhook, Telegram, ServerChan, PushPlus, **SMS**, and more. Dual-SIM, rule-based filtering, retry-5×.
- Chinese-centric, feature-heavy, mixed Java/Kotlin — **too much to fork wholesale**, but the canonical reference for **how to do email (JavaMail/SMTP) and SMS→SMS** on Android.
- License/usage: explicitly "test, study, research only." Treat as reference, not code to copy verbatim.
- A Kotlin-leaning variant exists: **`TianLiangZhou/SmsForwarder-Kotlin`** — useful if you want the same feature set expressed in Kotlin.

### Clean minimal references
- **`pierreduchemin/smsforward`** — free, OSS, no ads, Android 5.0+ (API 21+), **MVVM** architecture. Redirects SMS to another device. Good clean structural reference for a small app.
- **`EnixCoda/SMS-Forward`** — simple **SMS↔SMS**, including *bidirectional* control (text the phone `to {number}:` + body to make it send). Nice minimal reference for the phone-to-phone path. Build-from-source encouraged.
- **`nimblehq/sms-forwarder`** — forwards SMS → **email or Slack webhook**, sender/regex filtering, with an admin dashboard for remote config (2021, by Nimble). The dashboard/server piece is more than we need, but it's another email + filtering reference.

### Takeaway
Build our own minimal Kotlin app. Borrow:
- **Reliability skeleton** (receiver → WorkManager → backoff retry → optional failed-store) from `bogkonstantin`.
- **Email/SMTP** approach (JavaMail) from `pppscn` / `nimblehq`.
- **Small-app structure** (MVVM-ish, one settings screen) from `pierreduchemin`.

Scope stays tiny: email primary + SMS→SMS toggle, sender/keyword/regex filter, one settings screen. No analytics, no backend, no notification listening, no call forwarding.

---

## 2. Recommended architecture

```
Incoming SMS
   │
   ▼
SmsReceiver  (manifest-declared BroadcastReceiver on android.provider.Telephony.SMS_RECEIVED)
   │   - parses sender + body (concatenates multipart)
   │   - enqueues a one-time WorkManager job (sender, body)
   ▼
ForwardWorker  (CoroutineWorker)
   │   - reads saved config (Prefs)
   │   - applies filter (allowed senders / keyword / regex)  → drop if no match
   │   - forwards:
   │        EMAIL mode → EmailSender (JavaMail SMTP, Gmail App Password, STARTTLS)
   │        SMS   mode → SmsSender   (SmsManager.sendMultipartTextMessage)
   │   - on failure: Result.retry() up to N attempts, else Result.failure()
   ▼
Done
```

Why this shape:
- A **manifest-declared** receiver for `SMS_RECEIVED` is one of the few broadcasts that still wakes a **stopped** app, so we need **no persistent foreground service** (less battery, fewer Android 13/14 restrictions to fight).
- **WorkManager** persists its job queue **across reboots** and handles retry/backoff, so we also need **no BOOT_COMPLETED receiver** for the core path.
- Net result on a frozen-OS, plugged-in Pixel 4a: about the most durable setup possible.

---

## 3. Technical findings & gotchas (must-handle)

1. **Not Play-Store eligible.** Google's SMS/Call-Log policy only allows `RECEIVE_SMS` for a short list of approved roles (default SMS app, etc.). "Forward incoming SMS to a URL/email" isn't one. → Distribute via **sideload / F-Droid**, not Play.

2. **Play Protect will flag the install** as a potential SMS-stealer. It's an automated classifier and can't tell that *you* configured the destination. Expected **false positive** → "Install anyway."

3. **RCS blind spot (important).** Google Messages may deliver chats as **RCS**, which does **not** fire the `SMS_RECEIVED` broadcast, and Android exposes **no public API** for third-party apps to read RCS. Only true SMS will be forwarded. Mitigation on the forwarding phone: it likely has no RCS conversations anyway, but to be safe **turn off RCS chat features** in the Messages app so everything arrives as SMS.

4. **Android 13 notification permission.** `POST_NOTIFICATIONS` is runtime-requested on API 33 (the Pixel 4a). Forwarding works without it, but any status/"listening" indicator needs it.

5. **Battery optimization.** For prompt delivery, request the battery-optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + the system prompt, or send the user to Settings). On a **plugged-in, dedicated** phone Doze barely engages, so this is belt-and-suspenders.

6. **Multipart SMS.** Long messages arrive as multiple `SmsMessage` parts in the intent — **concatenate** the bodies (`getMessagesFromIntent` → join `displayMessageBody`).

7. **SmsManager API level.** On API 31+ obtain it via `context.getSystemService(SmsManager::class.java)`; `SmsManager.getDefault()` is deprecated (still works). Use `divideMessage` + `sendMultipartTextMessage` for outbound.

8. **Runtime permissions.** `RECEIVE_SMS`, `READ_SMS`, and (SMS mode) `SEND_SMS` are dangerous-level and must be requested at runtime, not just declared.

9. **Email path.** Android has no nice built-in SMTP client. Use **JavaMail for Android** (`com.sun.mail:android-mail` + `com.sun.mail:android-activation`). Gmail requires an **App Password** (with 2FA on the account); connect to `smtp.gmail.com:587` with STARTTLS (or `:465` SSL). Run on a background thread — it already does, inside `ForwardWorker.doWork()`.

---

## 4. Pixel 4a specifics

- **Android 13, no further OS updates** → the background-execution rules are a **frozen target**. The most common reason a DIY forwarder eventually dies (an OS update changing background/broadcast rules) simply can't happen here.
- **Stock Pixel** → respects the *standard* battery-optimization exemption; **no proprietary OEM task-killer** (unlike Samsung/Xiaomi) to fight.
- **Recommended deployment:** dedicate the phone, keep it **plugged in on Wi-Fi**. Doze stays minimal while charging, and battery aging becomes irrelevant — worth noting given the forced battery-throttling some 4a units received.

---

## 5. Security / trust

> **Implementation update:** the email path ships using the **Resend HTTPS API**, not direct Gmail SMTP/JavaMail. The notes below are updated to match. (Gmail SMTP via JavaMail remains a valid alternative if you want zero third parties for email — at the cost of storing a Gmail app password on-device and handling STARTTLS hardening yourself.)

- Self-hosted = **auditable**: the forwarding logic is yours to read, with no analytics/accounts/hidden backend. The *only* outbound calls are the Resend API (email mode) or a plain SMS (phone mode).
- **Email mode adds one third party.** Messages are POSTed to Resend over HTTPS, so Resend's servers see each forwarded text (incl. OTPs). Mitigations: a **sending-only, domain-restricted API key** (smaller blast radius than a full mail password), proper TLS cert validation by default (no STARTTLS-stripping risk), and an **idempotency key** so WorkManager retries can't double-send. For **no** third party in the path, use **SMS→SMS mode** (phone→carrier).
- **Secrets encrypted at rest:** the API key is stored in `EncryptedSharedPreferences` (AES-256, key in the Android Keystore), unreadable even via `adb run-as` on the debug build.
- This still reads **OTP/2FA** texts. Treat the destination mailbox/number as sensitive and lock down that account (strong unique password, 2FA).
- **Least privilege:** the app requests `RECEIVE_SMS` + `SEND_SMS` only — **not** `READ_SMS` — so it never has access to your existing inbox, only newly-arriving messages.

---

## 6. Reference links

- bogkonstantin/android_income_sms_gateway_webhook — https://github.com/bogkonstantin/android_income_sms_gateway_webhook
- pppscn/SmsForwarder — https://github.com/pppscn/SmsForwarder
- TianLiangZhou/SmsForwarder-Kotlin — https://github.com/TianLiangZhou/SmsForwarder-Kotlin
- pierreduchemin/smsforward — https://github.com/pierreduchemin/smsforward
- EnixCoda/SMS-Forward — https://github.com/EnixCoda/SMS-Forward
- nimblehq/sms-forwarder — https://github.com/nimblehq/sms-forwarder
- GitHub topic (browse more) — https://github.com/topics/sms-forwarder
