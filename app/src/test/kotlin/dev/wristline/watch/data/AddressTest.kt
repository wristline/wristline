package dev.wristline.watch.data

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AddressTest {
    private fun ok(input: String) = (normalizeAddress(input) as AddressResult.Ok).url

    private fun error(input: String) = (normalizeAddress(input) as AddressResult.Error).reason

    @Test
    fun bareHostGetsHttps() {
        assertEquals("https://siso-work.tail43ebc0.ts.net", ok("siso-work.tail43ebc0.ts.net"))
    }

    @Test
    fun httpsIsKeptAndTrailingSlashesStripped() {
        assertEquals("https://siso-work.tail43ebc0.ts.net", ok("https://siso-work.tail43ebc0.ts.net/"))
        assertEquals("https://siso-work.tail43ebc0.ts.net", ok("https://siso-work.tail43ebc0.ts.net//"))
    }

    @Test
    fun caseAndWhitespaceAreNormalized() {
        assertEquals("https://siso-work.tail43ebc0.ts.net", ok("  HTTPS://Siso-Work.Tail43ebc0.TS.net "))
        assertEquals("https://siso-work.tail43ebc0.ts.net", ok("siso-work. tail43ebc0 .ts.net"))
    }

    @Test
    fun portIsKeptUnlessDefault() {
        assertEquals("https://host.ts.net:8443", ok("host.ts.net:8443"))
        assertEquals("https://host.ts.net", ok("https://host.ts.net:443"))
    }

    @Test
    fun ipAddressesAreAccepted() {
        assertEquals("https://192.168.0.10:47770", ok("192.168.0.10:47770"))
        assertEquals("https://[::1]:8443", ok("[::1]:8443"))
    }

    @Test
    fun emptyInputIsRejected() {
        assertEquals(AddressError.EMPTY, error(""))
        assertEquals(AddressError.EMPTY, error("   "))
    }

    @Test
    fun plainHttpIsRejected() {
        assertEquals(AddressError.INSECURE, error("http://host.ts.net"))
        assertEquals(AddressError.INSECURE, error("HTTP://host.ts.net"))
    }

    @Test
    fun otherSchemesPathsAndJunkAreRejected() {
        assertEquals(AddressError.INVALID, error("ftp://host.ts.net"))
        assertEquals(AddressError.INVALID, error("wss://host.ts.net"))
        assertEquals(AddressError.INVALID, error("host.ts.net/api"))
        assertEquals(AddressError.INVALID, error("host.ts.net?x=1"))
        assertEquals(AddressError.INVALID, error("host.ts.net#top"))
        assertEquals(AddressError.INVALID, error("user@host.ts.net"))
        assertEquals(AddressError.INVALID, error("https://"))
        assertEquals(AddressError.INVALID, error("/"))
        assertEquals(AddressError.INVALID, error("host.ts.net:notaport"))
    }

    private fun authorize(token: String) =
        Request.Builder().url("https://host.ts.net/api/ws").header("Authorization", "Bearer $token").build()

    @Test
    fun tokensKeepEveryBase64UrlCharacter() {
        val token = "dG9rZW4tZm9yLWRvY3VtZW50YXRpb24tb25seS0xMjM0NTY_-"
        assertEquals(token, normalizeToken(token))
    }

    @Test
    fun typedTokensLoseWhitespaceAndCharactersAHeaderCannotCarry() {
        assertEquals("abcDEF123", normalizeToken(" abc DEF\n123 \t"))
        assertEquals("abc", normalizeToken("a한b“c”"))
        assertEquals("", normalizeToken("한글"))
    }

    // Regression: a typed token with such characters was saved as is, and OkHttp threw on the
    // Authorization header at every connection attempt, crashing the app on each start.
    @Test
    fun normalizedTokensAlwaysFitTheAuthorizationHeader() {
        val typed = "abc 한\ndef"
        assertThrows(IllegalArgumentException::class.java) { authorize(typed) }
        assertEquals("Bearer abcdef", authorize(normalizeToken(typed)).header("Authorization"))
    }
}
