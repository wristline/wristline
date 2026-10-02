package dev.wristline.watch.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pairing and user settings. Stored in app-private SharedPreferences, which are excluded from
 * backup and device transfer (see res/xml/data_extraction_rules.xml).
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wristline", Context.MODE_PRIVATE)
    private val defaultDeviceName =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    /**
     * `https://host[:port]`, set once an address has been entered before pairing; while paired it
     * changes only together with the token ([savePairing]), never on its own.
     */
    val baseUrl: String get() = sp.getString(KEY_BASE_URL, null).orEmpty()
    val token: String get() = sp.getString(KEY_TOKEN, null).orEmpty()
    val deviceId: String get() = sp.getString(KEY_DEVICE_ID, null).orEmpty()
    val isPaired: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    /** Name the bridge lists this watch under; sent when pairing. */
    var deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, null) ?: defaultDeviceName
        set(value) = sp.edit().putString(KEY_DEVICE_NAME, value).apply()

    private val monitoringFlow = MutableStateFlow(sp.getBoolean(KEY_MONITORING, false))

    /** Background monitoring requested by the user; MonitorService runs while this is on. */
    val monitoringState: StateFlow<Boolean> = monitoringFlow.asStateFlow()
    var monitoring: Boolean
        get() = monitoringFlow.value
        set(value) {
            monitoringFlow.value = value
            sp.edit().putBoolean(KEY_MONITORING, value).apply()
        }

    private val watchColorsFlow = MutableStateFlow(sp.getBoolean(KEY_WATCH_COLORS, false))

    /** The theme follows the watch's dynamic colors where it has them; off by default (graphite and blue). */
    val watchColorsState: StateFlow<Boolean> = watchColorsFlow.asStateFlow()
    var watchColors: Boolean
        get() = watchColorsFlow.value
        set(value) {
            watchColorsFlow.value = value
            sp.edit().putBoolean(KEY_WATCH_COLORS, value).apply()
        }

    /** Transcripts include tool calls and notices; off by default, when they show only the conversation. */
    var showToolCalls: Boolean
        get() = sp.getBoolean(KEY_SHOW_TOOL_CALLS, false)
        set(value) = sp.edit().putBoolean(KEY_SHOW_TOOL_CALLS, value).apply()

    /** Provider of Quick Ask questions; the last choice on the confirm screen or in Settings. */
    var askProvider: String
        get() = sp.getString(KEY_ASK_PROVIDER, null) ?: ProviderId.CLAUDE_CODE
        set(value) = sp.edit().putString(KEY_ASK_PROVIDER, value).apply()

    private val accountsFlow = MutableStateFlow(readAccounts())

    /** The accounts seen and how each looks (see [AccountBook]); one JSON value, written when it changes. */
    val accounts: StateFlow<AccountBook> = accountsFlow.asStateFlow()

    /** From any thread: the Bridge's updates and the Accounts screen's edits are written in the order they apply. */
    @Synchronized
    fun updateAccounts(change: (AccountBook) -> AccountBook) {
        val before = accountsFlow.value
        val after = change(before)
        if (after == before) return
        accountsFlow.value = after
        sp.edit().putString(KEY_ACCOUNTS, WireJson.encodeToString(AccountBook.serializer(), after)).apply()
    }

    private fun readAccounts(): AccountBook {
        val json = sp.getString(KEY_ACCOUNTS, null) ?: return AccountBook()
        return try {
            WireJson.decodeFromString(AccountBook.serializer(), json)
        } catch (_: IllegalArgumentException) {
            // SerializationException is one: a value this version cannot read starts over.
            AccountBook()
        }
    }

    /** Demo mode is on; like a pairing it survives the process (see Bridge.init). */
    var demo: Boolean
        get() = sp.getBoolean(KEY_DEMO, false)
        set(value) = sp.edit().putBoolean(KEY_DEMO, value).apply()

    fun saveAddress(baseUrl: String) = sp.edit().putString(KEY_BASE_URL, baseUrl).apply()

    /** [deviceId] is empty for a token entered by hand. */
    fun savePairing(baseUrl: String, token: String, deviceId: String) =
        sp.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_TOKEN, token)
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()

    fun clear() {
        sp.edit().clear().apply()
        monitoringFlow.value = false
        watchColorsFlow.value = false
        accountsFlow.value = AccountBook()
    }

    private companion object {
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_DEVICE_NAME = "deviceName"
        const val KEY_MONITORING = "monitoring"
        const val KEY_DEMO = "demo"
        const val KEY_SHOW_TOOL_CALLS = "showToolCalls"
        const val KEY_ASK_PROVIDER = "askProvider"
        const val KEY_WATCH_COLORS = "watchColors"
        const val KEY_ACCOUNTS = "accounts"
    }
}
