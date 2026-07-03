package tech.whitewolf.app.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushStatusTest {
    @Test fun endpointOnExpectedHostIsOk() {
        assertEquals(
            PushStatus.Ok,
            pushStatusForEndpoint("https://ntfy.whitewolf.tech/UPabc123", "ntfy.whitewolf.tech"),
        )
    }

    @Test fun endpointOnDifferentHostIsWrongServer() {
        assertEquals(
            PushStatus.WrongServer("ntfy.sh"),
            pushStatusForEndpoint("https://ntfy.sh/UPabc123", "ntfy.whitewolf.tech"),
        )
    }

    @Test fun hostCompareIsCaseInsensitive() {
        assertEquals(
            PushStatus.Ok,
            pushStatusForEndpoint("https://NTFY.WhiteWolf.Tech/UPabc123", "ntfy.whitewolf.tech"),
        )
    }

    @Test fun portIsIgnored() {
        assertEquals(
            PushStatus.Ok,
            pushStatusForEndpoint("https://ntfy.whitewolf.tech:8443/UPabc123", "ntfy.whitewolf.tech"),
        )
    }

    @Test fun unparseableEndpointIsOk() {
        assertEquals(PushStatus.Ok, pushStatusForEndpoint("not a url", "ntfy.whitewolf.tech"))
    }
}
