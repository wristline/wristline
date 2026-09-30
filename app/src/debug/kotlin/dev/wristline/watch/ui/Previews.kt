package dev.wristline.watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.tooling.preview.devices.WearDevices
import dev.wristline.watch.data.Account
import dev.wristline.watch.data.ContextUsage
import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.Item
import dev.wristline.watch.data.ItemKind
import dev.wristline.watch.data.Option
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Question
import dev.wristline.watch.data.RequestKind
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionItems
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
import java.time.Instant

// Debug-only previews for the round 454 px LARGE_ROUND device (closest preset to the 480 px
// Galaxy Watch Ultra). Screens take plain data here; nothing touches Bridge.

private val now = Instant.parse("2026-09-29T13:00:00Z").toEpochMilli()

private val sessions = listOf(
    Session(
        "claude-code:1", ProviderId.CLAUDE_CODE, "Refactor auth middleware", "/home/dev/api-server",
        SessionStatus.NEEDS_INPUT, "2026-09-29T12:58:00Z", "awaiting_input", ContextUsage(86_000, 200_000),
    ),
    Session(
        "codex:2", ProviderId.CODEX, "Add cursor pagination to /orders", "/home/dev/shop",
        SessionStatus.RUNNING, "2026-09-29T12:57:00Z", context = ContextUsage(156_000, 258_400),
    ),
    Session("claude-code:3", ProviderId.CLAUDE_CODE, "", "/home/dev/web", SessionStatus.IDLE, "2026-09-29T11:20:00Z", "no_tmux"),
)

private val permission = PendingRequest(
    "req-1", "claude-code:1", RequestKind.PERMISSION, "Bash",
    listOf(
        Question(
            "decision", text = "npm run test -- --watch=false",
            options = listOf(
                Option("allow", "Allow"), Option("always", "Always", "Bash(npm run test:*)"),
                Option("deny", "Deny"), Option("defer", "On PC"),
            ),
        ),
    ),
    "2026-09-29T12:58:00Z",
)

private val question = PendingRequest(
    "req-2", "claude-code:3", RequestKind.QUESTION, "Question",
    listOf(
        Question(
            "q1", "Library", "Which date library should the web app use?",
            options = listOf(Option("0", "date-fns", "Tree-shakable functions"), Option("1", "Day.js")),
        ),
        Question("q2", "Scope", "Which parts should be migrated now?", multi = true, options = listOf(Option("0", "Forms"), Option("1", "Reports"))),
    ),
    "2026-09-29T12:51:00Z",
)

private val usage = listOf(
    Usage(
        ProviderId.CLAUDE_CODE, "2026-09-29T12:56:00Z",
        listOf(UsageWindow("5h", 42.0, "2026-09-29T15:00:00Z", 300), UsageWindow("7d", 12.0, "2026-10-03T09:00:00Z", 10_080)),
    ),
    Usage(ProviderId.CODEX, "2026-09-29T12:57:00Z", listOf(UsageWindow("primary", 93.0, "2026-09-29T16:12:00Z", 300))),
)

// Two Claude accounts (one only estimated) and one Codex account: labels appear on cards and chips.
private val me = Account("acc-me", "me@gmail.com")
private val school = Account("acc-school", "school", estimated = true)
private val codexSchool = Account("chatgpt-school", "school.account@university.ac.kr")

private val accountSessions = listOf(
    sessions[0].copy(account = me),
    sessions[1].copy(account = codexSchool),
    sessions[2].copy(account = school),
)

private val accountUsage = listOf(
    usage[0].copy(account = me),
    Usage(
        ProviderId.CLAUDE_CODE, "2026-09-29T12:40:00Z",
        listOf(UsageWindow("5h", 10.0, "2026-09-29T14:30:00Z", 300), UsageWindow("7d", 3.0, "2026-10-02T09:00:00Z", 10_080)),
        school,
    ),
    usage[1].copy(account = codexSchool),
)

private val items = SessionItems(
    items = listOf(
        Item(1, ItemKind.USER, "2026-09-29T12:41:00Z", "Refactor the auth middleware to use the new token service."),
        Item(2, ItemKind.ASSISTANT, "2026-09-29T12:41:08Z", "I'll read the current middleware and its tests first."),
        Item(3, ItemKind.TOOL, "2026-09-29T12:41:10Z", "Read src/middleware/auth.ts", "142 lines"),
        Item(4, ItemKind.TOOL, "2026-09-29T12:50:40Z", "Bash npm run lint", "1 error", error = true),
        Item(5, ItemKind.NOTICE, "2026-09-29T12:52:00Z", "Context compacted"),
        Item(6, ItemKind.TOOL, "2026-09-29T12:58:10Z", "Bash npm run test", pending = true),
    ),
    hasMore = true,
    loaded = true,
)

@Composable
private fun Frame(content: @Composable () -> Unit) {
    MaterialTheme { AppScaffold { content() } }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun WelcomePreview() = Frame { WelcomeScreen(onSetUp = {}, onDemo = {}) }

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun AddressFoundPreview() = Frame {
    AddressContent("https://example.tail0000.ts.net", AddressState.Found, onEnter = {}, onNext = {}, onToken = {})
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun CodePreview() = Frame { CodeContent(busy = false, error = null, onSubmit = {}) }

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun CodeErrorKoPreview() = Frame { CodeContent(busy = false, error = "not_found", onSubmit = {}) }

// Glance card: Codex's 93% takes the error color.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SessionListPreview() = Frame {
    SessionListContent(
        Conn.Online, sessions, listOf(permission, question), usage, { now },
        onSession = {}, onRequest = {}, onUsage = {}, onSettings = {}, onRetry = {}, onRepair = {},
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun SessionListUnreachablePreview() = Frame {
    SessionListContent(
        Conn.Unreachable(System.currentTimeMillis() + 12_000), sessions, emptyList(), usage, { now },
        onSession = {}, onRequest = {}, onUsage = {}, onSettings = {}, onRetry = {}, onRepair = {},
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SessionListAccountsPreview() = Frame {
    SessionListContent(
        Conn.Online, accountSessions, emptyList(), accountUsage, { now },
        onSession = {}, onRequest = {}, onUsage = {}, onSettings = {}, onRetry = {}, onRepair = {},
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun SessionListAccountsKoPreview() = Frame {
    SessionListContent(
        Conn.Online, accountSessions, emptyList(), accountUsage, { now },
        onSession = {}, onRequest = {}, onUsage = {}, onSettings = {}, onRetry = {}, onRepair = {},
    )
}

// Edge gauges: context 60% on the left, Codex's primary window at 93% (error) on the right.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SessionDetailPreview() = Frame {
    SessionDetailContent(
        sessions[1].copy(model = "gpt-5.3-codex", effort = "high"), gone = false, limit = usage[1].windows[0],
        state = items, hasRequest = false, sending = false, outcome = null,
        onEarlier = {}, onAction = {}, onType = {},
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun SessionDetailKoPreview() = Frame {
    SessionDetailContent(
        sessions[0].copy(status = SessionStatus.RUNNING, promptBlock = null, model = "Fable 5.1", effort = "xhigh"),
        gone = false, limit = usage[0].windows[0], state = items, hasRequest = false, sending = false, outcome = null,
        onEarlier = {}, onAction = {}, onType = {},
    )
}

// A waiting request: a compact [Respond] replaces the speak and type buttons.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SessionDetailRespondPreview() = Frame {
    SessionDetailContent(
        sessions[0], gone = false, limit = usage[0].windows[0], state = items, hasRequest = true, sending = false, outcome = null,
        onEarlier = {}, onAction = {}, onType = {},
    )
}

// The newest page could not be fetched and nothing is held: the caption, not a blank list.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun SessionDetailFailedPreview() = Frame {
    SessionDetailContent(
        sessions[1], gone = false, limit = null, state = SessionItems(failed = true), hasRequest = false, sending = false, outcome = null,
        onEarlier = {}, onAction = {}, onType = {},
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun PermissionPreview() = Frame {
    RequestContent(permission, "Refactor auth middleware", sending = false, error = null, onAnswer = {})
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun QuestionPreview() = Frame {
    RequestContent(question, "Pick a date library", sending = false, error = null, onAnswer = {})
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun UsagePreview() = Frame { UsageContent(usage, now) }

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun UsageAccountsPreview() = Frame { UsageContent(accountUsage, now) }

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun UsageAccountsKoPreview() = Frame { UsageContent(accountUsage, now) }

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SettingsPreview() = Frame {
    SettingsContent(
        demo = false, address = "https://example.tail0000.ts.net", paired = true, deviceName = "Galaxy Watch Ultra",
        showToolCalls = false, monitoring = false, canMonitor = true, notificationsOff = true, busy = false, tokenNeedsAddress = false,
        onAddress = {}, onDeviceName = {}, onShowToolCalls = {}, onMonitoring = {}, onNotificationSettings = {}, onRepair = {}, onToken = {},
        onDisconnect = {}, onExitDemo = {},
    )
}
