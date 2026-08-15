package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the dependency-free settings back stack (T7/T11): push/pop semantics,
 * Root collapse, back-at-Root returning false, reset, stack restore, the
 * Bundle-safe route tokens behind the `rememberSaveable` saver, and a full
 * saver round trip.
 */
class SettingsNavStateTest {

    private val appearance = SettingsRoute.Category(SettingCategory.APPEARANCE)
    private val privacy = SettingsRoute.Category(SettingCategory.PRIVACY_SECURITY)
    private val accountOne = SettingsRoute.AccountSettings(1L)

    @Test
    fun initialStackIsRoot() {
        assertEquals(listOf(SettingsRoute.Root), SettingsNavState().backStack)
    }

    @Test
    fun navigatePushesRoutes() {
        val nav = SettingsNavState()
        nav.navigate(appearance)
        assertEquals(listOf(SettingsRoute.Root, appearance), nav.backStack)
    }

    @Test
    fun navigateRootWhenAlreadyAtRootIsNoOp() {
        val nav = SettingsNavState()
        nav.navigate(SettingsRoute.Root)
        assertEquals(listOf(SettingsRoute.Root), nav.backStack)
    }

    @Test
    fun navigateRootCollapsesTheWholeStack() {
        val nav = SettingsNavState()
        nav.navigate(appearance)
        nav.navigate(accountOne)
        nav.navigate(SettingsRoute.Root)
        assertEquals(listOf(SettingsRoute.Root), nav.backStack)
    }

    @Test
    fun navigateDuplicateTopRouteIsNoOp() {
        val nav = SettingsNavState()
        nav.navigate(SettingsRoute.Accounts)
        nav.navigate(SettingsRoute.Accounts)
        assertEquals(listOf(SettingsRoute.Root, SettingsRoute.Accounts), nav.backStack)
    }

    @Test
    fun backPopsUntilRoot() {
        val nav = SettingsNavState()
        nav.navigate(privacy)
        nav.navigate(accountOne)
        assertTrue(nav.back())
        assertEquals(listOf(SettingsRoute.Root, privacy), nav.backStack)
        assertTrue(nav.back())
        assertEquals(listOf(SettingsRoute.Root), nav.backStack)
    }

    @Test
    fun backAtRootReturnsFalse() {
        val nav = SettingsNavState()
        assertFalse(nav.back())
        assertEquals(listOf(SettingsRoute.Root), nav.backStack)
    }

    @Test
    fun resetReturnsToRoot() {
        val nav = SettingsNavState()
        nav.navigate(SettingsRoute.Accounts)
        nav.reset()
        assertEquals(listOf(SettingsRoute.Root), nav.backStack)
    }

    @Test
    fun fromStackRestoresStackAndFallsBackForEmpty() {
        val nav = SettingsNavState.fromStack(listOf(SettingsRoute.Root, appearance))
        assertEquals(listOf(SettingsRoute.Root, appearance), nav.backStack)
        assertEquals(listOf(SettingsRoute.Root), SettingsNavState.fromStack(emptyList()).backStack)
    }

    // --- route tokens (saver payload) ---------------------------------------

    @Test
    fun routeTokensRoundTrip() {
        val routes = listOf(
            SettingsRoute.Root,
            appearance,
            privacy,
            SettingsRoute.Accounts,
            accountOne,
            SettingsRoute.AccountSettings(42L),
        )
        routes.forEach { route ->
            assertEquals(route, settingsRouteFromToken(route.toToken()))
        }
    }

    @Test
    fun garbageTokensReturnNull() {
        assertNull(settingsRouteFromToken("bogus"))
        assertNull(settingsRouteFromToken("category:NOT_A_CATEGORY"))
        assertNull(settingsRouteFromToken("account:not-a-number"))
        assertNull(settingsRouteFromToken(""))
    }

    @Test
    fun stackRoundTripsThroughSaverTokens() {
        // The saver's payload is the token list; this exercises the exact
        // save/restore pipeline SettingsNavSaver composes.
        val nav = SettingsNavState()
        nav.navigate(privacy)
        nav.navigate(accountOne)

        val tokens = nav.backStack.map { it.toToken() }
        val restored = SettingsNavState.fromStack(tokens.mapNotNull { settingsRouteFromToken(it) })

        assertEquals(nav.backStack, restored.backStack)
    }

    @Test
    fun saverRestoresTheRootForGarbageTokens() {
        val restored = SettingsNavState.fromStack(
            listOf("bogus", "root").mapNotNull { settingsRouteFromToken(it) },
        )
        assertEquals(listOf(SettingsRoute.Root), restored.backStack)
    }
}
