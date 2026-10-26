package com.faka.invisible.logickit

/** Pure text helpers shared by every screen and store. Check `logickit/KIT.md` before writing a new one. */
private val spaces = Regex("\\s+")

/** Any run of spaces, tabs and line breaks becomes one space; ends are trimmed. */
fun String.squash(): String = replace(spaces, " ").trim()

/** The words of a text, split on any white space, none empty. */
fun String.words(): List<String> = trim().split(spaces).filter { it.isNotEmpty() }
