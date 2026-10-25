package com.faka.invisible

import android.graphics.Color

/** UI Kit: every colour and corner size of the screen you can see. Change a value here, the whole app changes. */
object Ui {
    val ink = Color.parseColor("#1B1F23")         // main text
    val soft = Color.parseColor("#5F6872")        // hints
    val accent = Color.parseColor("#2F5BEA")      // buttons, values
    val good = Color.parseColor("#1E8E4E")
    val bad = Color.parseColor("#C5392B")
    val page = Color.parseColor("#F3F4F7")        // screen background, task row
    val tint = Color.parseColor("#E8EDFB")        // soft button, chip
    val edge = Color.parseColor("#B8C0CC")        // outline of the input box
    val card = Color.WHITE

    // corner radius in dp
    const val RADIUS_CARD = 14
    const val RADIUS_ROW = 12
    const val RADIUS_CHIP = 16
    const val RADIUS_BUTTON = 24
}
