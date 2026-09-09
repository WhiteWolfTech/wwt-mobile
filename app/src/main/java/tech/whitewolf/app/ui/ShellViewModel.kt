package tech.whitewolf.app.ui

import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tech.whitewolf.app.AppContainer
import tech.whitewolf.app.push.WakeBus
import tech.whitewolf.app.subapp.SubAppHost
import tech.whitewolf.app.subapp.SubAppId
import tech.whitewolf.app.subapp.SubAppRegistry
import tech.whitewolf.app.subapp.WakePayload

/**
 * Android glue over [RouteState]: nothing here decides navigation, it just persists it in
 * two different places for two different reasons.
 *
 * [SavedStateHandle] mirrors the CURRENT route exactly, including `null` at the launcher —
 * a warm restore (config change, or a process death that keeps saved instance state) must
 * put the user back exactly where they were, launcher included.
 *
 * [lastUsed] is coarser and never cleared by visiting the launcher: it is cold-start's
 * "which sub-app to open with zero taps", updated only when [open] actually lands on a
 * known sub-app (see [RouteState.restoreKey]'s own doc).
 *
 * Also the one place that turns a [SubAppId] into the [SubAppHost] a sub-app's `Content()`
 * composes with — built once per id and cached, since `wake`/`deepLink` are StateFlows a
 * fresh object per recomposition would either leak (a fresh `stateIn` collector every
 * frame) or break identity for anything that keys off it.
 */
class ShellViewModel(
    private val savedState: SavedStateHandle,
    registry: SubAppRegistry,
    private val lastUsed: LastUsedStore,
    private val wakeBus: WakeBus,
) : ViewModel() {

    private val state = RouteState(
        known = { registry.byId(it) != null },
        lastUsed = lastUsed.get(),
        saved = savedState.get<String>(ROUTE_KEY)?.let { SubAppId.parse(it) },
    )

    val route: StateFlow<ShellRoute> = state.route

    private val hosts = mutableMapOf<SubAppId, SubAppHost>()

    init {
        // Persist the route RouteState actually resolved to (which may differ from what
        // was read back above — an unknown saved/lastUsed id falls to Launcher).
        syncSavedRoute()
    }

    fun open(id: SubAppId) {
        state.open(id)
        // RouteState.open() no-ops for an unknown id; only persist "last used" when the
        // route actually landed on it, per RouteState.restoreKey's own doc.
        if (state.route.value == ShellRoute.Open(id)) lastUsed.set(id)
        syncSavedRoute()
    }

    fun toLauncher() {
        state.toLauncher()
        syncSavedRoute()
    }

    fun offerLink(payload: WakePayload, signedIn: Boolean) {
        state.offerLink(payload, signedIn)
        syncSavedRoute()
    }

    /** Applies a link that arrived while signed out, now that sign-in has completed. */
    fun onSignedIn() {
        state.onSignedIn()
        syncSavedRoute()
    }

    fun hostFor(id: SubAppId): SubAppHost = hosts.getOrPut(id) {
        val initial = state.pendingLink.value?.takeIf { it.subAppId == id }
        val deepLinkFlow: StateFlow<WakePayload?> = state.pendingLink
            .map { payload -> payload?.takeIf { it.subAppId == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, initial)

        object : SubAppHost {
            override val deepLink: StateFlow<WakePayload?> = deepLinkFlow
            override val wake: StateFlow<Long> = wakeBus.tick(id)
            override fun onDeepLinkHandled() { state.consumeLink() }
        }
    }

    private fun syncSavedRoute() {
        savedState[ROUTE_KEY] = state.restoreKey
    }

    private companion object {
        const val ROUTE_KEY = "shell.route"
    }
}

/**
 * `wakeBus` is process-scoped and owned by `WwtApp`, not [AppContainer] — `PushReceiver`
 * signals into that SAME instance — so it is threaded in explicitly here rather than
 * looked up from inside the ViewModel.
 */
class ShellViewModelFactory(
    private val container: AppContainer,
    private val wakeBus: WakeBus,
) : AbstractSavedStateViewModelFactory() {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle,
    ): T = ShellViewModel(handle, container.registry, container.lastUsedStore, wakeBus) as T
}
