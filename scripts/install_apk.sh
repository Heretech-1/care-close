#!/usr/bin/env bash
# CareClose - швидке встановлення зібраного APK на підключені Android-пристрої або емулятори
set -euo pipefail

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -f "$APK_PATH" ]]; then
    echo "❌ APK не знайдено за шляхом $APK_PATH. Спочатку виконайте збірку: ./gradlew :app:assembleDebug"
    exit 1
fi

# Автоматичний пошук adb у системі або через local.properties
ADB_BIN="adb"
if ! command -v adb &> /dev/null; then
    if [[ -f "local.properties" ]]; then
        SDK_DIR=$(grep '^sdk.dir=' local.properties | cut -d'=' -f2-)
        if [[ -f "$SDK_DIR/platform-tools/adb" ]]; then
            ADB_BIN="$SDK_DIR/platform-tools/adb"
        fi
    elif [[ -f "$HOME/.cache/careclose-toolchain/android-sdk/platform-tools/adb" ]]; then
        ADB_BIN="$HOME/.cache/careclose-toolchain/android-sdk/platform-tools/adb"
    fi
fi

if ! command -v "$ADB_BIN" &> /dev/null && [[ ! -f "$ADB_BIN" ]]; then
    echo "❌ adb не знайдено. Переконайтеся, що Android SDK встановлено і adb додано до PATH."
    exit 1
fi

# Захист для середовищ з read-only домашньою директорією
if [[ ! -w "$HOME" ]]; then
    export HOME="/tmp"
    export ANDROID_USER_HOME="/tmp/.android"
fi

echo "🔍 Пошук підключених пристроїв через adb ($ADB_BIN)..."
DEVICES=$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}')

if [[ -z "$DEVICES" ]]; then
    echo "⚠️  Жодного активного пристрою або емулятора не знайдено."
    echo "Підключіть телефон через USB (увімкніть 'USB Debugging') або запустіть Android Emulator."
    exit 1
fi

for DEV in $DEVICES; do
    echo "📲 Встановлення на пристрій $DEV..."
    "$ADB_BIN" -s "$DEV" install -r "$APK_PATH"
    echo "✅ Успішно встановлено на $DEV!"
done

echo ""
echo "🎉 Готово! Додаток CareClose готовий до тестування."

