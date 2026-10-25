package com.faka.invisible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InfoTest {
    private val sample = """{"latitude":40.4,"current_units":{"time":"iso8601","temperature_2m":"°C","weather_code":"wmo code"},
        "current":{"time":"2026-10-25T14:00","interval":900,"temperature_2m":18.4,"weather_code":3},
        "hourly_units":{"time":"iso8601","precipitation_probability":"%"},
        "hourly":{"time":["2026-10-25T14:00","2026-10-25T15:00","2026-10-25T16:00"],"precipitation_probability":[0,10,70]}}"""

    @Test fun weatherRainInTwoHours() = assertEquals("Now 18 degrees, rain in 2 hours.", Weather.sentence(sample))
    @Test fun weatherNoRain() = assertEquals("Now 18 degrees, no rain soon.", Weather.sentence(sample.replace("[0,10,70]", "[0,10,20]")))
    @Test fun weatherRainingNow() = assertEquals("Now 18 degrees, raining now.", Weather.sentence(sample.replace("\"weather_code\":3", "\"weather_code\":61")))
    @Test fun weatherOneHourAndMinus() =
        assertEquals("Now minus 3 degrees, rain in 1 hour.", Weather.sentence(sample.replace("18.4", "-2.6").replace("[0,10,70]", "[0,60,70]")))
    @Test fun weatherBadJson() { assertNull(Weather.sentence("{}")); assertNull(Weather.sentence("oops")) }

    private val min = 60_000L
    private val noon = 12 * 60 * min   // UTC day start + 12:00, offset 0
    @Test fun eventInMinutes() = assertEquals("Dentist at 4 PM, in 40 minutes.", Agenda.sentence("Dentist", 16 * 60 * min, 16 * 60 * min - 40 * min, 0))
    @Test fun eventWithMinutesAndHours() = assertEquals("Lunch at 12:30 PM, in 1 hour 30 minutes.", Agenda.sentence("Lunch", noon + 30 * min, noon - 60 * min, 0))
    @Test fun eventExactHoursAndMorning() = assertEquals("Run at 12 AM, in 2 hours.", Agenda.sentence("Run", 24 * 60 * min, 22 * 60 * min, 0))
    @Test fun eventUsesTimeZoneOffset() = assertEquals("Call at 9 AM, in 1 minute.", Agenda.sentence("Call", 7 * 60 * min, 7 * 60 * min - min, 2 * 60 * min))
    @Test fun eventNowAndUnnamed() { assertEquals("Call, now.", Agenda.sentence("Call", 1000, 5000, 0)); assertEquals("Event, now.", Agenda.sentence(null, 0, 0, 0)) }
    @Test fun noEventsSentence() = assertEquals("No events today.", Voice.NO_EVENTS)
}
