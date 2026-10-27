package com.faka.invisible.logickit

/**
 * The voice language of an app name: "es" or "en". A name is a few words, too short for a language detector, so:
 * 1. a package table (Spanish apps "es", brands that must stay "en"),
 * 2. else the label has a Spanish word or one of the letters ñ á é í ó ú ¿ ¡ -> "es",
 * 3. else "en". Only for app names; OS menu words stay English.
 */
object NameLang {
    private val PACKAGES: Map<String, String> = buildMap {
        for (p in listOf("com.mercadopago.wallet", "com.mercadolibre", "com.brubank", "com.bbva.bbvacontigo", "es.bancosantander.apps",
            "com.santander.app", "com.myinvestor.es", "com.synergym", "es.lacaixa.mobile.imaginemobile", "es.caixabank.caixabankmobile", "es.lacaixa.mobile.android.newwapicon", "com.bankinter.launcher", "ar.com.santander.rio.mbanking",
            "com.nu.production", "com.cabify.rider", "com.mi.tarjeta.transporte"))
            put(p, "es")
        for (p in listOf("com.whatsapp", "com.whatsapp.w4b", "com.revolut.revolut", "com.transferwise.android", "com.android.chrome", "com.google.android.gm",
            "com.google.android.apps.maps", "com.google.android.youtube", "com.google.android.apps.photos", "com.google.android.googlequicksearchbox",
            "com.google.android.calendar", "com.google.android.apps.messaging", "com.google.android.dialer", "com.ubercab", "com.spotify.music",
            "com.instagram.android", "org.telegram.messenger", "com.netflix.mediaclient", "com.microsoft.teams", "de.traderepublic.app",
            "com.chase.sig.android", "com.paypal.android.p2pmobile", "com.infonow.bofa", "com.wf.wellsfargomobile", "com.konylabs.capitalone", "co.uk.getmondo",
            "com.starlingbank.android", "com.venmo", "com.squareup.cash", "com.coinbase.android", "com.strava", "com.citymapper.app.release", "me.lyft.android",
            "com.facebook.katana", "com.twitter.android", "com.linkedin.android", "com.amazon.mShop.android.shopping"))
            put(p, "en")
    }

    private val WORDS = setOf("de", "del", "la", "las", "el", "los", "mi", "mis", "tu", "y", "para", "con", "por", "en",
        "tarjeta", "banco", "correo", "mensajes", "notas", "calendario", "reloj", "linterna", "cámara", "camara", "tienda", "juegos",
        "ajustes", "transporte", "pagos", "pago", "mercado", "cuenta", "cuentas", "inversión", "inversion", "tren", "metro", "autobús", "bus",
        "billetera", "farmacia", "salud", "gimnasio", "radio", "noticias", "mapas", "llamadas", "contactos", "archivos", "galería", "galeria",
        "calculadora", "tiempo", "música", "musica", "teléfono", "telefono", "ofertas", "viajes", "comida", "seguros", "ahorro")

    private val LETTERS = Regex("[ñáéíóú¿¡]")
    private val SPLIT = Regex("[^\\p{L}]+")

    /** "es" or "en" for an app with this package and label. */
    fun of(pkg: String, label: String): String {
        PACKAGES[pkg]?.let { return it }
        val l = label.lowercase()
        if (LETTERS.containsMatchIn(l)) return "es"
        return if (l.split(SPLIT).any { it in WORDS }) "es" else "en"
    }

    /** The Phrase language: "es", or null for the menu voice (English). */
    fun voice(pkg: String, label: String): String? = of(pkg, label).takeIf { it == "es" }
}
