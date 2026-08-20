package tech.whitewolf.app.auth

import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeAuth(private val result: LoginResult) : Authenticator {
    override fun login(email: String, password: String): LoginResult = result
    override fun isLoggedIn(): Boolean = false
    override fun logout() = Unit
}

private class FakeSso : SsoLogin {
    override suspend fun authorizationIntent(): Intent = error("no flow should start in these tests")
    override suspend fun signIn(resultData: Intent): LoginResult = error("no exchange should run in these tests")
}

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun successSetsLoggedIn() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth(LoginResult.Success), io = dispatcher)
        vm.onEmail("a@x.tech"); vm.onPassword("pw")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test fun invalidCredentialsSetsError() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth(LoginResult.InvalidCredentials), io = dispatcher)
        vm.submit(); advanceUntilIdle()
        assertFalse(vm.state.value.loggedIn)
        assertEquals("Incorrect email or password", vm.state.value.error)
    }

    @Test fun errorSurfacesMessage() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth(LoginResult.Error("network error")), io = dispatcher)
        vm.submit(); advanceUntilIdle()
        assertEquals("network error", vm.state.value.error)
    }

    /** WWT-173: a 500 at the IdP means the redirect never fires, so the result comes back
     *  null — indistinguishable from the user swiping the tab away. Either way the app
     *  must say something rather than silently stop the spinner. */
    @Test fun unfinishedSsoTellsTheUserToRetry() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth(LoginResult.Success), FakeSso(), dispatcher)
        vm.onSsoResult(null)
        advanceUntilIdle()
        assertEquals("Sign-in didn't complete — tap to try again", vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.loggedIn)
    }

    /** WWT-177: this is Authelia's own prose, arriving as AuthorizationException.message
     *  (AppAuth passes error_description straight to Exception's message). It is not ours
     *  to show a user. */
    @Test fun providerErrorTextNeverReachesTheUser() = runTest(dispatcher) {
        val authelia = "The authorization server encountered an unexpected condition " +
            "that prevented it from fulfilling the request. Could not perform consent."
        val vm = LoginViewModel(FakeAuth(LoginResult.Success), FakeSso(), dispatcher)
        vm.completeSso { throw IllegalStateException(authelia) }
        advanceUntilIdle()
        assertEquals("Sign-in didn't complete — tap to try again", vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.loggedIn)
    }

    /** The backend's own rejection wording is no more use to a user than the IdP's. */
    @Test fun backendRejectionTextNeverReachesTheUser() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth(LoginResult.Success), FakeSso(), dispatcher)
        vm.completeSso { LoginResult.Error("SSO sign-in failed (500)") }
        advanceUntilIdle()
        assertEquals("Sign-in didn't complete — tap to try again", vm.state.value.error)
    }
}
