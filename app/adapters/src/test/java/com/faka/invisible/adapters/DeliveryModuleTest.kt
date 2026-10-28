package com.faka.invisible.adapters

import org.junit.Assert.*
import org.junit.Test

class DeliveryModuleTest {
    private val p = DeliveryModule.Companion::phrase
    @Test fun uberEnglish() = assertEquals("Driver, 4 min", p("Uber", "Your driver is 4 min away"))
    @Test fun uberSpanish() = assertEquals("Driver, 4 min", p("Uber", "Tu conductor llega en 4 minutos"))
    @Test fun arrived() = assertEquals("Driver, arrived", p("Your driver has arrived", "Look for a white Toyota"))
    @Test fun arrivedSpanish() = assertEquals("Driver, arrived", p("Tu conductor ha llegado", ""))
    @Test fun eatsOrder() = assertEquals("Order, 10 min", p("Uber Eats", "Your order is arriving in 10 minutes"))
    @Test fun rappiNear() = assertEquals("Driver, near", p("Rappi", "Tu repartidor está cerca"))
    @Test fun pedidosYa() = assertEquals("Order, 15 min", p("PedidosYa", "Tu pedido llega en 15 min"))
    @Test fun doordash() = assertEquals("Driver, 3 min", p("DoorDash", "Your Dasher is 3 mins away"))
    @Test fun unknownKept() = assertEquals("Uber, Rate your trip", p("Uber", "Rate your trip"))
    @Test fun packages() { val m = DeliveryModule(); assertTrue(DeliveryModule.PACKAGES.all { m.matches(it) }); assertFalse(m.matches("com.whatsapp")) }
}
