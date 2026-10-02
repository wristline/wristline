package dev.wristline.watch

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation3.rememberSwipeDismissableSceneStrategy
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.ui.AccountScreen
import dev.wristline.watch.ui.AccountsScreen
import dev.wristline.watch.ui.AddressScreen
import dev.wristline.watch.ui.AskHistoryScreen
import dev.wristline.watch.ui.AskScreen
import dev.wristline.watch.ui.CodeScreen
import dev.wristline.watch.ui.FailureNotice
import dev.wristline.watch.ui.NotifyScreen
import dev.wristline.watch.ui.RequestScreen
import dev.wristline.watch.ui.SessionDetailScreen
import dev.wristline.watch.ui.SessionListScreen
import dev.wristline.watch.ui.SettingsScreen
import dev.wristline.watch.ui.UsageScreen
import dev.wristline.watch.ui.WelcomeScreen
import dev.wristline.watch.ui.WristlineTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/** Navigation keys. The onboarding steps are separate keys so a swipe goes back one step. */
@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Welcome : Route

    @Serializable
    data object Notify : Route

    @Serializable
    data object Address : Route

    /** Pairing screen for the bridge at [url]. */
    @Serializable
    data class Code(val url: String) : Route

    @Serializable
    data object Sessions : Route

    @Serializable
    data class Session(val id: String) : Route

    @Serializable
    data class Request(val id: String) : Route

    @Serializable
    data object Usage : Route

    @Serializable
    data class Ask(val id: String) : Route

    @Serializable
    data object Asks : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object Accounts : Route

    /** One account, by its key in the AccountBook (`provider:id`). */
    @Serializable
    data class Account(val key: String) : Route
}

/** Saves the back stack's [Route]s by their serial names, without reflection (R8 renames classes). */
@OptIn(ExperimentalSerializationApi::class)
internal val routeModule = SerializersModule { polymorphic(NavKey::class) { subclassesOfSealed<Route>() } }

/**
 * The screens a notification opens above the session list: a request over its session when the
 * notification names one, so Back goes request, session, list.
 */
internal fun notificationTarget(requestId: String?, sessionId: String?): List<Route>? = when {
    requestId != null -> listOfNotNull(sessionId?.let(Route::Session), Route.Request(requestId))
    sessionId != null -> listOf(Route.Session(sessionId))
    else -> null
}

/**
 * [stack] with [target] opened on top. A session or request on top gives way to it; any other
 * screen stays below, so an Ask screen a notification covers is not popped (that would cancel its
 * question, see AskCanceller).
 */
internal fun notificationStack(stack: List<NavKey>, target: List<Route>): List<NavKey> =
    stack.dropLastWhile { it is Route.Session || it is Route.Request } + target

/**
 * Makes this back stack [keys], keeping the entries both share at the bottom: only the screens
 * that go away are popped (and their ViewModels cleared).
 */
internal fun MutableList<NavKey>.replaceWith(keys: List<NavKey>) {
    val same = zip(keys).takeWhile { (old, new) -> old == new }.size
    subList(same, size).clear()
    addAll(keys.drop(same))
}

/**
 * Root composable. [openTarget] comes from a notification tap (a request or a session) and is
 * opened once. AppScaffold shows the TimeText above every screen; each screen brings its own
 * ScreenScaffold.
 */
@Composable
fun App(openTarget: List<Route>?, onOpened: () -> Unit) {
    val watchColors by Bridge.prefs.watchColorsState.collectAsStateWithLifecycle()
    WristlineTheme(followWatchColors = watchColors) {
        AppScaffold {
            val backStack = rememberNavBackStack(
                SavedStateConfiguration { serializersModule = routeModule },
                if (Bridge.prefs.isPaired) Route.Sessions else Route.Welcome,
            )
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            NavDisplay(
                backStack = backStack,
                // The ViewModel decorator clears an entry's ViewModels when it is popped (AskCanceller).
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                sceneStrategies = listOf(rememberSwipeDismissableSceneStrategy()),
                entryProvider = entryProvider {
                    entry<Route.Welcome> {
                        WelcomeScreen(
                            onSetUp = {
                                Bridge.stopDemo()
                                val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                                    PackageManager.PERMISSION_GRANTED
                                backStack.add(if (granted) Route.Address else Route.Notify)
                            },
                            onDemo = {
                                scope.launch {
                                    Bridge.startDemo()
                                    // A second tap while the demo data loads must not stack a second list.
                                    if (backStack.lastOrNull() != Route.Sessions) backStack.add(Route.Sessions)
                                }
                            },
                        )
                    }
                    entry<Route.Notify> {
                        NotifyScreen(onNext = { backStack.replaceWith(backStack.takeWhile { it != Route.Notify } + Route.Address) })
                    }
                    entry<Route.Address> {
                        AddressScreen(onFound = { backStack.add(Route.Code(it)) }, onPairedWithToken = { backStack.showSessions() })
                    }
                    entry<Route.Code> { key ->
                        CodeScreen(baseUrl = key.url, onPaired = { backStack.showSessions() })
                    }
                    entry<Route.Sessions> {
                        SessionListScreen(
                            onSession = { backStack.add(Route.Session(it)) },
                            onRequest = { backStack.add(Route.Request(it)) },
                            onUsage = { backStack.add(Route.Usage) },
                            onAsk = { backStack.add(Route.Ask(it)) },
                            onAskHistory = { backStack.add(Route.Asks) },
                            onSettings = { backStack.add(Route.Settings) },
                            onRepair = { backStack.add(Route.Code(Bridge.prefs.baseUrl)) },
                        )
                    }
                    entry<Route.Session> { key ->
                        SessionDetailScreen(sessionId = key.id, onRespond = { backStack.add(Route.Request(it)) })
                    }
                    entry<Route.Request> { key ->
                        // This entry, not whatever is on top: a notification may have opened a screen over it.
                        RequestScreen(requestId = key.id, onDone = { backStack.remove(key) })
                    }
                    entry<Route.Usage> { UsageScreen() }
                    entry<Route.Ask> { key ->
                        // A new question from this screen replaces it, so a swipe back lands on the list.
                        AskScreen(
                            askId = key.id,
                            onReplaced = { id -> backStack.replaceWith(backStack.map { if (it == key) Route.Ask(id) else it }) },
                        )
                    }
                    entry<Route.Asks> { AskHistoryScreen(onAsk = { backStack.add(Route.Ask(it)) }) }
                    entry<Route.Settings> {
                        SettingsScreen(
                            onAddress = { backStack.add(Route.Address) },
                            onAccounts = { backStack.add(Route.Accounts) },
                            onRepair = { backStack.add(Route.Code(Bridge.prefs.baseUrl)) },
                            onPaired = { backStack.showSessions() },
                            onSignedOut = { backStack.replaceWith(listOf(Route.Welcome)) },
                        )
                    }
                    entry<Route.Accounts> { AccountsScreen(onAccount = { backStack.add(Route.Account(it)) }) }
                    entry<Route.Account> { key -> AccountScreen(key.key) }
                },
            )

            FailureNotice()

            LaunchedEffect(openTarget) {
                if (openTarget == null) return@LaunchedEffect
                if (Bridge.prefs.isPaired) backStack.replaceWith(notificationStack(backStack, openTarget))
                onOpened()
            }
        }
    }
}

/** Replaces the whole back stack with the session list (after pairing). */
private fun MutableList<NavKey>.showSessions() = replaceWith(listOf(Route.Sessions))
