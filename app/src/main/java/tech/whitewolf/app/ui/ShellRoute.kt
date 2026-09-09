package tech.whitewolf.app.ui

import tech.whitewolf.app.subapp.SubAppId

/** Where the shell is. Flat by design: no nested stacks, so no nav library. */
sealed interface ShellRoute {
    data object Launcher : ShellRoute
    data class Open(val id: SubAppId) : ShellRoute
}
