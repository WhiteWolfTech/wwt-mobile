package tech.whitewolf.app.push

import java.net.URI

/**
 * Health of push delivery, derived from the distributor and its endpoint.
 * Data-light and free of Android types → JVM-testable.
 */
sealed interface PushStatus {
    /** A distributor is registered and pointed at the expected server. */
    object Ok : PushStatus
    /** No UnifiedPush distributor is installed. */
    object NoDistributor : PushStatus
    /** A distributor is installed but pointed at [endpointHost], not the expected host. */
    data class WrongServer(val endpointHost: String) : PushStatus
}

/**
 * Classify a distributor endpoint by its host. The endpoint URL the distributor issues
 * *is* its configured server, so comparing its host to [expectedHost] (case-insensitive,
 * port ignored) is a precise, network-free check. An unparseable endpoint → [PushStatus.Ok]
 * (never a false alarm). Never returns [PushStatus.NoDistributor] — that is decided upstream
 * from distributor presence, not from an endpoint.
 */
fun pushStatusForEndpoint(endpoint: String, expectedHost: String): PushStatus {
    val host = try {
        URI(endpoint).host
    } catch (e: Exception) {
        null
    } ?: return PushStatus.Ok
    return if (host.equals(expectedHost, ignoreCase = true)) {
        PushStatus.Ok
    } else {
        PushStatus.WrongServer(host)
    }
}
