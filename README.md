# CareClose

**CareClose** is a serverless paired Android application designed to support daily routines, medication intake, and nutrition for elderly individuals with compassionate care and monitoring by adult caregivers via a shared Google Drive folder.

---

## 🌟 Key Features

- **🧓 Elder Mode (Senior):**
  - High-contrast, adaptive UI with large fonts, distinct colors, and self-explanatory buttons.
  - Full-screen alarm over lock screen (`AlarmManager.setAlarmClock`).
  - **Do Not Disturb (DND) Bypass** and automatic volume elevation to a safe, audible level even if the phone is muted.
  - **Quick Return:** floating heart overlay button over all applications (YouTube, Viber, phone calls) and persistent Android notification shade entry showing the next scheduled task.
  - Audio reminder playback recorded by loved ones ("Mom, please take your blood pressure medication").
  - Medication visualization: pill/blister photos and graphical dosage indicators.
  - Single-button confirmation ("TAKEN / DONE") with accidental press protection (5-second cancellation countdown).
  - **Full-Screen Emergency Siren:** instant response to caregiver search signals with prominent buttons: "I AM HERE, ALL GOOD" and "CALL CAREGIVER".
  - One-tap quick emergency calling for relatives.
  - Jetpack Glance home screen widget.
  - **Caregiver PIN Protection:** settings entry secured by a 4-digit PIN code (with a 4-second hold fallback) to prevent accidental misconfiguration by the senior.

- **📱 Primary Caregiver Mode (Adult / Administrator):**
  - Flexible schedule builder: exact times, relative offsets after daily anchors (breakfast, lunch, dinner, bedtime), periodic intervals (every 2–4+ days or every N hours), and quick one-off task creation (pharmacy, doctor, groceries, etc.).
  - In-app medication photo capture and custom voice reminder recording.
  - **🚨 Emergency Tandem:** direct phone call (bypassing DND via "Starred Contacts") or remote search siren activation at 100% volume if the senior is unreachable.
  - **Elder Quick Return Management:** configurable floating button, notification shade, or both, with adjustable button size.
  - **Clean Schedule & Settings Separation:** senior profile settings, address forms, and quick-call contacts reside in "Settings", keeping the "Schedule" tab focused on timeline actions.
  - Smart medicine cabinet: inventory tracking with automatic deduction and replenishment alerts.
  - Telemetry monitoring: elder battery level, network connectivity status, and exact last active timestamp.
  - **Multi-Elder Support:** seamless one-tap switching between profiles ("Mom", "Dad", "Grandma") in the top app bar.
  - Smart alerts: notifications sent only when a deadline expires and the senior's device was recently online.

- **🤝 Secondary Caregiver Mode (Assistant / Neighbor / Nurse / Caregiver):**
  - Fast 10-second pairing via QR code from the primary caregiver.
  - Safe Read-Only Mode: prevents accidental edits to prescribed treatment regimens.
  - Daily schedule monitoring and intake progress overview.
  - Manual check-off with automated audit signatures (e.g., *“✓ Completed by nurse (Oksana)”* or *“✓ Checked by neighbor (Halyna)”*).
  - Quick emergency contact button for reaching the primary caregiver.

- **🌍 Bilingual & Grammatical Agreement:**
  - Full support for **Ukrainian** and **English** with runtime language switching or system default.
  - Gender and kinship awareness (Mom/Dad, Daughter/Son) with natural grammatical verb inflection.

- **🎨 Accessible Visual Themes:**
  - Standard Light and Dark themes.
  - **High Contrast Theme (WCAG AAA compliant):** deep dark surfaces with ultra-vivid yellow, neon lime, and cyan accents for maximum legibility.

- **🔒 Reliability & Privacy:**
  - 100% offline autonomy: all reminders trigger locally without active internet.
  - Serverless architecture: synchronized via Google Drive API with end-to-end AES-256-GCM encryption.
  - Conflict-Free Replicated Event Logs (CRDT per-device): caregiver and elder actions never overwrite or lose history.

---

## 🏛️ Module Architecture

- `:app` — Entry point, DI, Material 3 dynamic and high-contrast theming, active role selection (`ELDER`, `CAREGIVER`), lifecycle management.
- `:core:model` — Pure Kotlin/JVM module: domain models (`PlanSnapshot`, `TaskSpec`, `AnchorSpec`, `CaregiverRole`, `CaregiverAccessLevel`), schedule resolution (`DailyScheduleResolver`), medicine cabinet (`InventoryCalculator`), telemetry evaluation (`PresenceEvaluator`), linguistic engine (`CareCloseWording`), and localization (`CareCloseStrings`).
- `:core:database` — Room (SQLite) storage: schedule tables, CRDT events, outbox sync queue, media cache, and `DeviceConfigStore`.
- `:core:alarms` — Local system alarms: `AlarmCoordinator`, `AlarmRingingService`, `ElderAlarmAudioPlayer` (DND bypass, auto-volume), `FullScreenAlarmActivity` (medication cards and emergency siren), boot receivers, and Predictive Window Polling.
- `:core:sync` — Serverless transport layer: `GoogleDriveServiceAccountTransport` (RS256 JWT + Google Drive v3 REST API), QR generator/parser, AES-256-GCM encryption, device telemetry collection.
- `:feature:onboarding` — Setup wizard: role selection (Senior, Primary Caregiver, Assistant), kinship and gender selection, QR pairing, step-by-step system permission checklist (DND, overlay, battery optimization, exact alarms).
- `:feature:elder` — Senior interface: adaptive high-contrast screens, task view, medication photos, one-tap confirmations, floating heart bubble (`ElderFloatingBubbleService`), PIN-protected settings, and Jetpack Glance widget.
- `:feature:caregiver` — Caregiver dashboard: multi-profile switcher, status overview, emergency tandem call and siren triggers, quick return controls, timeline builder with camera and microphone, medicine cabinet, and QR sharing for assistants.

---

## 📚 Documentation

- [User Guide & Feature Walkthrough](docs/USER_GUIDE.md)
- [Google Drive & Service Account Setup](docs/GOOGLE_DRIVE_SETUP.md)
- [JSON Schemas & Data Exchange Specification](docs/JSON_SCHEMAS.md)
- [Українська версія README](readme-ua.md)

---

## 🛠️ System Requirements & Build

- **Minimum SDK:** Android 8.0 (API 26 — Oreo)
- **Target SDK:** Android 15 (API 35 — Vanilla Ice Cream)
- **Environment:** JDK 17+, Android Studio Ladybug+ / Gradle 8.10.2

### Build and Test via Gradle:

```bash
# Run unit tests across all modules:
./gradlew :core:model:test :core:sync:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:caregiver:testDebugUnitTest :feature:elder:testDebugUnitTest

# Assemble debug APK:
./gradlew :app:assembleDebug
```

The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 🚀 Repository & Publishing

To push updates to your GitHub repository:

```bash
# Verify remote configuration
git remote -v

# Push to main branch
git push origin main
```
