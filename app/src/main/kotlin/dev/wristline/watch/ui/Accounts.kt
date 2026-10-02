package dev.wristline.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextDefaults
import androidx.wear.compose.material3.TextToggleButton
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.touchTargetAwareSize
import dev.wristline.watch.R
import dev.wristline.watch.data.AccountBook
import dev.wristline.watch.data.AccountLook
import dev.wristline.watch.data.AccountStyle
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.MARK_EMOJI
import dev.wristline.watch.data.Mark
import dev.wristline.watch.data.MarkType
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.accountKey
import dev.wristline.watch.data.accountsOf
import dev.wristline.watch.data.edit
import dev.wristline.watch.data.move
import dev.wristline.watch.data.ordered
import dev.wristline.watch.data.reset
import dev.wristline.watch.data.shownByOthers

/**
 * The colors an account's mark can have: its disc and the glyph on it, each pair at least 4.5:1
 * (ThemeColorsTest). White by default; the others from the Okabe-Ito set, which stays apart for
 * color-blind eyes, without its yellow and vermilion, which would read as the 80% and 95% limit
 * colors. [id] is what Prefs stores (see [dev.wristline.watch.data.AccountStyle.color]).
 */
internal enum class MarkColor(val id: String?, val disc: Color, val glyph: Color) {
    WHITE(null, Color.White, Color.Black),
    SKY("sky", Color(0xFF56B4E9), Color.Black),
    GREEN("green", Color(0xFF009E73), Color.Black),
    ORANGE("orange", Color(0xFFE69F00), Color.Black),
    PURPLE("purple", Color(0xFFCC79A7), Color.Black),
    BLUE("blue", Color(0xFF0072B2), Color.White),
}

/** The palette entry of [id]; white for null or one this version does not know. */
internal fun markColor(id: String?): MarkColor = MarkColor.entries.firstOrNull { it.id == id } ?: MarkColor.WHITE

@Composable
private fun colorName(color: MarkColor): String = stringResource(
    when (color) {
        MarkColor.WHITE -> R.string.account_color_white
        MarkColor.SKY -> R.string.account_color_sky
        MarkColor.GREEN -> R.string.account_color_green
        MarkColor.ORANGE -> R.string.account_color_orange
        MarkColor.PURPLE -> R.string.account_color_purple
        MarkColor.BLUE -> R.string.account_color_blue
    },
)

/** The marks the picker offers: [MARK_EMOJI], then letters A–Z, then digits 1–9. */
internal val PICKER_MARKS: List<Mark> =
    MARK_EMOJI.map { Mark(MarkType.EMOJI, it) } +
        ('A'..'Z').map { Mark(MarkType.LETTER, it.toString()) } +
        ('1'..'9').map { Mark(MarkType.DIGIT, it.toString()) }

/**
 * The picker's tabs, emoji first: each offers the [PICKER_MARKS] of its [type] in rows of
 * [columns]; letters four across (Auto first), as many 48dp targets as the round screen's middle holds.
 */
internal enum class MarkTab(val type: String, val label: String, val columns: Int) {
    EMOJI(MarkType.EMOJI, "\uD83D\uDE42", 3),
    LETTERS(MarkType.LETTER, "ABC", 4),
    DIGITS(MarkType.DIGIT, "123", 3),
    ;

    val marks: List<Mark> get() = PICKER_MARKS.filter { it.type == type }
}

/** The tab the picker opens on: the picked mark's, else emoji. */
internal fun tabOf(mark: Mark?): MarkTab = MarkTab.entries.firstOrNull { it.type == mark?.type } ?: MarkTab.EMOJI

/** A picker button: the minimum touch target. */
private val PICK_SIZE = 48.dp
private val PICK_GAP = 8.dp

/** Between four picker buttons in a row: what is left of the round screen's middle. */
private val PICK_GAP_TIGHT = 2.dp

/** A picker tab: room for `ABC` in three that fit across the round screen low in the list. */
private val TAB_WIDTH = 56.dp

/**
 * A color swatch: six in a row fit across the round screen under the name. Smaller than the
 * minimum touch target, which Compose's hit test extends each to (the nearest wins where they meet).
 */
private val SWATCH_SIZE = 30.dp
private val SWATCH_GAP = 3.dp

/** The selected pick's rings ([selectedRing]): the outer and the inner one. */
private val RING_OUTER = 3.dp
private val RING_INNER = 2.dp

/** The badge's enlarged copy beside the actual size in the account's preview. */
private const val PREVIEW_SCALE = 2f

/**
 * The accounts the Accounts screen lists: per provider with more than one ([markedProviders]), in
 * provider order, the keys of its accounts among [usage] and [sessions] in [book]'s order.
 */
internal fun accountGroups(book: AccountBook, usage: List<Usage>, sessions: List<Session>): List<Pair<String, List<String>>> {
    val marked = markedProviders(usage, sessions)
    val visible = accountsOf(usage, sessions).mapTo(HashSet()) { (provider, account) -> accountKey(provider, account) }
    return marked.sorted().map { provider -> provider to book.ordered(provider).filter { it in visible } }.filter { it.second.isNotEmpty() }
}

@Composable
internal fun AccountsScreen(onAccount: (String) -> Unit) {
    val usage by Bridge.usage.collectAsStateWithLifecycle()
    val sessions by Bridge.sessions.collectAsStateWithLifecycle()
    val accounts by Bridge.prefs.accounts.collectAsStateWithLifecycle()
    AccountsContent(usage, sessions, accounts, onAccount) { key, by, shown -> Bridge.prefs.updateAccounts { it.move(key, by, shown) } }
}

/**
 * Per provider with more than one account, its accounts in the order the app shows them: each its
 * badge as the cards show it, its name and, for the primary home's, `Default`; under each, buttons
 * that move it up and down. A tap opens the account ([AccountScreen]).
 */
@Composable
internal fun AccountsContent(
    usage: List<Usage>,
    sessions: List<Session>,
    accounts: AccountBook,
    onAccount: (String) -> Unit,
    onMove: (key: String, by: Int, shown: List<String>) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val book = rememberAccountBook(accounts, usage, sessions)
    val groups = remember(book, usage, sessions) { accountGroups(book, usage, sessions) }
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(listState),
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
        ) {
            item(key = "title") { Header(stringResource(R.string.settings_accounts), spec) }
            for ((provider, keys) in groups) {
                item(key = "provider/$provider") {
                    ListSubHeader(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) { Text(providerLabel(provider)) }
                }
                keys.forEachIndexed { i, key ->
                    val style = book.accounts.getValue(key)
                    item(key = key) {
                        FilledTonalButton(
                            onClick = { onAccount(key) },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItemCalmly(this),
                            transformation = SurfaceTransformation(spec),
                            icon = { BadgeWithMark(provider, style.look(), decorative = true) },
                            label = { Text(style.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = if (style.primary) {
                                { Text(stringResource(R.string.accounts_primary)) }
                            } else {
                                null
                            },
                        )
                    }
                    item(key = "$key/move") {
                        Row(
                            Modifier.fillMaxWidth().edgeTransform(this, spec).animateItemCalmly(this),
                            horizontalArrangement = Arrangement.spacedBy(PICK_GAP, Alignment.CenterHorizontally),
                        ) {
                            MoveButton(R.drawable.ic_arrow_up, stringResource(R.string.accounts_move_up, style.name), enabled = i > 0) {
                                onMove(key, -1, keys)
                            }
                            MoveButton(R.drawable.ic_arrow_down, stringResource(R.string.accounts_move_down, style.name), enabled = i < keys.lastIndex) {
                                onMove(key, 1, keys)
                            }
                        }
                    }
                }
            }
            item(key = "end") { EndSpace(spec) }
        }
    }
}

@Composable
private fun MoveButton(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.touchTargetAwareSize(PICK_SIZE)) {
        Icon(painterResource(icon), description, Modifier.size(IconButtonDefaults.iconSizeFor(PICK_SIZE)))
    }
}

private fun AccountStyle.look(): AccountLook = AccountLook(glyph, color, name)

@Composable
internal fun AccountScreen(key: String) {
    val usage by Bridge.usage.collectAsStateWithLifecycle()
    val sessions by Bridge.sessions.collectAsStateWithLifecycle()
    val accounts by Bridge.prefs.accounts.collectAsStateWithLifecycle()
    val book = rememberAccountBook(accounts, usage, sessions)
    val update = { change: (AccountBook) -> AccountBook -> Bridge.prefs.updateAccounts(change) }
    val rename = rememberTextInput(stringResource(R.string.account_nickname)) { name ->
        update { it.edit(key) { style -> style.copy(nickname = name) } }
    }
    AccountContent(
        book,
        key,
        onMark = { mark -> update { it.edit(key) { style -> style.copy(mark = mark) } } },
        onColor = { color -> update { it.edit(key) { style -> style.copy(color = color) } } },
        onRename = rename,
        onReset = { update { it.reset(key) } },
    )
}

/**
 * One account. Pinned at the top, its badge at the size the cards show it and enlarged, so every
 * choice shows there at once. Under it, scrolling: its name (the nickname, in place of the label
 * everywhere; a tap renames it) with its provider and `Default` beside it; its color
 * ([MarkColor]), which goes with every mark, in one row; its mark in tabs ([MarkTab]: Auto among
 * the letters; the ones its provider's other accounts show dimmed); Reset to auto. Kept tight so
 * the preview, name, colors, tabs and the first row of marks show at once. A choice applies at
 * once, as the Wear settings screens do.
 */
@Composable
internal fun AccountContent(
    book: AccountBook,
    key: String,
    onMark: (Mark?) -> Unit,
    onColor: (String?) -> Unit,
    onRename: () -> Unit,
    onReset: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val style = book.accounts[key]
    // Who shows each of the other glyphs, for the dimmed picks' description.
    val others = remember(book, key) {
        style?.let { own -> book.accounts.filter { (k, s) -> k != key && s.provider == own.provider }.values.associate { it.glyph to it.name } }.orEmpty()
    }
    val used = remember(book, key) { style?.let { book.shownByOthers(it.provider, key) }.orEmpty() }
    var tab by rememberSaveable { mutableStateOf(tabOf(style?.mark)) }
    ScreenScaffold(scrollState = listState) { contentPadding ->
        val direction = LocalLayoutDirection.current
        Column(Modifier.fillMaxSize()) {
            // Outside the list, so it stays while the picker scrolls; the list clipped, so nothing
            // scrolls over it (or takes its touches).
            if (style != null) AccountPreview(style, Modifier.padding(top = contentPadding.calculateTopPadding()))
            TransformingLazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).clipToBounds(),
                contentPadding = PaddingValues(
                    start = contentPadding.calculateStartPadding(direction),
                    top = if (style == null) contentPadding.calculateTopPadding() else 0.dp,
                    end = contentPadding.calculateEndPadding(direction),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
                flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(listState),
                rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
            ) {
                if (style == null) {
                    item(key = "title") { Header(stringResource(R.string.settings_accounts), spec) }
                    return@TransformingLazyColumn
                }
                item(key = "name") {
                    val rename = stringResource(R.string.account_rename)
                    val provider = providerLabel(style.provider)
                    val caption = if (style.primary) "$provider · ${stringResource(R.string.accounts_primary)}" else provider
                    CompactButton(
                        onClick = onRename,
                        // The caption is said with the preview.
                        modifier = Modifier.transformedHeight(this, spec).semantics { onClick(label = rename) { onRename(); true } },
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        transformation = SurfaceTransformation(spec),
                        icon = { Icon(painterResource(R.drawable.ic_edit), null, Modifier.size(ButtonDefaults.ExtraSmallIconSize)) },
                        label = {
                            Text(
                                style.name,
                                Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                " · $caption",
                                Modifier.clearAndSetSemantics {},
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        },
                    )
                }
                item(key = "colors") {
                    val ring = MaterialTheme.colorScheme.onSurface
                    val gap = MaterialTheme.colorScheme.background
                    PickRow(this, spec, Modifier.selectableGroup(), gap = SWATCH_GAP) {
                        for (color in MarkColor.entries) {
                            val checked = markColor(style.color) == color
                            val name = colorName(color)
                            Box(
                                Modifier
                                    .size(SWATCH_SIZE)
                                    .clip(CircleShape)
                                    .background(color.disc)
                                    .selectable(selected = checked, role = Role.RadioButton) { onColor(color.id) }
                                    .semantics { contentDescription = name }
                                    .selectedRing(checked, ring, gap),
                                contentAlignment = Alignment.Center,
                            ) { Text(style.glyph, color = color.glyph, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                        }
                    }
                }
                item(key = "tabs") {
                    PickRow(this, spec, Modifier.selectableGroup(), gap = PICK_GAP_TIGHT * 2) {
                        for (option in MarkTab.entries) {
                            val selected = tab == option
                            val name = stringResource(option.description)
                            CompactButton(
                                onClick = { tab = option },
                                modifier = Modifier.width(TAB_WIDTH).semantics {
                                    this.selected = selected
                                    contentDescription = name
                                },
                                colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                                label = { Text(option.label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1) },
                            )
                        }
                    }
                }
                // Auto first among the letters: the glyph the watch picked, with a caption.
                val picks: List<Mark?> = if (tab == MarkTab.LETTERS) listOf(null) + tab.marks else tab.marks
                picks.chunked(tab.columns).forEachIndexed { row, marks ->
                    item(key = "marks/${tab.name}/$row") {
                        PickRow(this, spec, gap = if (tab.columns > 3) PICK_GAP_TIGHT else PICK_GAP) {
                            for (mark in marks) {
                                if (mark == null) {
                                    AutoButton(style.auto, checked = style.mark == null) { onMark(null) }
                                    continue
                                }
                                val taken = mark.value in used
                                val description = if (taken) stringResource(R.string.account_mark_used, mark.value, others[mark.value].orEmpty()) else null
                                val checked = style.mark?.value == mark.value
                                TextToggleButton(
                                    checked = checked,
                                    onCheckedChange = { on -> onMark(mark.takeIf { on }) },
                                    enabled = !taken,
                                    modifier = Modifier
                                        .touchTargetAwareSize(PICK_SIZE)
                                        .selectedRing(checked, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.background)
                                        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
                                ) { Text(mark.value, style = MaterialTheme.typography.titleMedium) }
                            }
                        }
                    }
                }
                item(key = "reset") {
                    Button(
                        onClick = onReset,
                        enabled = style.mark != null || style.color != null || style.nickname != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, spec)
                            .minimumVerticalContentPadding(top = 0.dp, bottom = TextDefaults.minimumBottomListContentPadding),
                        transformation = SurfaceTransformation(spec),
                        label = { Text(stringResource(R.string.account_reset)) },
                    )
                }
            }
        }
    }
}

private val MarkTab.description: Int
    get() = when (this) {
        MarkTab.EMOJI -> R.string.account_tab_emoji
        MarkTab.LETTERS -> R.string.account_tab_letters
        MarkTab.DIGITS -> R.string.account_tab_digits
    }

/** The account's badge at the size the cards show it and enlarged; read out as `Preview, Codex Work, Default`. */
@Composable
private fun AccountPreview(style: AccountStyle, modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.account_preview) + ", " + providerLabel(style.provider) + " " + style.name +
        (if (style.primary) ", " + stringResource(R.string.accounts_primary) else "")
    val look = style.look()
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgeWithMark(style.provider, look, decorative = true)
        BadgeWithMark(style.provider, look, scale = PREVIEW_SCALE, decorative = true)
    }
}

/** The letters' first pick: [auto], the glyph the watch picked, over `Auto`; read out as `Auto (W)`. */
@Composable
private fun AutoButton(auto: String, checked: Boolean, onSelect: () -> Unit) {
    val description = stringResource(R.string.account_mark_auto, auto)
    TextToggleButton(
        checked = checked,
        onCheckedChange = { onSelect() },
        modifier = Modifier
            .touchTargetAwareSize(PICK_SIZE)
            .selectedRing(checked, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.background)
            .semantics { contentDescription = description },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(auto, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(stringResource(R.string.account_auto), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/**
 * A selected pick's mark, the same on every color and fill: a [RING_OUTER] ring in [ring] (white)
 * around a [RING_INNER] one in [gap] (the black background), which shows on a white disc as on a
 * blue one; nothing when not [selected].
 */
private fun Modifier.selectedRing(selected: Boolean, ring: Color, gap: Color): Modifier =
    if (!selected) {
        this
    } else {
        drawWithContent {
            drawContent()
            val outer = RING_OUTER.toPx()
            val inner = RING_INNER.toPx()
            val radius = size.minDimension / 2
            drawCircle(ring, radius = radius - outer / 2, style = Stroke(outer))
            drawCircle(gap, radius = radius - outer - inner / 2, style = Stroke(inner))
        }
    }

@Composable
private fun TransformingLazyColumnItemScope.Header(text: String, spec: TransformationSpec) {
    ListHeader(
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
        transformation = SurfaceTransformation(spec),
    ) { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

/** The list's last item: room under the last row, off the round edge. */
@Composable
private fun TransformingLazyColumnItemScope.EndSpace(spec: TransformationSpec) {
    Spacer(
        Modifier.edgeTransform(this, spec).minimumVerticalContentPadding(top = 0.dp, bottom = TextDefaults.minimumBottomListContentPadding),
    )
}

@Composable
private fun PickRow(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
    modifier: Modifier = Modifier,
    gap: Dp = PICK_GAP,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().edgeTransform(scope, spec),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
