package tech.whitewolf.app.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-scoped holder for the current [PushStatus]. Starts at [PushStatus.Ok] so no
 * banner shows until a problem is actually known. Published from PushReceiver (background
 * thread) and read by the shell UI — StateFlow makes the cross-thread hand-off safe.
 */
class PushStatusBus {
    private val _status = MutableStateFlow<PushStatus>(PushStatus.Ok)
    val status: StateFlow<PushStatus> = _status

    fun set(s: PushStatus) { _status.value = s }
}
