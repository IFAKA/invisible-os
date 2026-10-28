package com.faka.invisible.reader

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A minimal in-memory SharedPreferences (only what ReaderStore uses). */
private class MemPrefs : SharedPreferences {
    val m = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = m
    override fun getString(k: String, d: String?) = m[k] as String? ?: d
    override fun getStringSet(k: String, d: MutableSet<String>?) = d
    override fun getInt(k: String, d: Int) = m[k] as Int? ?: d
    override fun getLong(k: String, d: Long) = d
    override fun getFloat(k: String, d: Float) = d
    override fun getBoolean(k: String, d: Boolean) = d
    override fun contains(k: String) = m.containsKey(k)
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val ops = ArrayList<() -> Unit>()
        override fun putString(k: String, v: String?) = apply { ops.add { m[k] = v } }
        override fun putInt(k: String, v: Int) = apply { ops.add { m[k] = v } }
        override fun remove(k: String) = apply { ops.add { m.remove(k) } }
        override fun putStringSet(k: String, v: MutableSet<String>?) = this
        override fun putLong(k: String, v: Long) = this
        override fun putFloat(k: String, v: Float) = this
        override fun putBoolean(k: String, v: Boolean) = this
        override fun clear() = this
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { ops.forEach { it() } }
    }
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

class ReaderStoreTest {
    @Test fun sharedFirstThenClipboardThenLast() {
        val p = MemPrefs()
        assertNull(ReaderStore.pick(p, null))
        assertNull(ReaderStore.pick(p, "   "))
        assertEquals("From clip.", ReaderStore.pick(p, "From clip."))
        assertEquals("From clip.", ReaderStore.pick(p, null))   // the last one
        ReaderStore.share(p, "Shared text.")
        assertEquals("Shared text.", ReaderStore.pick(p, "From clip."))
        assertEquals("From clip.", ReaderStore.pick(p, "From clip."))   // shared is used once
        assertEquals("From clip.", ReaderStore.pick(p, null))
    }

    @Test fun positionIsPerTextAndClearedAtTheEnd() {
        val p = MemPrefs()
        ReaderStore.save(p, "a", 3); ReaderStore.save(p, "b", 1)
        assertEquals(3, ReaderStore.position(p, "a", 10))
        assertEquals(1, ReaderStore.position(p, "b", 10))
        assertEquals(1, ReaderStore.position(p, "a", 2))   // text changed: kept inside the list
        ReaderStore.finished(p, "a")
        assertEquals(0, ReaderStore.position(p, "a", 10))
    }

    @Test fun languageFromTheText() {
        assertEquals("es", ReaderStore.lang("Hola, esto es una prueba de que el texto es para ti y no para mi."))
        assertEquals("en", ReaderStore.lang("This is a test of the text for you and me."))
        assertNull(ReaderStore.lang("xyz"))
    }
}
