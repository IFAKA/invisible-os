package com.faka.invisible

import com.faka.invisible.echo.EchoProgram
import com.faka.invisible.guard.GuardProgram
import com.faka.invisible.oskit.ProgramEntry

/**
 * THE REGISTRY. The one file that lists the programs of the OS (games and tools). The OS is the only module that depends on
 * program modules. To add a program: a module under programs/ that implements Program, one line here, and (for a tool) one
 * key in LauncherSettings.HUB_ITEMS plus one tag in AppCategories. The order here is the order of the Games list.
 */
object Programs {
    val all: List<ProgramEntry> = listOf(
        EchoProgram.entry,
        GuardProgram.entry,
    )
    fun byId(id: String): ProgramEntry? = all.firstOrNull { it.id == id }
    /** Dev hook name (`--es start echo`) to entry: the part of the id after the colon. */
    fun byDevName(name: String): ProgramEntry? = all.firstOrNull { it.id.substringAfter(':') == name }
}
