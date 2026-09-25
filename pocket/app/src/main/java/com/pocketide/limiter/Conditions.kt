package com.pocketide.limiter

import android.provider.Settings
import com.pocketide.model.PhoneSnapshot

/** The phone maker's skin, which decides where its own battery killer hides. */
enum class Vendor { COLOR_OS, MIUI, ONE_UI, VIVO, HUAWEI, OTHER }

/** The banners of §11: conditions Android or the phone maker imposes, each with its fix. */
object ConditionRules {
    const val BATTERY_RESTRICTED = "battery-restricted"
    const val STANDBY_RESTRICTED = "standby-restricted"
    const val OEM_BATTERY = "oem-battery"
    const val DATA_SAVER = "data-saver"
    const val BATTERY_SAVER = "battery-saver"

    /** UsageStatsManager.STANDBY_BUCKET_RESTRICTED (Android 11+; 8 days without use on 13+). */
    const val RESTRICTED_BUCKET = 45

    fun vendor(manufacturer: String, brand: String): Vendor {
        val name = "${manufacturer.lowercase()} ${brand.lowercase()}"
        return when {
            listOf("oppo", "realme", "oneplus").any { it in name } -> Vendor.COLOR_OS
            listOf("xiaomi", "redmi", "poco").any { it in name } -> Vendor.MIUI
            "samsung" in name -> Vendor.ONE_UI
            listOf("vivo", "iqoo").any { it in name } -> Vendor.VIVO
            listOf("huawei", "honor").any { it in name } -> Vendor.HUAWEI
            else -> Vendor.OTHER
        }
    }

    fun conditions(snapshot: PhoneSnapshot, vendor: Vendor, oemStepDone: Boolean): List<Condition> {
        if (snapshot.at == 0L) return emptyList()
        return buildList {
            if (snapshot.backgroundRestricted) add(batteryRestricted())
            else if (snapshot.standbyBucket == RESTRICTED_BUCKET) add(standbyRestricted())
            if (!oemStepDone) oemStep(vendor)?.let(::add)
            if (snapshot.dataSaver) add(dataSaver())
            if (snapshot.powerSave) add(batterySaver())
        }
    }

    private fun batteryRestricted() = Condition(
        id = BATTERY_RESTRICTED,
        title = "Battery use is set to Restricted",
        explanation = "Android stops PocketIDE's work as soon as you leave the app, so agents and sync pause. " +
            "Set PocketIDE's battery use to Unrestricted or Optimised.",
        fixLabel = "Open battery settings",
        fixIntentAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    )

    private fun standbyRestricted() = Condition(
        id = STANDBY_RESTRICTED,
        title = "Android is rationing PocketIDE",
        explanation = "After days without use, Android lets PocketIDE work in the background only about once a day, " +
            "even while charging. Using the app lifts it; Unrestricted battery use keeps it lifted.",
        fixLabel = "Open app settings",
        fixIntentAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    )

    private fun dataSaver() = Condition(
        id = DATA_SAVER,
        title = "Data Saver is on",
        explanation = "On mobile data, Android blocks PocketIDE in the background, so sync waits for Wi-Fi. " +
            "Allow PocketIDE unrestricted data if you want it to sync on mobile data too.",
        fixLabel = "Allow data use",
        fixIntentAction = Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
    )

    private fun batterySaver() = Condition(
        id = BATTERY_SAVER,
        title = "Battery Saver is on",
        explanation = "Agents keep running while you use them, but background sync may be late. " +
            "It catches up when Battery Saver is off or the phone charges.",
        fixLabel = "Open Battery Saver",
        fixIntentAction = Settings.ACTION_BATTERY_SAVER_SETTINGS,
    )

    /** The one-time guided step for skins that stop apps on their own, in the skin's own words. */
    private fun oemStep(vendor: Vendor): Condition? {
        val (maker, steps) = when (vendor) {
            Vendor.COLOR_OS -> "realme, OPPO and OnePlus" to
                "Open Settings › Battery › App battery management › PocketIDE. Turn on Allow auto-launch and " +
                "Allow background activity, and choose Don't optimise. Also make sure PocketIDE is not in App quick freeze."
            Vendor.MIUI -> "Xiaomi, Redmi and POCO" to
                "Open Settings › Apps › Manage apps › PocketIDE. Turn on Autostart, and set Battery saver to No restrictions."
            Vendor.ONE_UI -> "Samsung" to
                "Open Settings › Apps › PocketIDE › Battery and choose Unrestricted. Make sure PocketIDE is not in " +
                "Sleeping apps or Deep sleeping apps."
            Vendor.VIVO -> "vivo and iQOO" to
                "Open i Manager › App manager › Autostart manager and allow PocketIDE."
            Vendor.HUAWEI -> "Huawei and Honor" to
                "Open Settings › Apps › App launch › PocketIDE. Choose Manage manually and turn on Auto-launch, " +
                "Secondary launch and Run in background."
            Vendor.OTHER -> return null
        }
        return Condition(
            id = OEM_BATTERY,
            title = "One step for $maker phones",
            explanation = "These phones close apps in the background on their own, which would stop your agents mid-task. $steps",
            fixLabel = "Open battery settings",
            fixIntentAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        )
    }
}
