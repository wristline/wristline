package dev.wristline.watch.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class AddressError { EMPTY, INSECURE, INVALID }

sealed interface AddressResult {
    /** [url] is `https://host[:port]` without a trailing slash. */
    data class Ok(val url: String) : AddressResult

    data class Error(val reason: AddressError) : AddressResult
}

/*
 * targetSdk 37 plan, local network permission (not done while targetSdk is 36; the manifest
 * already declares it). https://developer.android.com/privacy-and-security/local-network-permission
 * - Apps targeting Android 17 need the runtime permission ACCESS_LOCAL_NETWORK for any TCP
 *   connection to a local network address, HTTPS included. Denied, a connection typically times
 *   out: to the user it reads as Sent.Unreachable / Conn.Offline, with no hint why.
 * - Request it only for a bridge that resolves to a local address: 10/8, 172.16/12, 192.168/16,
 *   169.254/16, fc00::/7, fe80::/10, or a `.local` name. A public name (Tailscale Funnel) needs nothing.
 * - Unknown: whether a tailnet address (100.64.0.0/10, the CGNAT range; fd7a:115c:a1e0::/48) counts
 *   as local; the page lists no ranges. Test on an Android 17 watch with the permission denied
 *   (docs/dev-setup.md, "targetSdk 37 체크리스트") and treat it as local until then.
 * - Ask in the address step, before pairing, so the code screen never times out for it; on denial
 *   show the reason with a link to the app's permission settings, as the notifications-off row does.
 */

/**
 * Turns what the user typed or dictated into the bridge base URL. A bare host gets `https://`;
 * `http://` is refused (the bridge is reached through HTTPS only); paths, queries and user info are
 * refused because the bridge serves its API from the root of its host.
 */
fun normalizeAddress(input: String): AddressResult {
    // Hosts cannot contain whitespace; keyboards and dictation sometimes insert it.
    var rest = input.filterNot { it.isWhitespace() }
    if (rest.isEmpty()) return AddressResult.Error(AddressError.EMPTY)
    when {
        rest.startsWith("http://", ignoreCase = true) -> return AddressResult.Error(AddressError.INSECURE)
        rest.startsWith("https://", ignoreCase = true) -> rest = rest.substring("https://".length)
        "://" in rest -> return AddressResult.Error(AddressError.INVALID)
    }
    rest = rest.trimEnd('/')
    if (rest.isEmpty()) return AddressResult.Error(AddressError.INVALID)
    val url = "https://$rest".toHttpUrlOrNull() ?: return AddressResult.Error(AddressError.INVALID)
    if (url.encodedPath != "/" || url.query != null || url.fragment != null ||
        url.username.isNotEmpty() || url.password.isNotEmpty()
    ) {
        return AddressResult.Error(AddressError.INVALID)
    }
    // HttpUrl lowercases the host and drops the default port 443.
    return AddressResult.Ok(url.toString().removeSuffix("/"))
}

/**
 * The device token as it is stored and sent. Bridge tokens are base64url, so everything outside
 * printable ASCII is dropped: spaces and line breaks that keyboards and dictation insert, and any
 * other stray character. OkHttp refuses such characters in the Authorization header by throwing,
 * which would crash every connection attempt for as long as the token is saved.
 */
fun normalizeToken(input: String): String = input.filter { it in '!'..'~' }
