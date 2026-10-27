package com.faka.invisible

import com.faka.invisible.oskit.*

import org.junit.Assert.*
import org.junit.Test

class NotificationStoreTest {
    private fun n(key: String, pkg: String, at: Long, text: String = "t") = Notif(key, pkg, pkg.uppercase(), "title", text, at)

    @Test fun newestFirstAndDedupeByKey() {
        val s = NotificationStore()
        s.upsert(n("a", "x", 1)); s.upsert(n("b", "x", 3)); s.upsert(n("a", "x", 2, "changed"))
        assertEquals(listOf("b", "a"), s.all().map { it.key })
        assertEquals("changed", s.get("a")!!.text)
        assertEquals(2, s.count())
    }

    @Test fun groupsByAppNewestGroupFirst() {
        val s = NotificationStore()
        s.upsert(n("1", "mail", 5)); s.upsert(n("2", "chat", 9)); s.upsert(n("3", "mail", 7))
        val g = s.groups()
        assertEquals(listOf("chat", "mail"), g.map { it.id })
        assertEquals(listOf("3", "1"), g[1].items.map { it.key })
    }

    @Test fun customGrouping() {
        val s = NotificationStore()
        s.upsert(n("1", "chat", 1)); s.upsert(n("2", "chat", 2))
        assertEquals(2, s.groups { it.key }.size)
    }

    @Test fun capDropsOldest() {
        val s = NotificationStore(max = 2)
        s.upsert(n("1", "x", 1)); s.upsert(n("2", "x", 2)); s.upsert(n("3", "x", 3))
        assertEquals(listOf("3", "2"), s.all().map { it.key })
    }

    @Test fun removeAndClear() {
        val s = NotificationStore()
        s.upsert(n("1", "x", 1)); s.upsert(n("2", "x", 2))
        s.remove("1"); assertEquals(listOf("2"), s.all().map { it.key })
        s.clear(); assertEquals(0, s.count())
    }

    @Test fun mutedAppsAreNotCounted() {
        val mutes = MuteSet()
        val s = NotificationStore(muted = mutes::isMuted)
        s.upsert(n("1", "x", 1)); s.upsert(n("2", "y", 2))
        mutes.mute("x")
        assertEquals(listOf("2"), s.all().map { it.key })
        assertEquals(listOf("y"), s.groups().map { it.id })
        mutes.unmuteAll()
        assertEquals(2, s.count())
    }

    @Test fun muteSetPersists() {
        var saved = emptySet<String>()
        val m = MuteSet(setOf("a")) { saved = it }
        assertTrue(m.isMuted("a"))
        m.mute("b"); assertEquals(setOf("a", "b"), saved)
        m.unmuteAll(); assertEquals(emptySet<String>(), saved)
    }

    @Test fun privacy() {
        assertTrue(NotifPrivacy.mustHide("com.x", NotifPrivacy.VISIBILITY_SECRET, false, false))
        assertTrue(NotifPrivacy.mustHide("com.x", NotifPrivacy.VISIBILITY_PRIVATE, true, false))
        assertFalse(NotifPrivacy.mustHide("com.x", NotifPrivacy.VISIBILITY_PRIVATE, false, false))
        assertFalse(NotifPrivacy.mustHide("com.x", 1, true, false))
        assertTrue(NotifPrivacy.mustHide("com.x", 1, false, true))
        assertTrue(NotifPrivacy.mustHide("com.chase.sig.android", 1, false, false))
        assertTrue(NotifPrivacy.mustHide("com.x.bitwarden.mobile", 1, false, false))
        assertFalse(NotifPrivacy.sensitivePackage("com.whatsapp"))
    }

    @Test fun replyActionIsTheOneThatNeedsText() {
        val x = Notif("k", "p", "P", "t", "x", 1, listOf(NotifAction(0, "Archive", false), NotifAction(1, "Reply", true)))
        assertEquals(1, x.reply!!.index)
    }
}

class NotificationModulesTest {
    private val n = Notif("k", "com.chat", "Chat", "Ana", "hola", 1)
    private val chat = object : NotificationModule {
        override fun matches(pkg: String) = pkg == "com.chat"
        override fun read(n: Notif) = "${n.title}: ${n.text}"
        override fun groupKey(n: Notif) = n.title
    }

    @Test fun genericIsTheDefault() {
        val r = NotificationModules(listOf(chat))
        assertSame(GenericModule, r.forPackage("com.other"))
        assertEquals("Ana. hola", GenericModule.read(n))
    }

    @Test fun registeredModuleWinsByPackage() {
        val r = NotificationModules(listOf(chat))
        assertSame(chat, r.forNotif(n))
        assertEquals("Ana: hola", r.forNotif(n).read(n))
        assertEquals("Ana", r.forNotif(n).groupKey(n))
    }

    @Test fun genericSkipsBlankParts() {
        assertEquals("only", GenericModule.read(n.copy(title = "", text = "only")))
    }
}
