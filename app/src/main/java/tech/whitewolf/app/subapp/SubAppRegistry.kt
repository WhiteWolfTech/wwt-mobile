package tech.whitewolf.app.subapp

/**
 * The ordered registry of WWT sub-apps. An instance, not an object: entries are
 * constructed with their dependencies by AppContainer. Must stay UI-free and safe to
 * build off the main thread — PushReceiver builds the container on a background thread.
 *
 * There is deliberately no default()/auto-open: what opens on cold start is the
 * launcher's last-used state, not a property of the registry.
 */
class SubAppRegistry(private val entries: List<SubAppEntry>) {
    init {
        val ids = entries.map { it.id }
        require(ids.toSet().size == ids.size) { "duplicate sub-app ids: $ids" }
    }

    fun all(): List<SubAppEntry> = entries

    fun ids(): List<SubAppId> = entries.map { it.id }

    fun byId(id: SubAppId): SubAppEntry? = entries.firstOrNull { it.id == id }
}
