package com.faka.invisible.claude

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilesTest {
    @Test fun chipFillsNameAndProfile() {
        assertEquals(Profiles.Draft("Work laptop", "work"), Profiles.chip("work"))
        assertEquals(Profiles.Draft("Laptop", "personal"), Profiles.chip("personal"))
    }
    @Test fun typingAName_keepsTheChosenProfile() {
        val d = Profiles.typed(Profiles.chip("work"), "Office Mac")
        assertEquals(Profiles.Draft("Office Mac", "work"), d)
        assertEquals("personal", Profiles.typed(Profiles.chip("personal"), "Mine").profile)
    }
    @Test fun chipAfterTypingOverwritesTheName() {
        assertEquals("Laptop", Profiles.chip("personal").name)
    }
    @Test fun suggestionFillsOnlyAnUntouchedForm() {
        val fresh = Profiles.Draft("MacBook", "")
        assertEquals(Profiles.Draft("Work laptop", "work"), Profiles.suggested(fresh, "MacBook", "work"))
        assertEquals(fresh.copy(name = "Mine"), Profiles.suggested(fresh.copy(name = "Mine"), "MacBook", "work"))   // typed
        assertEquals(Profiles.chip("personal"), Profiles.suggested(Profiles.chip("personal"), "MacBook", "work"))   // chosen
        assertEquals(fresh, Profiles.suggested(fresh, "MacBook", "nonsense"))
    }
    @Test fun profileIsSavedWithTheComputer() {
        val list = listOf(Computer("Home", "http://h:1", "t", "work"), Computer("Old", "http://h:2", "t2"))
        val back = Computers.decode(Computers.encode(list))
        assertEquals(list, back)
        assertEquals("work", Profiles.effective(back[0]))
        assertEquals("personal", Profiles.effective(back[1]))      // not chosen: personal
        assertEquals("work", Computers.withProfile(back, "old", "work")[1].profile)
        assertEquals("work", Computers.rename(back, "Home", "Office")[0].profile)
    }
    @Test fun oldSavedListWithoutProfileStillLoads() {
        val c = Computers.decode("[{\"name\":\"Home\",\"url\":\"u\",\"token\":\"t\"}]")
        assertEquals("", c[0].profile)
    }
    @Test fun toolsSitBeforeReadAllAndWrite() {
        val r = Room("%1", "A", "p", "claude", "idle", "", emptyList(), listOf("go", "stop", "readall", "screen", "write"))
        assertEquals(listOf("hear", "go", "replies", "read", "tools", "write"), RoomAction.earbudList(r).map { it.id })
        assertEquals(listOf("go", "stop"), RoomAction.screenList(r).map { it.id })
    }
}
