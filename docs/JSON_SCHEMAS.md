# CareClose — JSON-схеми та структура папки Google Drive

Усі файли на Google Drive живуть у спільній папці `CareClose_Family/`.  
Принцип уникнення конфліктів: **кожен пристрій записує лише у власний потік файлів**.

## Дерево файлів

```text
CareClose_Family/
 ├── manifest.json
 ├── schedules/
 │    ├── active_pointer.json
 │    └── plan_v14.json
 ├── media/
 │    ├── med_thyroxine_pill.jpg
 │    └── voice_morning_med.m4a
 ├── events/
 │    ├── device_elder_01/
 │    │    └── 2026-11-08.jsonl
 │    └── device_caregiver_01/
 │         └── 2026-11-08.jsonl
 └── presence/
      └── device_elder_01.json
```

## 1. `manifest.json`

```json
{
  "schemaVersion": 1,
  "familyId": "fam_9d81a2",
  "familyName": "Мама",
  "timezone": "Europe/Kyiv",
  "createdAtUtc": "2026-11-08T10:00:00Z"
}
```

## 2. `schedules/active_pointer.json` та `schedules/plan_vN.json`

`active_pointer.json`:
```json
{
  "planVersion": 14,
  "fileName": "plan_v14.json",
  "updatedAtUtc": "2026-11-08T10:05:00Z",
  "sha256": "e3b0c44298fc1c149afbf4c8996fb924..."
}
```

`plan_v14.json`:
```json
{
  "schemaVersion": 1,
  "planVersion": 14,
  "effectiveFromUtc": "2026-11-08T00:00:00Z",
  "timezone": "Europe/Kyiv",
  "recipientRole": "MOTHER",
  "recipientName": "Мама",
  "caregiverRole": "DAUGHTER",
  "caregiverPhone": "+380501234567",
  "quickReturnMode": "BOTH",
  "emergencyContact": {
    "name": "Олексій (Син)",
    "phone": "+380509876543"
  },
  "anchors": [ ... ],
  "tasks": [ ... ]
}
```

### Режими розкладу завдань (`schedule`):
- `FIXED` — Щоденний прийом у фіксований час:
  `{"mode": "FIXED", "time": "08:30", "daysOfWeek": [1,2,3,4,5,6,7]}`
- `RELATIVE_TO_ANCHOR` — Відносний час після підтвердженої події дня (сніданок, обід):
  `{"mode": "RELATIVE_TO_ANCHOR", "anchorId": "anchor_breakfast", "offsetMinutes": 20}`
- `ONE_OFF` — Одноразове нагадування на конкретну дату (магазин, аптека, лікар):
  `{"mode": "ONE_OFF", "date": "2026-11-09", "time": "14:30"}`
- `EVERY_N_DAYS` — Інтервальне повторення кожні 2–4+ дні (уколи, пластирі, перев'язки):
  `{"mode": "EVERY_N_DAYS", "time": "09:00", "intervalDays": 3, "startDate": "2026-11-08"}`

### Режими швидкого повернення (`quickReturnMode`):
- `BOTH` — Плаваюча кнопка поверх вікон + Закріплене сповіщення у шторці (Рекомендовано).
- `FLOATING_BUBBLE_ONLY` — Тільки плаваюча кнопка поверх вікон.
- `ONGOING_NOTIFICATION_ONLY` — Тільки закріплене сповіщення у шторці.
- `DISABLED` — Вимкнено (звичайний запуск підопічним з іконки додатку).

### Примітка щодо `RELATIVE_TO_ANCHOR`
- Позитивний зсув (`offsetMinutes >= 0`, наприклад `+20` після сніданку або `0` під час їжі) планується автоматично в момент, коли літня людина підтвердила відповідний якір (`ANCHOR_CONFIRMED`).
- Для ліків «до їжі» рекомендується використовувати або окремий якір «Прокинувся» (`offsetMinutes >= 0`), або фіксований режим `FIXED`.

## 3. `events/{deviceId}/{YYYY-MM-DD}.jsonl`

Формат **JSON Lines** (один рядок = одна подія). Порядок відновлення стану: дедуплікація за `eventId`, сортування за `(occurredAtUtc, eventId)`.

- `ANCHOR_CONFIRMED` — підтвердження прийому їжі або пробудження.
- `TAKEN` — літня людина підтвердила прийом/виконання.
- `TAKEN_UNDONE` — скасування попереднього натискання (`targetEventId` вказує на скасовану подію).
- `SNOOZED` — відкладено до `snoozeUntilUtc`.
- `SKIPPED` — свідомо пропущено.
- `CAREGIVER_OVERRIDE_TAKEN` — дорослий відмітив виконання зі свого пристрою («я поруч»).
- `EMERGENCY_PING` — опікун дистанційно активував пошукову сирену на пристрої підопічного.
- `EMERGENCY_PING_DISMISSED` — підопічний або опікун підтвердив «Я тут, усе гаразд» / зупинив сирену.

## 4. `presence/{deviceId}.json`

Оновлюється власником пристрою при кожному успішному циклі синхронізації або значній зміні заряду/гучності.
Дорослий розрізняє статус `MISSED_ONLINE` (телефон підопічного був онлайн нещодавно, але реакції немає) та `MISSED_DEVICE_OFFLINE` (телефон давно не виходив на зв'язок).

