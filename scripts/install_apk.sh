#!/usr/bin/env bash
# CareClose - швидке встановлення зібраного APK на підключені Android-пристрої або емулятори
set -euo pipefail

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -f "$APK_PATH" ]]; then
    echo "❌ APK не знайдено за шляхом $APK_PATH. Спочатку виконайте збірку: ./gradlew :app:assembleDebug"
    exit 1
fi

echo "🔍 Пошук підключених пристроїв через adb..."
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')

if [[ -z "$DEVICES" ]]; then
    echo "⚠️  Жодного активного пристрою або емулятора не знайдено."
    echo "Підключіть телефон через USB (увімкніть 'USB Debugging') або запустіть Android Emulator."
    exit 1
fi

for DEV in $DEVICES; do
    echo "📲 Встановлення на пристрій $DEV..."
    adb -s "$DEV" install -r "$APK_PATH"
    echo "✅ Успішно встановлено на $DEV!"
done

echo ""
echo "🎉 Готово! Додаток CareClose готовий до тестування."

