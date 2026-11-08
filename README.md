# CareClose

Безсерверний парний Android-додаток для підтримки щоденного ритму, прийому ліків та харчування літньої людини з турботою та моніторингом з боку дорослого опікуна через спільну папку Google Drive.

## Статус проєкту

- [x] **Етап 1:** Каркас Gradle-проєкту, доменні моделі, рушій розкладу з якорями, оцінка телеметрії, інвентар аптечки, Room БД та unit-тести (`:core:model`, `:core:database`, `:app`).
- [x] **Етап 2:** Локальний рушій надійних будильників (`:core:alarms`: `AlarmManager.setAlarmClock`, `FullScreenAlarmActivity`, `AlarmRingingService`, ресивери після ребуту, `CaregiverCheckReceiver`).
- [x] **Етап 3:** Безсерверна синхронізація через Google Drive (`:core:sync`: `Service Account` + JWT + REST v3 + QR-пейринг + AES-GCM).
- [x] **Етап 4:** Інтерфейс «Літньої людини» (`:feature:elder`: головний екран, фото ліків, Glance-віджет) та онбординг (`:feature:onboarding`: QR-генератор/сканер CameraX+ZXing, чекліст дозволів).
- [x] **Етап 5:** Інтерфейс «Дорослого» (`:feature:caregiver`: дашборд, конструктор ліків із фото/записом голосу, аптечка, телеметрія пристрою) та інтеграція в `:app`.

## Архітектура модулів

- `:app` — точка входу, DI, тема Material 3 з підвищеним контрастом, навігація між ролями `ELDER` і `CAREGIVER`.
- `:core:model` — чистий Kotlin/JVM модуль: JSON-контракти, `DailyScheduleResolver` (підтримка `FIXED` і `RELATIVE_TO_ANCHOR`), `InventoryCalculator`, `PresenceEvaluator`.
- `:core:database` — локальна Room (SQLite) база даних, таблиці планів, подій, черги синхронізації (Outbox), кешу медіа, телеметрії та `DeviceConfigStore`.
- `:core:alarms` — будильники (`AlarmCoordinator`, `AlarmRingingService`, `ElderAlarmAudioPlayer`, `FullScreenAlarmActivity`), ресивери після ребуту/зміни часу та Predictive Window Polling для опікуна.
- `:core:sync` — `SyncTransport`, `GoogleDriveServiceAccountTransport` (JWT RS256 + REST API v3), `SyncEngine`, AES-256-GCM шифрування, QR-кодек та збір телеметрії `AndroidPresenceCollector`.
- `:feature:onboarding` — вибір ролі, підключення Google Drive, генератор/сканер QR-кодів (CameraX + ZXing) та інтерактивний чекліст дозволів.
- `:feature:elder` — адаптований інтерфейс підопічного (годинник, швидкий дзвінок, фокусна картка з фото та кружечками дози, якорі, 5-с скасування) і віджет Jetpack Glance.
- `:feature:caregiver` — панель опікуна (дашборд із телеметрією, редактор розкладу та якорів, фото таблеток, запис голосових нагадувань, облік залишків аптечки).

Докладніше:
- [Покрокове налаштування Google Drive та Service Account](docs/GOOGLE_DRIVE_SETUP.md)
- [Опис форматів JSON та структур обміну даними](docs/JSON_SCHEMAS.md)

## Збірка та запуск тестів

1. Відкрийте папку проєкту в **Android Studio (Ladybug+)** або встановіть JDK 17 + Android SDK (API 35).
2. Переконайтеся, що `local.properties` вказує на ваш Android SDK (`sdk.dir=...`).
3. Запустіть unit-тести всіх модулів та зберіть debug APK:
   ```bash
   ./gradlew :core:model:test :core:sync:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:caregiver:testDebugUnitTest :app:assembleDebug
   ```
4. Готовий APK створюється в `app/build/outputs/apk/debug/app-debug.apk`.

