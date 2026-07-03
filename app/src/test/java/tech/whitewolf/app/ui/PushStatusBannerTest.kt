package tech.whitewolf.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.whitewolf.app.push.PushStatus

class PushStatusBannerTest {
    @Test fun okHasNoBannerContent() {
        assertNull(pushBannerContent(PushStatus.Ok))
    }

    @Test fun noDistributorIsStepOneWithInstallUrl() {
        assertEquals(
            BannerContent(
                stepLabel = "Notifications — step 1 of 2",
                instruction = "Install ntfy: in Obtainium, add this source:",
                copyUrl = NTFY_INSTALL_URL,
            ),
            pushBannerContent(PushStatus.NoDistributor),
        )
    }

    @Test fun wrongServerIsStepTwoWithServerUrlAndNoHostInCopy() {
        assertEquals(
            BannerContent(
                stepLabel = "Notifications — step 2 of 2",
                instruction = "In ntfy, set Settings → Default server to:",
                copyUrl = "https://ntfy.whitewolf.tech",
            ),
            pushBannerContent(PushStatus.WrongServer("ntfy.sh")),
        )
    }

    @Test fun installUrlIsTheNtfyAndroidAppRepo() {
        assertEquals("https://github.com/binwiederhier/ntfy-android", NTFY_INSTALL_URL)
    }
}
