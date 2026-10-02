package dev.wristline.watch.data

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable

/** Remembered accounts not seen for this long are forgotten (see [AccountBook.seen]). */
const val ACCOUNT_KEEP_MS = 90L * 24 * 60 * 60 * 1000

/** How stale an account's [AccountStyle.seenAt] may get before [AccountBook.seen] refreshes it: few writes. */
private const val SEEN_REFRESH_MS = 24L * 60 * 60 * 1000

/** Values of [Mark.type]. */
object MarkType {
    const val LETTER = "letter"
    const val DIGIT = "digit"
    const val EMOJI = "emoji"
}

/** The marks offered besides letters and digits: one glyph each, told apart by their colors at the badge's size. */
val MARK_EMOJI = listOf("🏠", "💼", "🎓", "🧪", "⭐", "🌙")

/** A mark the user picked for an account: [type] one of [MarkType], [value] the glyph. */
@Serializable
data class Mark(val type: String, val value: String)

/**
 * What the watch remembers of an account, keyed by [accountKey]. [auto] is the mark the watch
 * picked for it ([autoCandidates]), kept while the name it came from ([autoFrom]) stays, so other
 * accounts coming and going never change it. [mark], [color] (a palette id, null for white) and
 * [nickname] are the user's; null means the default.
 */
@Serializable
data class AccountStyle(
    val provider: String,
    val id: String,
    val label: String,
    val primary: Boolean = false,
    val auto: String,
    val autoFrom: String,
    val mark: Mark? = null,
    val color: String? = null,
    val nickname: String? = null,
    val firstSeen: Long,
    val seenAt: Long,
) {
    /** The name the watch shows: the nickname, else the bridge's label. */
    val name: String get() = nickname?.takeIf { it.isNotBlank() } ?: label

    /** The glyph on the badge: the user's mark, else the automatic one. */
    val glyph: String get() = mark?.value ?: auto
}

/**
 * Every account the watch has seen, and per provider the order the user put them in (keys, see
 * [accountKey]); stored as one JSON value in Prefs.
 */
@Serializable
data class AccountBook(
    val accounts: Map<String, AccountStyle> = emptyMap(),
    val order: Map<String, List<String>> = emptyMap(),
)

/** How an account looks wherever it is shown: the badge's [mark] and [color] (null: white), its [name]. */
data class AccountLook(val mark: String, val color: String?, val name: String)

/** The key of [account] of [provider] in an [AccountBook]: ids are the bridge's, unique per provider only. */
fun accountKey(provider: String, account: Account): String = provider + ":" + account.id

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+$")
private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")

/** The Latin letters of [text], accents dropped (`é` is `E`), upper case. */
private fun latinLetters(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).filter { it in 'a'..'z' || it in 'A'..'Z' }.uppercase(Locale.ROOT)

/**
 * The automatic marks for an account named [name], the best first: the first letter of each word
 * (of an email, the local part's and then the domain's), then the words' later letters in order,
 * all A–Z upper case; then the digits 1–9. A name without Latin letters gets digits only.
 */
fun autoCandidates(name: String): List<String> {
    val text = name.trim()
    val words = if (EMAIL.matches(text)) {
        listOf(text.substringBefore('@'), text.substringAfter('@').substringBefore('.'))
    } else {
        text.split(WORD_BREAK)
    }
    val letters = words.map(::latinLetters).filter { it.isNotEmpty() }
    val initials = letters.map { it.take(1) }
    val later = letters.flatMap { word -> word.drop(1).map(Char::toString) }
    return (initials + later + (1..9).map(Int::toString)).distinct()
}

/** The first of [name]'s [autoCandidates] not in [taken]; the last digit if all are. */
fun autoMark(name: String, taken: Set<String>): String {
    val candidates = autoCandidates(name)
    return candidates.firstOrNull { it !in taken } ?: candidates.last()
}

/** The glyphs of [provider]'s accounts other than [key], both automatic and picked: what [key] must not take. */
fun AccountBook.taken(provider: String, key: String): Set<String> =
    accounts.filter { (k, style) -> k != key && style.provider == provider }.values.flatMapTo(HashSet()) { listOfNotNull(it.auto, it.mark?.value) }

/** The glyphs [provider]'s accounts other than [key] show now: the ones the mark picker dims. */
fun AccountBook.shownByOthers(provider: String, key: String): Set<String> =
    accounts.filter { (k, style) -> k != key && style.provider == provider }.values.mapTo(HashSet()) { it.glyph }

/**
 * [provider]'s remembered accounts in the order the app shows them: as the user ordered them, then
 * the primary home's account, then the earlier seen, then by label in any case and by id.
 */
fun AccountBook.ordered(provider: String): List<String> {
    val user = order[provider].orEmpty()
    return accounts.filterValues { it.provider == provider }.entries.sortedWith(
        compareBy(
            { (key, _) -> user.indexOf(key).let { if (it < 0) Int.MAX_VALUE else it } },
            { (_, style) -> !style.primary },
            { (_, style) -> style.firstSeen },
            { (_, style) -> style.label.lowercase(Locale.ROOT) },
            { (_, style) -> style.id },
        ),
    ).map { it.key }
}

/**
 * The book after seeing [visible] (provider and account) at [now]: a new account gets the first
 * [autoMark] its siblings, shown or remembered, have not taken, the new ones in the order
 * [ordered] gives them (primary first); a known one keeps its mark unless its name changed, and
 * its label and primary flag follow the bridge. Accounts not seen for [ACCOUNT_KEEP_MS] are
 * forgotten. The same book (equal) when nothing changed, so a caller can skip the write.
 */
fun AccountBook.seen(visible: List<Pair<String, Account>>, now: Long): AccountBook {
    val shown = visible.associateBy { (provider, account) -> accountKey(provider, account) }
    var accounts = accounts.filter { (key, style) -> key in shown || now - style.seenAt <= ACCOUNT_KEEP_MS }
    // Known ones first, so a new account's mark avoids theirs.
    for ((key, pair) in shown) {
        val (_, account) = pair
        val style = accounts[key] ?: continue
        val seenAt = if (now - style.seenAt >= SEEN_REFRESH_MS) now else style.seenAt
        accounts = accounts + (key to style.copy(label = account.label, primary = account.primary, seenAt = seenAt))
    }
    val new = shown.filterKeys { it !in accounts }.map { (key, pair) ->
        val (provider, account) = pair
        key to AccountStyle(provider, account.id, account.label, account.primary, auto = "", autoFrom = "", firstSeen = now, seenAt = now)
    }
    var book = copy(accounts = accounts + new)
    // New ones in display order; a known one whose name changed is picked for again.
    val stale = book.accounts.keys.filter { key -> key in shown && book.accounts.getValue(key).let { it.auto.isEmpty() || it.autoFrom != it.name } }
    for (provider in stale.map { book.accounts.getValue(it).provider }.distinct()) {
        for (key in book.ordered(provider).filter { it in stale }) book = book.reassigned(key)
    }
    val order = book.order.mapValues { (_, keys) -> keys.filter { it in book.accounts } }.filterValues { it.isNotEmpty() }
    return book.copy(order = order)
}

/** [key]'s automatic mark picked again from its name ([AccountStyle.name]) if that changed or a sibling now has it. */
private fun AccountBook.reassigned(key: String): AccountBook {
    val style = accounts[key] ?: return this
    val taken = taken(style.provider, key)
    if (style.auto.isNotEmpty() && style.autoFrom == style.name && style.auto !in taken) return this
    val updated = style.copy(auto = autoMark(style.name, taken), autoFrom = style.name)
    return copy(accounts = accounts + (key to updated))
}

/** [key] changed by [change], its automatic mark picked again when that changed its name. */
fun AccountBook.edit(key: String, change: (AccountStyle) -> AccountStyle): AccountBook {
    val style = accounts[key] ?: return this
    return copy(accounts = accounts + (key to change(style))).reassigned(key)
}

/** [key] back to the defaults: its automatic mark, white, the bridge's label. */
fun AccountBook.reset(key: String): AccountBook = edit(key) { it.copy(mark = null, color = null, nickname = null) }

/**
 * [key] moved one place up ([by] -1) or down (+1) among [shown], [its provider][AccountStyle.provider]'s
 * accounts in the order on screen; the user's order of that provider becomes the whole new order.
 */
fun AccountBook.move(key: String, by: Int, shown: List<String>): AccountBook {
    val provider = accounts[key]?.provider ?: return this
    val at = shown.indexOf(key)
    val other = shown.getOrNull(at + by) ?: return this
    if (at < 0) return this
    val full = ordered(provider).toMutableList()
    val i = full.indexOf(key)
    val j = full.indexOf(other)
    if (i < 0 || j < 0) return this
    full[i] = other
    full[j] = key
    return copy(order = order + (provider to full))
}

/** How [account] of [provider] looks; from its label alone when the book does not know it yet. */
fun AccountBook.look(provider: String, account: Account): AccountLook {
    val style = accounts[accountKey(provider, account)] ?: return AccountLook(autoMark(account.label, emptySet()), null, account.label)
    return AccountLook(style.glyph, style.color, style.name)
}

/** The accounts of [usage] and [sessions], each once, as [AccountBook.seen] takes them. */
fun accountsOf(usage: List<Usage>, sessions: List<Session>): List<Pair<String, Account>> =
    (usage.mapNotNull { u -> u.account?.let { u.provider to it } } + sessions.mapNotNull { s -> s.account?.let { s.provider to it } })
        .distinctBy { (provider, account) -> accountKey(provider, account) }
