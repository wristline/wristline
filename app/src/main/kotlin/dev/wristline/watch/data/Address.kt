package dev.wristline.watch.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class AddressError { EMPTY, INSECURE, INVALID }

sealed interface AddressResult {
    /** [url] is `https://host[:port]` without a trailing slash. */
    data class Ok(val url: String) : AddressResult

    data class Error(val reason: AddressError) : AddressResult
}

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
