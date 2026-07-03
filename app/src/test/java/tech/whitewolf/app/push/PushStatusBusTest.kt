package tech.whitewolf.app.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushStatusBusTest {
    @Test fun initialStatusIsOk() {
        assertEquals(PushStatus.Ok, PushStatusBus().status.value)
    }

    @Test fun setNoDistributorUpdatesStatus() {
        val bus = PushStatusBus()
        bus.set(PushStatus.NoDistributor)
        assertEquals(PushStatus.NoDistributor, bus.status.value)
    }

    @Test fun setWrongServerRoundTrips() {
        val bus = PushStatusBus()
        bus.set(PushStatus.WrongServer("ntfy.sh"))
        assertEquals(PushStatus.WrongServer("ntfy.sh"), bus.status.value)
    }
}
