package dev.wristline.watch.data

import org.junit.Assert.assertEquals
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
}
