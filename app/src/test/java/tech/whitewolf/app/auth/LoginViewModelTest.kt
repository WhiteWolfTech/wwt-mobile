package tech.whitewolf.app.auth

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
}
