package dev.wristline.watch

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.ui.AddressScreen
import dev.wristline.watch.ui.AskHistoryScreen
import dev.wristline.watch.ui.AskScreen
import dev.wristline.watch.ui.CodeScreen
import dev.wristline.watch.ui.NotifyScreen
import dev.wristline.watch.ui.RequestScreen
import dev.wristline.watch.ui.SessionDetailScreen
import dev.wristline.watch.ui.SessionListScreen
import dev.wristline.watch.ui.SettingsScreen
import dev.wristline.watch.ui.UsageScreen
import dev.wristline.watch.ui.WelcomeScreen
import kotlinx.coroutines.launch

/** Navigation routes. The onboarding steps are separate routes so a swipe goes back one step. */
object Route {
    const val ONBOARDING = "onboarding"
    const val NOTIFY = "onboarding/notify"
    const val ADDRESS = "onboarding/address"
    const val CODE = "onboarding/code/{url}"
    const val SESSIONS = "sessions"
    const val SESSION = "session/{id}"
    const val REQUEST = "request/{id}"
    const val USAGE = "usage"
    const val ASK = "ask/{id}"
    const val ASKS = "asks"
    const val SETTINGS = "settings"

    /** Pairing screen for the bridge at [url]. */
    fun code(url: String) = "onboarding/code/" + Uri.encode(url)

    fun session(id: String) = "session/" + Uri.encode(id)

    fun request(id: String) = "request/" + Uri.encode(id)

    fun ask(id: String) = "ask/" + Uri.encode(id)
}

/**
 * Root composable. [openRoute] comes from a notification tap (a request or a session) and is
 * opened once. AppScaffold shows the TimeText above every screen; each screen brings its own
 * ScreenScaffold.
 */
@Composable
fun App(openRoute: String?, onOpened: () -> Unit) {
    MaterialTheme {
        AppScaffold {
            val nav = rememberSwipeDismissableNavController()
            val start = remember { if (Bridge.prefs.isPaired) Route.SESSIONS else Route.ONBOARDING }
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            SwipeDismissableNavHost(navController = nav, startDestination = start) {
                composable(Route.ONBOARDING) {
                    WelcomeScreen(
                        onSetUp = {
                            Bridge.stopDemo()
                            val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                                PackageManager.PERMISSION_GRANTED
                            nav.navigate(if (granted) Route.ADDRESS else Route.NOTIFY)
                        },
                        onDemo = {
                            scope.launch {
                                Bridge.startDemo()
                                // A second tap while the demo data loads must not stack a second list.
                                nav.navigate(Route.SESSIONS) { launchSingleTop = true }
                            }
                        },
                    )
                }
                composable(Route.NOTIFY) {
                    NotifyScreen(onNext = { nav.navigate(Route.ADDRESS) { popUpTo(Route.NOTIFY) { inclusive = true } } })
                }
                composable(Route.ADDRESS) {
                    AddressScreen(onFound = { nav.navigate(Route.code(it)) }, onPairedWithToken = { nav.showSessions() })
                }
                composable(Route.CODE) { entry ->
                    CodeScreen(baseUrl = entry.arguments?.getString("url").orEmpty(), onPaired = { nav.showSessions() })
                }
                composable(Route.SESSIONS) {
                    SessionListScreen(
                        onSession = { nav.navigate(Route.session(it)) },
                        onRequest = { nav.navigate(Route.request(it)) },
                        onUsage = { nav.navigate(Route.USAGE) },
                        onAsk = { nav.navigate(Route.ask(it)) },
                        onAskHistory = { nav.navigate(Route.ASKS) },
                        onSettings = { nav.navigate(Route.SETTINGS) },
                        onRepair = { nav.navigate(Route.code(Bridge.prefs.baseUrl)) },
                    )
                }
                // A notification tap (launchSingleTop) reuses the entry on top for another id; keyed by
                // the id, the screen starts afresh instead of keeping the last one's draft or dialog.
                composable(Route.SESSION) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    key(id) { SessionDetailScreen(sessionId = id, onRespond = { nav.navigate(Route.request(it)) }) }
                }
                composable(Route.REQUEST) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    key(id) { RequestScreen(requestId = id, onDone = { nav.popBackStack() }) }
                }
                composable(Route.USAGE) { UsageScreen() }
                composable(Route.ASK) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    // A new question from this screen replaces it, so a swipe back lands on the list.
                    AskScreen(askId = id, onReplaced = { nav.navigate(Route.ask(it)) { popUpTo(Route.ASK) { inclusive = true } } })
                }
                composable(Route.ASKS) { AskHistoryScreen(onAsk = { nav.navigate(Route.ask(it)) }) }
                composable(Route.SETTINGS) {
                    SettingsScreen(
                        onAddress = { nav.navigate(Route.ADDRESS) },
                        onRepair = { nav.navigate(Route.code(Bridge.prefs.baseUrl)) },
                        onPaired = { nav.showSessions() },
                        onSignedOut = {
                            nav.navigate(Route.ONBOARDING) { popUpTo(nav.graph.id) { inclusive = true } }
                        },
                    )
                }
            }

            LaunchedEffect(openRoute) {
                if (openRoute == null) return@LaunchedEffect
                if (Bridge.prefs.isPaired) nav.navigate(openRoute) { launchSingleTop = true }
                onOpened()
            }
        }
    }
}

/** Replaces the whole back stack with the session list (after pairing). */
private fun NavHostController.showSessions() {
    navigate(Route.SESSIONS) { popUpTo(graph.id) { inclusive = true } }
}
