package tech.whitewolf.app.subapp

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeSubApp(override val id: SubAppId) : SubApp {
    override val title = id.value
    override val icon: ImageVector = Icons.Filled.Email
    @Composable override fun Content(host: SubAppHost, modifier: Modifier) = Unit
}

private fun entry(id: String) = SubAppEntry(FakeSubApp(SubAppId(id)), push = null)

class SubAppRegistryTest {
    @Test fun byIdFindsARegisteredSubApp() {
        val reg = SubAppRegistry(listOf(entry("mail"), entry("video")))
        assertEquals(SubAppId("video"), reg.byId(SubAppId("video"))?.ui?.id)
    }

    @Test fun byIdReturnsNullForAnUnknownSubApp() {
        // A newer build's notification must not crash an older shell.
        val reg = SubAppRegistry(listOf(entry("mail")))
        assertNull(reg.byId(SubAppId("video")))
    }

    @Test fun allPreservesRegistrationOrder() {
        val reg = SubAppRegistry(listOf(entry("mail"), entry("video")))
        assertEquals(listOf(SubAppId("mail"), SubAppId("video")), reg.ids())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateIdsAreRejectedAtConstruction() {
        // Two sub-apps sharing an id would collide on channel, prefs key and push instance.
        SubAppRegistry(listOf(entry("mail"), entry("mail")))
    }
}
