package com.faka.invisible

import com.faka.invisible.voicekit.*

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

class ContextTest {
    private val base = object : Ranker {
        override fun ranked(context: List<String>) = listOf("hola" to Math.log(0.5), "gracias" to Math.log(0.3), "no" to Math.log(0.2))
    }
    private val frame = Frame(app = "wa", field = "entry", thread = "Ana", hasField = true)

    @Test fun fieldKindsFromTheInputType() {
        assertEquals(FieldKind.NUMBER, Fields.kind(InputType.TYPE_CLASS_PHONE, false, "", ""))
        assertEquals(FieldKind.EMAIL, Fields.kind(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, false, "", ""))
        assertEquals(FieldKind.PASSWORD, Fields.kind(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, false, "", ""))
        assertEquals(FieldKind.PASSWORD, Fields.kind(InputType.TYPE_CLASS_TEXT, true, "", ""))
        assertEquals(FieldKind.SEARCH, Fields.kind(InputType.TYPE_CLASS_TEXT, false, "Buscar", ""))
        assertEquals(FieldKind.TEXT, Fields.kind(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, false, "Message", "entry"))
    }

    @Test fun bucketsGoFromSpecificToGeneral() {
        assertEquals(listOf("t:wa/Ana", "f:wa/entry", "a:TEXT/wa", "k:TEXT"), frame.keys())
        assertEquals(listOf("k:TEXT"), Frame().keys())
    }

    @Test fun learningChangesTheOrderInThatPlaceOnly() {
        val s = BucketStore(emptyList())
        repeat(3) { s.learn(frame, listOf("no", "puedo"), "No puedo") }
        assertEquals("no", BucketRanker(base, s, frame).ranked(emptyList()).first().first)   // here "no" beat "hola"
        assertEquals("hola", BucketRanker(base, s, Frame(app = "mail", kind = FieldKind.EMAIL)).ranked(emptyList()).first().first)
    }

    @Test fun newWordsBecomeReachableByLearning() {
        val s = BucketStore(emptyList())
        repeat(2) { s.learn(frame, listOf("hola", "Ana"), "Hola Ana") }
        assertTrue(BucketRanker(base, s, frame).ranked(listOf("hola")).any { it.first == "ana" })
    }

    @Test fun passwordsAreNeverLearned() {
        val saved = ArrayList<List<String>>()
        val s = BucketStore(emptyList()) { saved.add(it) }
        s.learn(Frame(kind = FieldKind.PASSWORD), listOf("secret"), "secret")
        assertTrue(saved.isEmpty()); assertTrue(s.suggestions(Frame(kind = FieldKind.PASSWORD)).isEmpty())
    }

    @Test fun storeSurvivesSaveAndLoad() {
        var lines = emptyList<String>()
        val s = BucketStore(emptyList()) { lines = it }
        s.scored("t", true); s.learn(frame, listOf("hola"), "Hola")   // hit counts are saved with the next finished text
        val again = BucketStore(lines)
        assertEquals(listOf("Hola"), again.suggestions(frame))
        assertEquals(2.0 / 3.0, again.trust("t"), 1e-9)
    }

    @Test fun aLayerThatKeepsWinningGetsMoreTrust() {
        val s = BucketStore(emptyList())
        val before = s.trust("t"); repeat(5) { s.scored("t", true) }
        assertTrue(s.trust("t") > before)
    }

    @Test fun suggestionsPutTheMostSpecificPlaceFirst() {
        val s = BucketStore(emptyList())
        s.learn(Frame(app = "wa", field = "entry", thread = "Ana"), listOf("a"), "to Ana")
        s.learn(Frame(app = "wa", field = "entry", thread = "Bea"), listOf("b"), "to Bea")
        assertEquals("to Ana", s.suggestions(Frame(app = "wa", field = "entry", thread = "Ana")).first())
    }

    @Test fun wordsOnTheScreenComeBackInTheReply() {
        val chat = Frame(app = "wa", around = listOf("Ana: ¿Vienes a cenar mañana?"), hasField = true)
        val top = BucketRanker(base, BucketStore(emptyList()), chat).ranked(emptyList()).take(4).map { it.first }
        assertTrue(top.toString(), top.contains("cenar") || top.contains("vienes") || top.contains("mañana"))
    }
}
