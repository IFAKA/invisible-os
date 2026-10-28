package com.faka.invisible

import com.faka.invisible.logickit.*
import org.junit.Assert.*
import org.junit.Test

class HomeActionsTest {
    private fun src(id: String, vararg a: HomeAction) = object : ActionSource { override val id = id; override fun actions() = a.toList() }
    private val apps = src("apps",
        HomeAction(ActionKind.OPEN, "WhatsApp", "com.whatsapp", 30.0),
        HomeAction(ActionKind.OPEN, "Weather", "com.weather", 3.0),
        HomeAction(ActionKind.OPEN, "Wikipedia", "org.wiki", 1.0),
        HomeAction(ActionKind.START, "Timer", "hub:timer", 5.0))
    private val songs = src("songs", HomeAction(ActionKind.PLAY, "Billie Jean", "Billie Jean Michael Jackson", 4.0), HomeAction(ActionKind.PLAY, "Bad", "Bad Michael Jackson", 2.0))

    @Test fun w_ranks_the_most_used_w_app_first() {
        val r = ActionRanker().rank(listOf(apps, songs), "w")
        assertEquals("WhatsApp", r.first().action.name)
        assertEquals(listOf("WhatsApp", "Weather", "Wikipedia"), r.map { it.action.name })
        assertEquals("Open WhatsApp", r.first().action.label.text)
    }

    @Test fun bi_gives_play_billie_jean_when_it_was_played() {
        val r = ActionRanker().rank(listOf(apps, songs), "bi")
        assertEquals("Play Billie Jean", r.first().action.label.text)
        assertTrue(ActionRanker().rank(listOf(apps), "bi").isEmpty())   // not played: no song
    }

    @Test fun initials_and_later_words_fit() {
        assertEquals(ActionMatch.INITIALS, ActionMatch.quality("Billie Jean", "bj"), 1e-9)
        assertEquals(ActionMatch.WORD, ActionMatch.quality("Billie Jean", "je"), 1e-9)
        assertEquals(0.0, ActionMatch.quality("Billie Jean", "x"), 1e-9)
        assertEquals('l', ActionMatch.next("Billie Jean", "bi"))
    }

    @Test fun every_app_is_reachable_by_its_letters_even_a_stored_one() {
        val stored = src("apps", HomeAction(ActionKind.OPEN, "Zebra Notes", "z.notes", 0.0))
        assertEquals("Zebra Notes", ActionRanker().rank(listOf(stored), "zeb").single().action.name)
    }

    @Test fun picks_move_an_action_up_and_trust_is_learned() {
        val a = src("apps", HomeAction(ActionKind.OPEN, "Maps", "maps", 5.0), HomeAction(ActionKind.OPEN, "Mail", "mail", 4.0))
        assertEquals("Maps", ActionRanker().rank(listOf(a), "ma").first().action.name)
        assertEquals("Mail", ActionRanker(picks = { if (it == "open:mail") 3 else 0 }).rank(listOf(a), "ma").first().action.name)
        var saved = ""
        val t = SourceTrust(null) { saved = it }
        val rk = ActionRanker(t::trust, t::scored)
        rk.rank(listOf(a), "ma"); assertEquals("apps", rk.picked("open:maps")!!.first)
        assertEquals("home:apps=1/1", saved)
        assertTrue(SourceTrust(saved) {}.trust("home:apps") > 0.6)
    }

    @Test fun songs_played_counts_and_orders() {
        var saved = ""
        val s = SongsPlayed(null) { saved = it }
        s.record("Billie Jean, Michael Jackson", 0); s.record("Bad, Michael Jackson", 1_000_000); s.record("Billie Jean, Michael Jackson", 2_000_000)
        assertEquals(listOf("Billie Jean", "Bad"), s.all().map { it.title }); assertEquals(2, s.all().first().plays)
        s.record("Billie Jean, Michael Jackson", 2_010_000)   // the same play seen again
        assertEquals(2, s.all().first().plays)
        assertEquals("Billie Jean", SongsPlayed(saved).all().first().title)
    }

    @Test fun spokenLabelsAreShortAndNeverARawKey() {
        assertEquals("Play La Maza", HomeAction(ActionKind.PLAY, "La Maza (feat. Shakira)", "La Maza (feat. Shakira) Mercedes Sosa", 1.0).label.text)
        assertEquals("Play Billie Jean", HomeAction(ActionKind.PLAY, "Billie Jean - Remastered 2012", "x", 1.0).label.text)
        assertEquals("Open WhatsApp", HomeAction(ActionKind.OPEN, "WhatsApp", "com.whatsapp", 1.0).label.text)
        assertEquals("Reply to Ana Lopez", HomeAction(ActionKind.REPLY, "Ana Lopez", "0|com.microsoft.teams|-376346685|null|10355", 1.0).label.text)
        assertEquals("Reply to the chat", HomeAction(ActionKind.REPLY, "0|com.microsoft.teams|-376346685", "k", 1.0).label.text)
        assertEquals("Reply to Team Alpha Beta", HomeAction(ActionKind.REPLY, "Team Alpha Beta Gamma Delta", "k", 1.0).label.text)
    }
}
