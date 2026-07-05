package tech.whitewolf.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.whitewolf.app.push.PushStatus

class PushStatusBannerTest {
    @Test fun okWithNotificationsEnabledHasNoBanner() {
        assertNull(pushBannerContent(PushStatus.Ok, notificationsEnabled = true))
    }

    @Test fun okWithNotificationsBlockedIsTheFinalStep() {
        assertEquals(
            BannerContent(
                stepLabel = "Notifications — one last thing",
                instruction = "Allow notifications for WWT in Settings → Apps → WWT → Notifications.",
                copyUrl = null,
            ),
            pushBannerContent(PushStatus.Ok, notificationsEnabled = false),
        )
    }

    @Test fun noDistributorIsStepOneAndWinsOverBlockedNotifications() {
        assertEquals(
            BannerContent(
                stepLabel = "Notifications — step 1 of 2",
                instruction = "Install and open ntfy: in Obtainium, add this source:",
                copyUrl = NTFY_INSTALL_URL,
            ),
            pushBannerContent(PushStatus.NoDistributor, notificationsEnabled = false),
        )
    }

    @Test fun wrongServerIsStepTwoAndWinsOverBlockedNotifications() {
        assertEquals(
            BannerContent(
                stepLabel = "Notifications — step 2 of 2",
                instruction = "In ntfy, set Settings → Default server to:",
                copyUrl = "https://ntfy.whitewolf.tech",
            ),
            pushBannerContent(PushStatus.WrongServer("ntfy.sh"), notificationsEnabled = false),
        )
    }

    @Test fun installUrlIsTheNtfyAndroidAppRepo() {
        assertEquals("https://github.com/binwiederhier/ntfy-android", NTFY_INSTALL_URL)
    }
}
