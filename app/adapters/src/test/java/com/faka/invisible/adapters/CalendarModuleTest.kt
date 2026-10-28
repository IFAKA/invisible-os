package com.faka.invisible.adapters

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
    @Test fun onlyCalendar() { assertTrue(CalendarModule().matches(CalendarModule.PKG)); assertFalse(CalendarModule().matches("com.whatsapp")) }
}
