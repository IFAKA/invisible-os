package com.faka.invisible.adapters

import com.faka.invisible.oskit.*

/**
 * Ride and delivery apps (Uber, Uber Eats, Rappi, DoorDash, PedidosYa): a status notification is read short, like
 * "Driver, 4 min", "Driver, arrived" or "Order, 10 min". English and Spanish. Other text is read as title and text.
 */
class DeliveryModule : NotificationModule {
    companion object {
        val PACKAGES = setOf("com.ubercab", "com.ubercab.eats", "com.grability.rappi", "com.dd.doordash", "com.pedidosya")
        private val WHO = Regex("\\b(driver|courier|dasher|rider|conductor|conductora|repartidor|repartidora|rappitendero|socio)\\b", RegexOption.IGNORE_CASE)
        private val ORDER = Regex("\\b(order|pedido|orden)\\b", RegexOption.IGNORE_CASE)
        private val MINUTES = Regex("(\\d+)\\s*(?:-\\s*\\d+\\s*)?(min\\w*|h\\b|hr\\w*|hours?|horas?)", RegexOption.IGNORE_CASE)
        private val ARRIVED = Regex("\\b(has arrived|is here|arrived|ha llegado|llegó|llego|está aquí|esta aqui|te espera|is waiting)\\b", RegexOption.IGNORE_CASE)
        private val NEAR = Regex("\\b(nearby|almost there|close|cerca|casi llega)\\b", RegexOption.IGNORE_CASE)

        /** Pure: a short line from the title and text, or title and text joined when nothing is known. */
        fun phrase(title: String, text: String): String {
            val all = "$title. $text"
            val who = when {
                WHO.containsMatchIn(all) -> "Driver"
                ORDER.containsMatchIn(all) -> "Order"
                else -> null
            }
            val status = MINUTES.find(all)?.let { m ->
                m.groupValues[1] + if (m.groupValues[2].lowercase().startsWith("m")) " min" else " h"
            } ?: when {
                ARRIVED.containsMatchIn(all) -> "arrived"
                NEAR.containsMatchIn(all) -> "near"
                else -> null
            }
            if (who == null || status == null) return listOf(title, text).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            return "$who, $status"
        }
    }

    override fun matches(pkg: String) = pkg in PACKAGES
    override fun read(n: Notif) = phrase(n.title, n.text)
}
