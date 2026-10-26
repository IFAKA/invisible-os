package com.faka.invisible

import com.faka.invisible.echo.EchoProgram
import com.faka.invisible.compass.CompassProgram
import com.faka.invisible.tell.DuelProgram
import com.faka.invisible.guard.GuardProgram
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.write.WriteEntries
import com.faka.invisible.write.WriteServices

/**
 * THE REGISTRY. The one file that lists the programs of the OS (games and tools). The OS is the only module that depends on
 * program modules. To add a program: a module under programs/ that implements Program, one line here, and (for a tool) one
 * key in LauncherSettings.HUB_ITEMS plus one tag in AppCategories. The order here is the order of the Games list.
 */
object Programs {
    private var services: WriteServices? = null
    private var appContext: android.content.Context? = null
    /** What the programs ask of the OS beyond the host; set once when the OS starts, before [all] is read. */
    fun install(write: WriteServices, ctx: android.content.Context) { services = write; appContext = ctx }

    val all: List<ProgramEntry> by lazy { listOf(
        DuelProgram.play,
        EchoProgram.entry,
        GuardProgram.entry,
        DuelProgram.howTo,
        CompassProgram.entry,
        WriteEntries.write(services!!) { appContext!! },
        WriteEntries.sum,
    ) }
    fun byId(id: String): ProgramEntry? = all.firstOrNull { it.id == id }
    /** Dev hook name (`--es start echo`) to entry: the part of the id after the colon. */
    fun byDevName(name: String): ProgramEntry? = all.firstOrNull { it.id.substringAfter(':') == name }
}
