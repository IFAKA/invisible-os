package com.faka.invisible.adapters

import com.faka.invisible.oskit.*
import org.junit.Assert.*
import org.junit.Test

class CalendarModuleTest {
    private val p = CalendarModule.Companion::phrase
    @Test fun english() = assertEquals("Standup, in 20 min, Room 2", p("Standup", "In 20 minutes · Room 2"))
    @Test fun spanish() = assertEquals("Reunión, in 20 min, Sala 2", p("Reunión", "En 20 minutos • Sala 2"))
    @Test fun spanishDentro() = assertEquals("Dentista, in 1 h", p("Dentista", "Dentro de 1 hora"))
    @Test fun noPlace() = assertEquals("Lunch, in 5 min", p("Lunch", "In 5 min"))
    @Test fun now() = assertEquals("Call, now, Zoom", p("Call", "Now · Zoom"))
    @Test fun clockTime() = assertEquals("Gym, at 10:00, Park", p("Gym", "10:00 - 11:00 · Park"))
    @Test fun unknownTextKept() = assertEquals("Party, Bring cake", p("Party", "Bring cake"))
    private class FakeHost(val remindOk: Boolean = true) : NotificationHost {
        val log = ArrayList<String>()
        override fun arrival(pkg: String, sender: String, text: String, lang: String, visibility: Int) {}
        override fun detectLanguage(text: String) = "en"
        override fun dismiss(key: String): Boolean { log.add("dismiss $key"); return true }
        override fun remindIn(minutes: Int): Boolean { log.add("remind $minutes"); return remindOk }
    }
    private val ev = Notif("k1", CalendarModule.PKG, "Calendar", "Standup", "In 10 minutes", 1)
    @Test fun offersSnooze() = assertEquals(listOf("Snooze 10 min"), CalendarModule().extraActions(ev).map { it.label })
    @Test fun snoozeRemindsThenDismisses() {
        val h = FakeHost(); val a = CalendarModule().extraActions(ev)[0]
        assertTrue(a.run(ev, h)); assertEquals(listOf("remind 10", "dismiss k1"), h.log)
    }
    @Test fun snoozeKeepsNotificationWhenReminderFails() {
        val h = FakeHost(remindOk = false)
        assertFalse(CalendarModule().extraActions(ev)[0].run(ev, h)); assertEquals(listOf("remind 10"), h.log)
    }
    @Test fun onlyCalendar() { assertTrue(CalendarModule().matches(CalendarModule.PKG)); assertFalse(CalendarModule().matches("com.whatsapp")) }
}
