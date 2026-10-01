package dev.wristline.watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.tooling.preview.devices.WearDevices
import dev.wristline.watch.data.Account
import dev.wristline.watch.data.Ask
import dev.wristline.watch.data.AskStatus
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
    // A provider without its own badge: the outlined first letter.
    Session("gemini:4", "gemini", "Summarize the release notes", "/home/dev/docs", SessionStatus.IDLE, "2026-09-29T10:05:00Z"),
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

// The list's limit card: two Claude sessions and one Codex session; Claude's 5-hour window resets
// in 2:13, Codex's primary (here weekly) in 3 days.
private val listSessions = sessions.take(3)
private val listUsage = listOf(
    usage[0].copy(windows = listOf(UsageWindow("5h", 42.0, "2026-09-29T15:13:00Z", 300), usage[0].windows[1])),
    Usage(ProviderId.CODEX, "2026-09-29T12:57:00Z", listOf(UsageWindow("primary", 93.0, "2026-10-02T16:12:00Z", 10_080))),
)

// The widest lines: 100% on both, 23:59 and 30 days to the resets, 12 sessions each.
private val fullSessions = List(24) { i ->
    val provider = if (i < 12) ProviderId.CLAUDE_CODE else ProviderId.CODEX
    Session("$provider:full$i", provider, "Session ${i + 1}", "/home/dev/repo$i", SessionStatus.IDLE, "2026-09-29T12:00:00Z")
}
private val fullUsage = listOf(
    Usage(ProviderId.CLAUDE_CODE, "2026-09-29T12:56:00Z", listOf(UsageWindow("5h", 100.0, "2026-09-30T12:59:30Z", 300))),
    Usage(ProviderId.CODEX, "2026-09-29T12:57:00Z", listOf(UsageWindow("primary", 100.0, "2026-10-29T14:00:00Z", 43_200))),
)

// Two Claude accounts (one only estimated) and one Codex account: labels appear on the Usage screen.
private val me = Account("acc-me", "me@gmail.com")
private val school = Account("acc-school", "school", estimated = true)
private val codexSchool = Account("chatgpt-school", "school.account@university.ac.kr")

private val accountUsage = listOf(
    usage[0].copy(account = me),
    Usage(
        ProviderId.CLAUDE_CODE, "2026-09-29T12:40:00Z",
        listOf(UsageWindow("5h", 10.0, "2026-09-29T14:30:00Z", 300), UsageWindow("7d", 3.0, "2026-10-02T09:00:00Z", 10_080)),
        school,
    ),
    usage[1].copy(account = codexSchool),
)

// The Usage screen's rows: Claude's 5-hour window resets in 2h 13m and its weekly one in 3d 4h, its
// Opus window has no reset time; Codex's primary (here weekly) resets in 5 days, its secondary reset
// two minutes ago.
private val detailUsage = listOf(
    Usage(
        ProviderId.CLAUDE_CODE, "2026-09-29T12:56:00Z",
        listOf(
            UsageWindow("5h", 42.0, "2026-09-29T15:13:00Z", 300),
            UsageWindow("7d", 18.0, "2026-10-02T17:00:00Z", 10_080),
            UsageWindow("7d_opus", 30.0, label = "7d Opus"),
        ),
        me,
    ),
    Usage(
        ProviderId.CODEX, "2026-09-29T12:57:00Z",
        listOf(UsageWindow("primary", 93.0, "2026-10-04T13:20:00Z", 10_080), UsageWindow("secondary", 100.0, "2026-09-29T12:58:00Z", 43_200)),
        codexSchool,
    ),
)

private val items = SessionItems(
    items = listOf(
        Item(1, ItemKind.USER, "2026-09-29T12:41:00Z", "Refactor the auth middleware to use the new token service."),
        Item(2, ItemKind.ASSISTANT, "2026-09-29T12:41:08Z", "I'll read the current middleware and its tests first."),
        Item(3, ItemKind.TOOL, "2026-09-29T12:41:10Z", "Read src/middleware/auth.ts", "142 lines"),
        Item(4, ItemKind.TOOL, "2026-09-29T12:50:40Z", "Bash npm run lint", "1 error", error = true),
        Item(5, ItemKind.NOTICE, "2026-09-29T12:52:00Z", "Context compacted"),
        Item(6, ItemKind.TOOL, "2026-09-29T12:58:10Z", "Bash npm run test", pending = true),
        Item(7, ItemKind.ASSISTANT, "2026-09-29T12:58:30Z", "Tests are running; I'll report once they pass."),
    ),
    hasMore = true,
    loaded = true,
)

private val asks = listOf(
    Ask(
        "ask-1", ProviderId.CLAUDE_CODE, "What does a mutex do?", AskStatus.DONE,
        answer = "A mutex is a lock: only one thread can hold it at a time, so the code it guards runs by one thread at once. " +
            "Others wait until it is released. It prevents two threads from changing the same data together.",
        model = "Haiku 4.5", durationMs = 2_926, createdAt = "2026-09-29T12:59:30Z",
    ),
    Ask("ask-2", ProviderId.CODEX, "뮤텍스가 뭐야?", AskStatus.RUNNING, createdAt = "2026-09-29T12:59:00Z"),
    Ask("ask-3", ProviderId.CODEX, "Explain a semaphore", AskStatus.ERROR, durationMs = 90_000, error = "timeout", createdAt = "2026-09-29T12:40:00Z"),
)

// A short answer, so the icon row under it is on screen.
private val shortAsk = asks[0].copy(answer = "A lock: one thread holds it at a time; the rest wait.")

// A conversation: the first ask's id names the thread, the follow-up continues it.
private val thread = listOf(
    shortAsk.copy(threadId = "ask-1"),
    Ask(
        "ask-4", ProviderId.CLAUDE_CODE, "And a semaphore?", AskStatus.DONE,
        answer = "A counter: it lets up to N holders in at once.",
        model = "Haiku 4.5", durationMs = 2_100, createdAt = "2026-09-29T12:59:50Z", threadId = "ask-1",
    ),
)

@Composable
private fun Frame(content: @Composable () -> Unit) {
    WristlineTheme { AppScaffold { content() } }
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

// The icon row (Settings, Ask, recent questions) heads the list; the request banner sits right
// under it, then the limit card, a line per provider where Codex's 93% is yellow (near its
// limit), and the two-line session cards.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SessionListPreview() = Frame {
    SessionListContent(
        Conn.Online, listSessions, listOf(permission, question), listUsage, { now },
        onSession = {}, onRequest = {}, onUsage = {}, onAsk = {}, onAskHistory = {}, onSettings = {}, onRetry = {}, onRepair = {},
    )
}

// Without a request the limit card follows the icon row directly.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun SessionListKoPreview() = Frame {
    SessionListContent(
        Conn.Online, listSessions, emptyList(), listUsage, { now },
        onSession = {}, onRequest = {}, onUsage = {}, onAsk = {}, onAskHistory = {}, onSettings = {}, onRetry = {}, onRepair = {},
    )
}

// The widest limit lines still fit on one line each, in red.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SessionListLimitsFullPreview() = Frame {
    SessionListContent(
        Conn.Online, fullSessions, emptyList(), fullUsage, { now },
        onSession = {}, onRequest = {}, onUsage = {}, onAsk = {}, onAskHistory = {}, onSettings = {}, onRetry = {}, onRepair = {},
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun SessionListLimitsFullKoPreview() = Frame {
    SessionListContent(
        Conn.Online, fullSessions, emptyList(), fullUsage, { now },
        onSession = {}, onRequest = {}, onUsage = {}, onAsk = {}, onAskHistory = {}, onSettings = {}, onRetry = {}, onRepair = {},
    )
}

// Sessions but no usage yet: a dash for each percentage, no countdowns; the provider without its
// own badge gets a line too.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SessionListNoUsagePreview() = Frame {
    SessionListContent(
        Conn.Online, sessions, emptyList(), emptyList(), { now },
        onSession = {}, onRequest = {}, onUsage = {}, onAsk = {}, onAskHistory = {}, onSettings = {}, onRetry = {}, onRepair = {},
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun SessionListUnreachablePreview() = Frame {
    SessionListContent(
        Conn.Unreachable(System.currentTimeMillis() + 12_000), sessions, emptyList(), usage, { now },
        onSession = {}, onRequest = {}, onUsage = {}, onAsk = {}, onAskHistory = {}, onSettings = {}, onRetry = {}, onRepair = {},
    )
}

@Composable
private fun AskFrame(thread: List<Ask>?, speaking: Boolean = false) = Frame {
    AskContent(thread, sending = false, speaking = speaking, onCancel = {}, onAgain = {}, onOther = {}, onSpeak = {}, onFollowUp = {})
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun AskRunningPreview() = AskFrame(listOf(asks[1]))

// The answer, then the icon row: ask again, ask Codex, read aloud, follow-up.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun AskDonePreview() = AskFrame(listOf(asks[0]))

// Reading aloud: the speaker is a stop square.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun AskSpeakingPreview() = AskFrame(listOf(shortAsk), speaking = true)

// Two questions in one thread, the newest at the bottom.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun AskThreadPreview() = AskFrame(thread)

// A follow-up still running: its spinner and Cancel sit under its question, no icon row yet.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun AskThreadRunningKoPreview() = AskFrame(listOf(thread[0], asks[1].copy(provider = ProviderId.CLAUDE_CODE, threadId = "ask-1")))

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun AskErrorKoPreview() = AskFrame(listOf(asks[2]))

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun AskGoneKoPreview() = AskFrame(null)

// One card per conversation: the two-question thread shows its count, the others their answer or status.
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun AskHistoryPreview() = Frame { AskHistoryContent(thread.asReversed() + asks.drop(1), { now }, onAsk = {}, onDelete = {}) }

// Edge gauges: context 60% on the left, Codex's primary window at 93% (yellow) on the right. The
// top text is the model and effort; the newest card rests near the middle, above the actions.
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

// A waiting request: a compact [Respond] replaces the speak and type buttons. Without a model or
// effort the top text names the provider.
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
private fun UsagePreview() = Frame { UsageContent(detailUsage) { now } }

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun UsageKoPreview() = Frame { UsageContent(detailUsage) { now } }

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun UsageAccountsPreview() = Frame { UsageContent(accountUsage) { now } }

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, locale = "ko")
@Composable
private fun UsageAccountsKoPreview() = Frame { UsageContent(accountUsage) { now } }

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SettingsPreview() = Frame {
    SettingsContent(
        demo = false, address = "https://example.tail0000.ts.net", paired = true, deviceName = "Galaxy Watch Ultra",
        showToolCalls = false, askProvider = ProviderId.CLAUDE_CODE, monitoring = false, canMonitor = true, notificationsOff = true,
        busy = false, tokenNeedsAddress = false,
        onAddress = {}, onDeviceName = {}, onShowToolCalls = {}, onAskProvider = {}, onMonitoring = {}, onNotificationSettings = {}, onRepair = {}, onToken = {},
        onDisconnect = {}, onExitDemo = {},
    )
}
