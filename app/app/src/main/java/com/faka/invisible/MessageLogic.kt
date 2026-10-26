package com.faka.invisible

import com.faka.invisible.logickit.*

import java.text.Normalizer

/** On-device language detection. Returns a language code ("es", "en", ...) or "" if unknown. Blocking: off the main thread. */
interface LangDetector { fun detect(text: String): String }

enum class MsgType { INVITE, QUESTION, REQUEST, THANKS, INFO }

enum class Answer { YES, NO, MAYBE, LATER, CANT_NOW, WELCOME, OK, THANKS, CALL_ME }
enum class When { TODAY, TONIGHT, TOMORROW, WEEKEND, SKIP }
enum class Extra { NOTHING, THANKS, SORRY, CALL_ME }

/** Pure, rule based (ES + EN) message classifier and wording tables. */
object MessageLogic {
    private val urlRe = Regex("""(?i)\b(?:https?://|www\.)\S+|\b(?:youtu\.be|[a-z0-9-]+\.(?:com|net|org|me|tv))/\S*""")

    /** Text without links, for language detection and classification. */
    fun stripLinks(text: String): String = urlRe.replace(text, " ").squash()

    /** True if the text has no letters left once links are removed (link only, "📷 Photo" aside). */
    fun hasWords(text: String): Boolean = stripLinks(text).count { it.isLetter() } >= 2 && !isPlaceholder(text)

    private val placeholderRe = Regex("""(?i)^[\p{So}\p{Sk}\s]*(?:(?:sent|enviou|envió|envio) (?:a|an|un|una|um|uma)\s+)?(?:photo|video|audio|sticker|gif|image|document|contact|location|voice message|foto|imagen|imagem|documento|contacto|ubicación|ubicacion|mensaje de voz|mensagem de voz|\d+ (?:new )?messages?)\b.*$""")

    /** WhatsApp's own text for media ("📷 Photo", "Sent a sticker"): written in the phone's language, not the person's. */
    fun isPlaceholder(text: String): Boolean = placeholderRe.matches(stripLinks(text)) && stripLinks(text).length < 40

    /** Conversation title without the "(2 messages)" counter, so a person keeps one identity. */
    fun stableName(sender: String): String = sender.replace(Regex("\\s*\\(\\d+ (?:new )?messages?\\)"), "").trim()

    private fun siteName(url: String): String? {
        val u = url.lowercase()
        return when {
            "youtu" in u -> "YouTube"; "instagram." in u || "instagr.am" in u -> "Instagram"
            "facebook." in u || "fb.com" in u || "fb.watch" in u -> "Facebook"; "tiktok." in u -> "TikTok"
            "twitter." in u || "x.com/" in u -> "X"; "spotify." in u -> "Spotify"; "maps.google" in u || "goo.gl/maps" in u || "maps.app" in u -> "Maps"
            else -> null
        }
    }

    /** Text for the voice: each link becomes a short label ("link de YouTube" / "a YouTube link"). */
    fun spoken(text: String, lang: String): String {
        val es = wordingLang(lang) == "es"
        return urlRe.replace(text) { r ->
            val site = siteName(r.value)
            " " + (if (es) (if (site != null) "un link de $site" else "un link") else (if (site != null) "${if (site == "Instagram") "an" else "a"} $site link" else "a link")) + " "
        }.squash()
    }

    /** "es" if the language is Spanish, else "en" (templates and tag words exist only for these two). */
    fun wordingLang(lang: String) = if (lang == "es") "es" else "en"

    /** Language to ask the voice for: es / en only. Others (or unknown) use the default voice. */
    fun voiceLang(lang: String): String? = if (lang == "es" || lang == "en") lang else null

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-z0-9?\\s']"), " ").squash()

    private fun hasWord(t: String, phrases: List<String>) =
        phrases.any { Regex("(^| )" + Regex.escape(it) + "( |\\?|$)").containsMatchIn(t) }

    private val invite = listOf("quieres", "quieren", "queres", "vamos", "te apuntas", "se apuntan", "te animas", "te vienes",
        "vienes", "venis", "want to", "wanna", "lets go", "let's go", "lets", "join us", "do you want", "come over", "come with")
    private val request = listOf("puedes", "puede", "podrias", "podes", "me ayudas", "me ayudarias", "necesito", "me puedes", "me podrias",
        "can you", "could you", "would you", "need", "i need", "please help", "help me")
    private val thanks = listOf("gracias", "muchas gracias", "mil gracias", "thanks", "thank you", "thx", "ty", "cheers")
    private val questionStart = listOf("que haces", "que tal", "que hora", "que pasa", "que paso", "que onda", "que opinas", "que te parece",
        "cuando es", "cuando llegas", "cuando nos vemos", "cuando sales", "cuando vienes", "donde", "como estas", "como va", "como te", "como esta",
        "quien", "cuanto", "cuantos", "por que", "para que",
        "what", "when", "where", "how", "who", "why", "which", "do you", "are you", "is it", "is there", "did you", "have you")
    // Accented starters are only safe in their accented form ("qué" vs "que" = "that").
    private val accentedStart = listOf("qué", "cuándo", "dónde", "cómo", "quién", "cuánto", "cuál", "por qué")

    fun classify(text0: String, lang: String = ""): MsgType {
        val text = stripLinks(text0)
        val raw = text.trim().lowercase()
        val t = norm(text)
        val extra = if (lang == "es") emptyList() else listOf("come")   // "come" in Spanish is "eats"
        if (hasWord(t, invite + extra)) return MsgType.INVITE
        if (hasWord(t, request)) return MsgType.REQUEST
        if (raw.endsWith("?") || raw.endsWith("?\"") ||
            questionStart.any { t == it || t.startsWith("$it ") } ||
            accentedStart.any { raw.startsWith("$it ") || raw == it || raw.startsWith("¿$it") }) return MsgType.QUESTION
        if (raw.startsWith("¿")) return MsgType.QUESTION
        if (hasWord(t, thanks)) return MsgType.THANKS
        return MsgType.INFO
    }

    fun tagWord(type: MsgType, lang: String): String = if (wordingLang(lang) == "es") when (type) {
        MsgType.INVITE -> "Invitación"; MsgType.QUESTION -> "Pregunta"; MsgType.REQUEST -> "Pedido"
        MsgType.THANKS -> "Gracias"; MsgType.INFO -> "Mensaje"
    } else when (type) {
        MsgType.INVITE -> "Invitation"; MsgType.QUESTION -> "Question"; MsgType.REQUEST -> "Request"
        MsgType.THANKS -> "Thanks"; MsgType.INFO -> "Message"
    }

    // ---- reply composer: Answer -> When -> Extra, built from templates ----
    /** Answer options for a message type, best first. THANKS / INFO answers are final (sent at once). */
    fun answers(type: MsgType): List<Answer> = when (type) {
        MsgType.INVITE -> listOf(Answer.YES, Answer.NO, Answer.MAYBE, Answer.LATER, Answer.CANT_NOW)
        MsgType.QUESTION -> listOf(Answer.YES, Answer.NO, Answer.LATER, Answer.MAYBE, Answer.CANT_NOW)
        MsgType.REQUEST -> listOf(Answer.YES, Answer.NO, Answer.LATER, Answer.CANT_NOW, Answer.MAYBE)
        MsgType.THANKS -> listOf(Answer.WELCOME, Answer.OK)
        MsgType.INFO -> listOf(Answer.OK, Answer.THANKS, Answer.CALL_ME)
    }

    fun isFinal(type: MsgType) = type == MsgType.THANKS || type == MsgType.INFO
    fun asksWhen(a: Answer) = a == Answer.YES || a == Answer.NO || a == Answer.MAYBE || a == Answer.LATER

    private fun pick(lang: String, es: String, en: String) = if (wordingLang(lang) == "es") es else en

    /** Short option word, spoken in the message language. */
    fun word(a: Answer, lang: String) = when (a) {
        Answer.YES -> pick(lang, "Sí", "Yes"); Answer.NO -> "No"; Answer.MAYBE -> pick(lang, "Quizás", "Maybe")
        Answer.LATER -> pick(lang, "Luego", "Later"); Answer.CANT_NOW -> pick(lang, "Ahora no", "Cant now")
        Answer.WELCOME -> pick(lang, "De nada", "Youre welcome"); Answer.OK -> "Ok"
        Answer.THANKS -> pick(lang, "Gracias", "Thanks"); Answer.CALL_ME -> pick(lang, "Llámame", "Call me")
    }

    fun word(w: When, lang: String) = when (w) {
        When.TODAY -> pick(lang, "Hoy", "Today"); When.TONIGHT -> pick(lang, "Esta noche", "Tonight")
        When.TOMORROW -> pick(lang, "Mañana", "Tomorrow"); When.WEEKEND -> pick(lang, "Fin de semana", "Weekend")
        When.SKIP -> pick(lang, "Omitir", "Skip")
    }

    fun word(x: Extra, lang: String) = when (x) {
        Extra.NOTHING -> pick(lang, "Nada", "Nothing"); Extra.THANKS -> pick(lang, "Gracias", "Thanks")
        Extra.SORRY -> pick(lang, "Perdón", "Sorry"); Extra.CALL_ME -> pick(lang, "Llámame", "Call me")
    }

    /** Safe question sent by "Tell me more". */
    fun tellMeMore(lang: String) = pick(lang, "¿A qué hora y dónde?", "What time and where?")

    private fun body(a: Answer, w: When, lang: String): String {
        val es = wordingLang(lang) == "es"
        return when (a) {
            Answer.YES -> if (es) when (w) {
                When.SKIP -> "Sí, dale"; When.TODAY -> "Sí, hoy va bien"; When.TONIGHT -> "Sí, esta noche va bien"
                When.TOMORROW -> "Sí, mañana va bien"; When.WEEKEND -> "Sí, el fin de semana va bien"
            } else when (w) {
                When.SKIP -> "Sure, count me in"; When.TODAY -> "Sure, today works"; When.TONIGHT -> "Sure, tonight works"
                When.TOMORROW -> "Sure, tomorrow works"; When.WEEKEND -> "Sure, the weekend works"
            }
            Answer.NO -> if (es) when (w) {
                When.SKIP -> "No puedo"; When.TODAY -> "Hoy no puedo"; When.TONIGHT -> "Esta noche no puedo"
                When.TOMORROW -> "Hoy no puedo, mañana sí"; When.WEEKEND -> "Hoy no puedo, el fin de semana sí"
            } else when (w) {
                When.SKIP -> "I can't"; When.TODAY -> "Can't today"; When.TONIGHT -> "Can't tonight"
                When.TOMORROW -> "Can't today, tomorrow works"; When.WEEKEND -> "Can't today, the weekend works"
            }
            Answer.MAYBE -> if (es) when (w) {
                When.SKIP -> "Quizá"; When.TODAY -> "Quizá hoy"; When.TONIGHT -> "Quizá esta noche"
                When.TOMORROW -> "Quizá mañana"; When.WEEKEND -> "Quizá el fin de semana"
            } else when (w) {
                When.SKIP -> "Maybe"; When.TODAY -> "Maybe today"; When.TONIGHT -> "Maybe tonight"
                When.TOMORROW -> "Maybe tomorrow"; When.WEEKEND -> "Maybe the weekend"
            }
            Answer.LATER -> if (es) when (w) {
                When.SKIP -> "Te digo luego"; When.TODAY -> "Te digo hoy"; When.TONIGHT -> "Te digo esta noche"
                When.TOMORROW -> "Te digo mañana"; When.WEEKEND -> "Te digo el fin de semana"
            } else when (w) {
                When.SKIP -> "I'll tell you later"; When.TODAY -> "I'll tell you today"; When.TONIGHT -> "I'll tell you tonight"
                When.TOMORROW -> "I'll tell you tomorrow"; When.WEEKEND -> "I'll tell you over the weekend"
            }
            Answer.CANT_NOW -> pick(lang, "Ahora no puedo, te escribo luego", "Can't right now, I'll text you later")
            Answer.WELCOME -> pick(lang, "De nada", "You're welcome")
            Answer.OK -> "Ok"
            Answer.THANKS -> pick(lang, "Gracias", "Thanks")
            Answer.CALL_ME -> pick(lang, "Llámame cuando puedas", "Call me when you can")
        }
    }

    private fun tail(x: Extra, lang: String) = when (x) {
        Extra.NOTHING -> ""
        Extra.THANKS -> pick(lang, ", gracias", ", thanks")
        Extra.SORRY -> pick(lang, ", perdón", ", sorry")
        Extra.CALL_ME -> pick(lang, ", llámame cuando puedas", ", call me when you can")
    }

    /** Final reply text. */
    fun compose(a: Answer, w: When, x: Extra, lang: String): String = body(a, w, lang) + tail(x, lang)

    // ---- v3 quick replies (codes 1 / 2 on a message) ----
    private val timeWords = listOf(When.TONIGHT to listOf("esta noche", "tonight", "tonite"),
        When.TOMORROW to listOf("manana", "tomorrow"), When.TODAY to listOf("hoy", "today"))

    /** The time word found in the text (hoy, esta noche, manana, today, tonight, tomorrow), earliest wins; SKIP if none. */
    fun timeWord(text: String): When {
        val t = norm(text)
        var best = When.SKIP; var bestAt = Int.MAX_VALUE
        for ((w, phrases) in timeWords) for (p in phrases) {
            val m = Regex("(^| )" + Regex.escape(p) + "( |\\?|$)").find(t) ?: continue
            if (m.range.first < bestAt) { bestAt = m.range.first; best = w }
        }
        return best
    }

    /** Best Yes-type (yes=true) or No-type reply. THANKS / INFO: "Ok" for yes, null (nothing to send) for no. */
    fun quickReply(type: MsgType, yes: Boolean, text: String, lang: String): String? {
        if (isFinal(type)) return if (yes) compose(Answer.OK, When.SKIP, Extra.NOTHING, lang) else null
        return compose(if (yes) Answer.YES else Answer.NO, timeWord(text), Extra.NOTHING, lang)
    }

    private val esWords = setOf("el", "la", "los", "las", "que", "de", "y", "es", "en", "un", "una", "por", "para", "con", "no", "si", "me", "te", "hola",
        "gracias", "quieres", "puedes", "vamos", "estoy", "esta", "como", "donde", "cuando", "muy", "pero", "hoy", "ahora", "luego", "dale", "ok")
    private val enWords = setOf("the", "and", "is", "are", "to", "of", "you", "i", "it", "in", "for", "with", "not", "me", "hi", "hey", "thanks",
        "want", "can", "lets", "what", "when", "where", "how", "but", "today", "now", "later", "wanna")

    /** Tiny stop-word guess used when the real detector has no answer: "es", "en" or "". */
    fun guessLang(text: String): String {
        val words = norm(text).split(' ')
        val e = words.count { it in esWords }; val n = words.count { it in enWords }
        return when { e > n -> "es"; n > e -> "en"; else -> "" }
    }
}
