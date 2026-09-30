package tech.whitewolf.app.subapp

/**
 * A sub-app's identity. One string with five jobs: the launcher's last-used
 * preference key, the notification channel id, the deep-link path segment, the wake
 * routing key, and the UnifiedPush instance name. Defined once here so those five
 * never drift apart.
 */
@JvmInline
value class SubAppId(val value: String) {
    override fun toString(): String = value

    companion object {
        private val safe = Regex("^[a-z0-9]{1,32}$")

        /** Null when [raw] would be unsafe in any of the five uses above. */
        fun parse(raw: String): SubAppId? = if (safe.matches(raw)) SubAppId(raw) else null
    }
}
