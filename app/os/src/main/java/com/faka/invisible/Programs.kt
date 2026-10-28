package com.faka.invisible

import com.faka.invisible.echo.EchoProgram
import com.faka.invisible.compass.CompassProgram
import com.faka.invisible.memory.MemoryProgram
import com.faka.invisible.quiz.QuizProgram
import com.faka.invisible.reaction.ReactionProgram
import com.faka.invisible.habits.HabitsEntry
import com.faka.invisible.counter.CounterEntry
import com.faka.invisible.breathe.BreatheEntry
import com.faka.invisible.clicktest.ClickTestProgram
import com.faka.invisible.reader.ReaderEntry
import com.faka.invisible.whereami.WhereAmIEntry
import com.faka.invisible.whereami.WhereServices
import com.faka.invisible.claude.ClaudeProgram
import com.faka.invisible.practice.PracticeEntry
import com.faka.invisible.tell.DuelProgram
import com.faka.invisible.guard.GuardProgram
import com.faka.invisible.adapters.CalendarModule
import com.faka.invisible.adapters.ChatAppsModule
import com.faka.invisible.adapters.TeamsModule
import com.faka.invisible.oskit.NotificationModules
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
    private var roomsPort: com.faka.invisible.claude.RoomsPort? = null
    private var appContext: android.content.Context? = null
    /** What the programs ask of the OS beyond the host; set once when the OS starts, before [all] is read. */
    private var where: WhereServices? = null
    fun install(write: WriteServices, rooms: com.faka.invisible.claude.RoomsPort, ctx: android.content.Context, whereServices: WhereServices) { services = write; roomsPort = rooms; appContext = ctx; where = whereServices }

    val all: List<ProgramEntry> by lazy { listOf(
        DuelProgram.play,
        EchoProgram.entry,
        GuardProgram.entry,
        MemoryProgram.entry,
        QuizProgram.entry,
        ReactionProgram.entry,
        DuelProgram.howTo,
        CompassProgram.entry,
        HabitsEntry.entry,
        CounterEntry.entry,
        BreatheEntry.entry,
        ClickTestProgram.entry,
        ReaderEntry.entry,
        WhereAmIEntry.entry(where!!),
        ClaudeProgram.entry(roomsPort!!),
        PracticeEntry.entry,
        WriteEntries.write(services!!) { appContext!! },
        WriteEntries.sum,
    ) }
    fun byId(id: String): ProgramEntry? = all.firstOrNull { it.id == id }
    /** Dev hook name (`--es start echo`) to entry: the part of the id after the colon. */
    fun byDevName(name: String): ProgramEntry? = all.firstOrNull { it.id.substringAfter(':') == name }
}

/**
 * THE ADAPTER REGISTRY (same file: the OS lists everything it plugs in here). One line per outside app; every other app is
 * read by the generic module. To add an app: one file in adapters/ and one line in [modules].
 */
object Adapters {
    val chat = ChatAppsModule()
    val modules = NotificationModules(listOf(TeamsModule(chat), CalendarModule(), chat))
}
