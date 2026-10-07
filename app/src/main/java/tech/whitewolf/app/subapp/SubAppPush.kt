package tech.whitewolf.app.subapp

import android.content.Context
import android.net.Uri

/**
 * A sub-app's push behaviour, kept separate from [SubApp] because PushReceiver is a
 * BroadcastReceiver with no Activity and cannot touch a @Composable. Each sub-app owns
 * its own wire format, channel and notification ids.
 */
interface SubAppPush {
    val channelId: String          // always SubAppId.value
    val channelName: String
    val channelDescription: String

    /** Parse this sub-app's own payload. Null when the body is not understood. */
    fun decode(body: ByteArray): WakePayload?

    /** Post (or replace) this sub-app's notification. Owns its notification ids. */
    fun notify(context: Context, payload: WakePayload)

    /** Where a tap should land, as a string: wwt://subapp/<id>[/<item>]. */
    fun tapTarget(payload: WakePayload): String

    /** The same target as a Uri. Default impl; android.net.Uri is stubbed in unit tests,
     *  so tests assert on [tapTarget] instead. */
    fun tapUri(payload: WakePayload): Uri = Uri.parse(tapTarget(payload))

    /**
     * True when [body] asks this sub-app to REMOVE its notification rather than post one
     * (mail's `{"type":"clear"}`, sent once the mail it announced was dealt with on
     * another client). Checked by PushReceiver before [decode]; a dismissal never bumps
     * the WakeBus and never notifies. Defaults to false, so a sub-app with no notion of
     * clearing is unaffected and every body still flows to [decode] as before.
     */
    fun dismisses(body: ByteArray): Boolean = false

    /** Cancel this sub-app's notification(s). Owns its ids, as [notify] does. A no-op by
     *  default, paired with the default [dismisses] that never asks for it. */
    fun clear(context: Context) {}
}
