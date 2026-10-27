package dev.wristline.watch.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsTest {
    @Test
    fun quickAskSendsNoLocationAndSearchesTheWebByDefault() {
        val prefs = Prefs(MemoryPrefs(), "Watch")
        assertFalse(prefs.askLocation)
        assertTrue(prefs.askSearch)
        assertEquals(ProviderId.CLAUDE_CODE, prefs.askProvider)
    }

    @Test
    fun quickAskSettingsAreKeptAndClearedWithThePairing() {
        val store = MemoryPrefs()
        val prefs = Prefs(store, "Watch")
        prefs.askLocation = true
        prefs.askSearch = false
        val reopened = Prefs(store, "Watch")
        assertTrue(reopened.askLocation)
        assertFalse(reopened.askSearch)
        reopened.clear()
        assertFalse(reopened.askLocation)
        assertTrue(reopened.askSearch)
    }
}

/** SharedPreferences in a map, applied at once; enough for [Prefs]. */
private class MemoryPrefs : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = @Suppress("UNCHECKED_CAST") (values[key] as Set<String>?) ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
