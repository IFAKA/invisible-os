package com.faka.invisible.logickit

import org.junit.Assert.*
import org.junit.Test

class NotifSpeechTest {
    private fun chat(title: String, text: String) = NotifSpeech.read(title, text, chat = true)

    @Test fun ownerReactionCase() {
        val t = "Lisandro Arenas: Reacted ❤️ to \"🔗 Steve Jobs' Son Is Betting Millions To Make Cancer... | https://youtu.be/dqpkDwlqEms El hijo de steve jobs. they agree with burn the boats\""
        assertEquals("Lisandro reacted with a heart.", chat("Lisandro Arenas", t))
    }
    @Test fun plainText() = assertEquals("Ana: see you at 8", chat("Ana", "see you at 8"))
    @Test fun voiceNote() = assertEquals("Ana: Voice message", chat("Ana", "🎤 Voice message (0:12)"))
    @Test fun photo() = assertEquals("Ana: Photo", chat("Ana", "📷 Photo"))
    @Test fun linkOnly() = assertEquals("Ana: a YouTube link", chat("Ana", "https://youtu.be/dqpkDwlqEms"))
    @Test fun groupMessage() = assertEquals("Ana in Family: hi", chat("Family", "Ana: hi"))
    @Test fun spanishReaction() = assertEquals("Lisandro reaccionó con un corazón.", chat("Lisandro", "Reaccionó con ❤️ a \"hola cómo estás\""))
    @Test fun spanishPlain() = assertEquals("Mamá: ¿Vienes a cenar?", chat("Mamá", "Mamá: ¿Vienes a cenar??? 😊"))
    @Test fun punctuationAndCut() = assertEquals("Ana: wait. what!", NotifSpeech.read("Ana", "wait... what!!!"))
    @Test fun trailingCutRemoved() = assertEquals("Ana: this is a long messa", NotifSpeech.read("Ana", "this is a long messa…"))
    @Test fun unknownEmojiReaction() = assertEquals("Ana reacted.", chat("Ana", "Reacted 🦄 to \"x\""))
    @Test fun genericKeepsColonSubject() = assertEquals("Gmail. Re: plan", NotifSpeech.read("Gmail", "Re: plan", sep = ". "))
    @Test fun longnessUsesCleanText() {
        assertFalse(NotifSpeech.isLong(NotifSpeech.cleanText("https://youtu.be/abc " + "word ".repeat(20))))
        assertTrue(NotifSpeech.isLong("word ".repeat(26)))
    }
}
