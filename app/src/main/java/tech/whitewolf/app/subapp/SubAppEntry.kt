package tech.whitewolf.app.subapp

/** One sub-app's two facets. [push] is null for a sub-app that never notifies. */
data class SubAppEntry(val ui: SubApp, val push: SubAppPush?) {
    val id: SubAppId get() = ui.id
}
