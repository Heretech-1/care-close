package com.careclose.core.model.engine

import com.careclose.core.model.AppLanguage
import java.time.format.DateTimeFormatter
import java.util.Locale

object CareCloseStrings {

    val ukLocale = Locale.forLanguageTag("uk-UA")
    val enLocale = Locale.US

    fun getLocale(lang: AppLanguage): Locale = when (lang) {
        AppLanguage.ENGLISH -> enLocale
        AppLanguage.UKRAINIAN -> ukLocale
        AppLanguage.SYSTEM -> {
            val defaultLang = Locale.getDefault().language
            if (defaultLang.startsWith("uk")) ukLocale else enLocale
        }
    }

    // Tabs
    fun tabToday(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Today" else "Сьогодні"
    fun tabSchedule(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Schedule" else "Розклад"
    fun tabInventory(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Medicine Kit" else "Аптечка"
    fun tabSettings(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Settings" else "Налаштування"

    // Elder screen
    fun elderMarkWhenStart(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Mark when you start:" else "Відмітьте, коли почнете:"
    fun elderUpcomingToday(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Upcoming today" else "Ще заплановано на сьогодні"
    fun elderCompletedToday(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Completed today" else "Вже зроблено сьогодні"
    fun elderAllDone(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "All tasks completed!" else "Всі справи виконано!"
    fun elderAllDoneSub(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Rest and take care. You're doing great." else "Відпочивайте та бережіть себе. Чудова робота."
    fun elderRemind10Min(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "REMIND IN 10 MIN" else "НАГАДАТИ ЧЕРЕЗ 10 ХВ"
    fun elderIStarted(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "I have started" else "Я почав/почала"
    fun elderContactCaregiver(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Contact caregiver to update this task schedule" else "Зверніться до опікуна для оновлення розкладу цієї справи"
    fun elderAlarmsPermissionNeeded(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Alarm permissions required" else "Потрібно увімкнути дозволи для будильників"
    fun elderEnable(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Enable" else "Увімкнути"
    fun elderUndo(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Undo" else "Скасувати"

    // Elder Service gate
    fun elderServiceSettingsProtected(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "CareClose · Caregiver Settings" else "CareClose · Налаштування опікуна"
    fun elderEnterCaregiverPin(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Enter caregiver PIN (default: 1234):" else "Введіть PIN-код опікуна (за замовчуванням: 1234):"
    fun elderPinIncorrect(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Incorrect PIN" else "Невірний PIN-код"
    fun elderConfirm(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Confirm" else "Підтвердити"
    fun elderCancel(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Cancel" else "Скасувати"
    fun elderHoldToUnlock(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Hold 4 sec for emergency unlock" else "Утримуйте 4 сек для аварійного входу"

    // Day periods
    fun periodMorning(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Morning" else "Ранок"
    fun periodAfternoon(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Day" else "День"
    fun periodEvening(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Evening" else "Вечір"
    fun periodNight(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Night" else "Ніч"

    // Caregiver screen
    fun lowStockWarning(lang: AppLanguage, count: Int) = if (lang == AppLanguage.ENGLISH) "Medication running low ($count)" else "Закінчуються ліки ($count)"
    fun toMedicineKit(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Medicine Kit →" else "Аптечка →"
    fun todayButton(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Today" else "Сьогодні"
    fun noTasksScheduled(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "No tasks scheduled for this day" else "На цей день немає запланованих справ"
    fun noTasksInFilter(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "No tasks in this category" else "У цій категорії немає завдань"

    // Filter labels
    fun filterAll(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "All" else "Всі"
    fun filterMedication(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "💊 Meds" else "💊 Ліки"
    fun filterRoutine(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "📋 Routine" else "📋 Рутина"
    fun filterOneOff(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "⏰ One-off" else "⏰ Одноразові"

    // Buttons in Schedule
    fun btnAddTask(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "+ Task / Meds" else "+ Справа / ліки"
    fun btnAddOneOff(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "⏰ One-off" else "⏰ Одноразове"
    fun anchorsHeader(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Daily Anchors (Meals / Wakeup)" else "Якорі дня (їжа / підйом)"
    fun addAnchor(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "+ Add Anchor" else "+ Додати якір"
    fun tasksHeader(lang: AppLanguage, versionInfo: String) = if (lang == AppLanguage.ENGLISH) "Tasks & Medications (schedule: $versionInfo)" else "Ліки та справи (графік: $versionInfo)"

    // Settings
    fun languageSettingTitle(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Interface Language" else "Мова інтерфейсу"
    fun themeSettingTitle(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "App Theme" else "Тема додатку"
    fun caregiverPinSettingTitle(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Caregiver Security PIN (Elder lock)" else "PIN-код захисту опікуна (на телефоні підопічного)"
    fun caregiverPinHint(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "4-digit PIN to access settings from elder screen" else "4-значний PIN для входу в налаштування з додатку підопічного"
    fun save(lang: AppLanguage) = if (lang == AppLanguage.ENGLISH) "Save" else "Зберегти"
}
