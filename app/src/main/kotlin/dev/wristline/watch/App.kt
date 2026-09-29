package dev.wristline.watch

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    const val CODE = "onboarding/code"
    const val SESSIONS = "sessions"
    const val SESSION = "session/{id}"
    const val REQUEST = "request/{id}"
    const val USAGE = "usage"
    const val SETTINGS = "settings"

    fun session(id: String) = "session/" + Uri.encode(id)

    fun request(id: String) = "request/" + Uri.encode(id)
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
                                nav.navigate(Route.SESSIONS)
                            }
                        },
                    )
                }
                composable(Route.NOTIFY) {
                    NotifyScreen(onNext = { nav.navigate(Route.ADDRESS) { popUpTo(Route.NOTIFY) { inclusive = true } } })
                }
                composable(Route.ADDRESS) {
                    AddressScreen(onFound = { nav.navigate(Route.CODE) }, onPairedWithToken = { nav.showSessions() })
                }
                composable(Route.CODE) {
                    CodeScreen(onPaired = { nav.showSessions() })
                }
                composable(Route.SESSIONS) {
                    SessionListScreen(
                        onSession = { nav.navigate(Route.session(it)) },
                        onRequest = { nav.navigate(Route.request(it)) },
                        onUsage = { nav.navigate(Route.USAGE) },
                        onSettings = { nav.navigate(Route.SETTINGS) },
                        onRepair = { nav.navigate(Route.CODE) },
                    )
                }
                composable(Route.SESSION) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    SessionDetailScreen(sessionId = id, onRespond = { nav.navigate(Route.request(it)) })
                }
                composable(Route.REQUEST) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    RequestScreen(requestId = id, onDone = { nav.popBackStack() })
                }
                composable(Route.USAGE) { UsageScreen() }
                composable(Route.SETTINGS) {
                    SettingsScreen(
                        onAddress = { nav.navigate(Route.ADDRESS) },
                        onRepair = { nav.navigate(Route.CODE) },
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
