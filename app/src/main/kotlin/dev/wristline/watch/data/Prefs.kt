package dev.wristline.watch.data

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Pairing and user settings. Stored in app-private SharedPreferences, which are excluded from
 * backup and device transfer (see res/xml/data_extraction_rules.xml).
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wristline", Context.MODE_PRIVATE)
    private val defaultDeviceName =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    /** `https://host[:port]`, set once an address has been entered (even before pairing). */
    val baseUrl: String get() = sp.getString(KEY_BASE_URL, null).orEmpty()
    val token: String get() = sp.getString(KEY_TOKEN, null).orEmpty()
    val deviceId: String get() = sp.getString(KEY_DEVICE_ID, null).orEmpty()
    val isPaired: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    /** Name the bridge lists this watch under; sent when pairing. */
    var deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, null) ?: defaultDeviceName
        set(value) = sp.edit().putString(KEY_DEVICE_NAME, value).apply()

    /** Background monitoring requested by the user (the service itself arrives in Phase 5). */
    var monitoring: Boolean
        get() = sp.getBoolean(KEY_MONITORING, false)
        set(value) = sp.edit().putBoolean(KEY_MONITORING, value).apply()

    fun saveAddress(baseUrl: String) = sp.edit().putString(KEY_BASE_URL, baseUrl).apply()

    /** [deviceId] is empty for a token entered by hand. */
    fun savePairing(baseUrl: String, token: String, deviceId: String) =
        sp.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_TOKEN, token)
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()

    fun clear() = sp.edit().clear().apply()

    private companion object {
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_DEVICE_NAME = "deviceName"
        const val KEY_MONITORING = "monitoring"
    }
}
