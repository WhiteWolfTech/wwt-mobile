package tech.whitewolf.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import tech.whitewolf.app.push.DeepLink
import tech.whitewolf.app.ui.ShellScreen
import tech.whitewolf.app.ui.ShellViewModel
import tech.whitewolf.app.ui.ShellViewModelFactory
import tech.whitewolf.app.ui.WwtTheme

class MainActivity : ComponentActivity() {
    // by lazy, not a plain property initializer: applicationContext is not available
    // until the framework attaches this Activity, which happens after construction.
    private val container: AppContainer by lazy { WwtApp.from(this).container }

    private val shellVm: ShellViewModel by viewModels {
        ShellViewModelFactory(container, WwtApp.from(this).wakeBus)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // Only on a genuinely new launch. After process death getIntent() re-delivers the
        // same data URI, and the route has already been restored from SavedStateHandle —
        // replaying it would re-navigate on every restore.
        if (savedInstanceState == null) deliverDeepLink(intent)
        setContent {
            WwtTheme {
                Surface { ShellScreen(container) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliverDeepLink(intent) // always: this is a fresh tap, not a redelivery
    }

    private fun deliverDeepLink(intent: Intent) {
        DeepLink.parse(intent.data)?.let {
            shellVm.offerLink(it, signedIn = container.sessionBus.loggedIn.value)
        }
    }
}
