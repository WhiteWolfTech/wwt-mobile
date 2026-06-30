package tech.whitewolf.app.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedPrefsStoreTest {
    @Test fun roundTripsThroughKeystore() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = EncryptedPrefsStore(ctx)
        store.remove("k")
        assertNull(store.getString("k"))
        store.putString("k", "v")
        assertEquals("v", store.getString("k"))
        store.remove("k")
        assertNull(store.getString("k"))
    }
}
