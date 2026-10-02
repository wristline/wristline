package dev.wristline.watch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextDefaults
import androidx.wear.compose.material3.TextToggleButton
import androidx.wear.compose.material3.TextToggleButtonDefaults
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

/** The marks the picker offers after Auto, in rows of [PICKER_COLUMNS]: letters A–Z, digits 1–9, then [MARK_EMOJI]. */
internal val PICKER_MARKS: List<Mark> =
    ('A'..'Z').map { Mark(MarkType.LETTER, it.toString()) } +
        ('1'..'9').map { Mark(MarkType.DIGIT, it.toString()) } +
        MARK_EMOJI.map { Mark(MarkType.EMOJI, it) }

private const val PICKER_COLUMNS = 3

/** A picker button: the minimum touch target, so three fit across the round screen's middle with room. */
private val PICK_SIZE = 48.dp
private val PICK_GAP = 8.dp

/** The badge's enlarged copy beside the actual size in the account's preview. */
private const val PREVIEW_SCALE = 4f

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
    val editNickname = rememberTextInput(stringResource(R.string.account_nickname)) { name ->
        update { it.edit(key) { style -> style.copy(nickname = name) } }
    }
    AccountContent(
        book,
        key,
        onMark = { mark -> update { it.edit(key) { style -> style.copy(mark = mark) } } },
        onColor = { color -> update { it.edit(key) { style -> style.copy(color = color) } } },
        onNickname = editNickname,
        onReset = { update { it.reset(key) } },
    )
}

/**
 * One account: its badge at the size the cards show it and enlarged; its mark (Auto, the label's
 * pick, or one of [PICKER_MARKS], the ones its provider's other accounts show dimmed), its color
 * ([MarkColor]), its nickname (in place of the label everywhere), and Reset to auto. A choice
 * applies at once, as the Wear settings screens do.
 */
@Composable
internal fun AccountContent(
    book: AccountBook,
    key: String,
    onMark: (Mark?) -> Unit,
    onColor: (String?) -> Unit,
    onNickname: () -> Unit,
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
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(listState),
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
        ) {
            item(key = "title") { Header(style?.name ?: stringResource(R.string.settings_accounts), spec) }
            if (style == null) return@TransformingLazyColumn
            val look = style.look()
            item(key = "preview") {
                val spoken = stringResource(R.string.account_preview) + ", " + providerLabel(style.provider) + " " + style.name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .edgeTransform(this, spec)
                        .padding(vertical = 8.dp)
                        .clearAndSetSemantics { contentDescription = spoken },
                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BadgeWithMark(style.provider, look, decorative = true)
                    BadgeWithMark(style.provider, look, scale = PREVIEW_SCALE, decorative = true)
                }
            }
            item(key = "markHeader") { SubHeader(stringResource(R.string.account_mark), spec) }
            item(key = "auto") {
                RadioButton(
                    selected = style.mark == null,
                    onSelect = { onMark(null) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    label = { Text(stringResource(R.string.account_mark_auto, style.auto)) },
                )
            }
            PICKER_MARKS.chunked(PICKER_COLUMNS).forEachIndexed { row, marks ->
                item(key = "marks/$row") {
                    PickRow(this, spec) {
                        for (mark in marks) {
                            val taken = mark.value in used
                            val description = if (taken) stringResource(R.string.account_mark_used, mark.value, others[mark.value].orEmpty()) else null
                            TextToggleButton(
                                checked = style.mark?.value == mark.value,
                                onCheckedChange = { on -> onMark(mark.takeIf { on }) },
                                enabled = !taken,
                                modifier = Modifier
                                    .touchTargetAwareSize(PICK_SIZE)
                                    .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
                            ) { Text(mark.value, style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                }
            }
            item(key = "colorHeader") { SubHeader(stringResource(R.string.account_color), spec) }
            MarkColor.entries.chunked(PICKER_COLUMNS).forEachIndexed { row, colors ->
                item(key = "colors/$row") {
                    PickRow(this, spec) {
                        for (color in colors) {
                            val checked = markColor(style.color) == color
                            val name = colorName(color)
                            TextToggleButton(
                                checked = checked,
                                onCheckedChange = { onColor(color.id) },
                                modifier = Modifier.touchTargetAwareSize(PICK_SIZE).semantics { contentDescription = name },
                                colors = TextToggleButtonDefaults.colors(
                                    checkedContainerColor = color.disc,
                                    checkedContentColor = color.glyph,
                                    uncheckedContainerColor = color.disc,
                                    uncheckedContentColor = color.glyph,
                                ),
                                border = if (checked) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                            ) { Text(style.glyph, style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                }
            }
            item(key = "nickname") {
                FilledTonalButton(
                    onClick = onNickname,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    label = { Text(stringResource(R.string.account_nickname)) },
                    secondaryLabel = {
                        Text(style.nickname ?: stringResource(R.string.settings_not_set), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
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

@Composable
private fun TransformingLazyColumnItemScope.SubHeader(text: String, spec: TransformationSpec) {
    ListSubHeader(
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
    ) { Text(text) }
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
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().edgeTransform(scope, spec),
        horizontalArrangement = Arrangement.spacedBy(PICK_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
