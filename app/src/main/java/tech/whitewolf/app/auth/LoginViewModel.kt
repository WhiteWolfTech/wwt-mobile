package tech.whitewolf.app.auth

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

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
)

class LoginViewModel(
    private val auth: Authenticator,
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
}
