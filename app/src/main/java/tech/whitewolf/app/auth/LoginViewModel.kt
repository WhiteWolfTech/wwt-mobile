package tech.whitewolf.app.auth

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the user is told when SSO does not produce a session. Deliberately the same for
 * every cause (WWT-173, WWT-177): a dismissed tab and a failed one are indistinguishable
 * from the activity result, and the provider's own wording — which arrives verbatim as
 * AuthorizationException.message — is a diagnostic, not something a user can act on.
 */
internal const val SSO_INCOMPLETE = "Sign-in didn't complete — tap to try again"

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
)

class LoginViewModel(
    private val auth: Authenticator,
    private val sso: SsoLogin? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmail(s: String) = _state.update { it.copy(email = s, error = null) }
    fun onPassword(s: String) = _state.update { it.copy(password = s, error = null) }

    fun submit() {
        val s = _state.value
        if (s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(io) { auth.login(s.email.trim(), s.password) }
            _state.update {
                when (result) {
                    is LoginResult.Success -> it.copy(loading = false, loggedIn = true)
                    is LoginResult.InvalidCredentials ->
                        it.copy(loading = false, error = "Incorrect email or password")
                    is LoginResult.Error -> it.copy(loading = false, error = result.message)
                }
            }
        }
    }

    /** Whether the SSO button should be offered (an SSO collaborator is wired). */
    val ssoAvailable: Boolean get() = sso != null

    /**
     * Starts SSO: builds the AppAuth authorization intent (OIDC discovery runs off the
     * main thread) and hands it to [launchTab], which opens the Custom Tab. The result
     * returns through [onSsoResult].
     */
    fun startSso(launchTab: (Intent) -> Unit) {
        val s = sso ?: return
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val intent = withContext(io) { s.authorizationIntent() }
                launchTab(intent)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't start sign-in") }
            }
        }
    }

    /**
     * Handles the Custom Tab result: completes the token exchange and mints the app
     * session. A null [data] is ambiguous — AppAuth returns it both when the user
     * dismisses the tab and when the tab ends on an IdP error page (WWT-173: an expired
     * consent challenge surfaces as a raw 500, so the redirect never fires). The two
     * cannot be told apart from the result, so say something true of both rather than
     * stopping the spinner in silence.
     */
    fun onSsoResult(data: Intent?) {
        val s = sso ?: return
        if (data == null) {
            _state.update { it.copy(loading = false, error = SSO_INCOMPLETE) }
            return
        }
        completeSso { s.signIn(data) }
    }

    /**
     * Runs an SSO exchange and maps its outcome onto the UI state.
     *
     * Split from [onSsoResult] so the failure mapping carries no Android types: the
     * [Intent] is only a token handed to the collaborator, and keeping it out of here is
     * what makes every failure path reachable from a unit test.
     */
    internal fun completeSso(exchange: suspend () -> LoginResult) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(io) {
                try {
                    exchange()
                } catch (e: Exception) {
                    LoginResult.Error(e.message ?: "SSO sign-in failed")
                }
            }
            // The cause is worth keeping, just not worth showing: log it once here, where
            // both a thrown exception and a backend rejection have converged.
            if (result is LoginResult.Error) {
                android.util.Log.w("LoginViewModel", "SSO sign-in failed: ${result.message}")
            }
            _state.update {
                when (result) {
                    is LoginResult.Success -> it.copy(loading = false, loggedIn = true)
                    is LoginResult.InvalidCredentials -> it.copy(loading = false, error = "Sign-in was rejected")
                    is LoginResult.Error -> it.copy(loading = false, error = SSO_INCOMPLETE)
                }
            }
        }
    }
}
