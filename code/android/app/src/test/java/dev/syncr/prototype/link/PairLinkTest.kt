package dev.syncr.prototype.link

import org.junit.Assert.*
import org.junit.Test

class PairLinkTest {
    private val mac = "0123456789abcdef0123456789abcdef"

    @Test fun readsWhatTheMacWrites() {
        val link = PairLink.parse("syncr://pair?h=192.168.1.20&p=51234&m=$mac&n=MacBook%20Air&e=1790000000")!!
        assertEquals(PairLink("192.168.1.20", 51234, mac, "MacBook Air", 1_790_000_000L), link)
        assertEquals(Endpoint("192.168.1.20", 51234, "MacBook Air", mac), link.endpoint)
        // Without an expiry (an older Mac's code) and with an awkward name.
        assertNull(PairLink.parse("syncr://pair?h=10.0.2.2&p=5050&m=$mac&n=Sid%27s%20Mac%20%E2%80%94%20pro")!!.expiresAt)
        assertEquals("Sid's Mac — pro", PairLink.parse("syncr://pair?h=10.0.2.2&p=5050&m=$mac&n=Sid%27s%20Mac%20%E2%80%94%20pro")!!.name)
        assertEquals("your Mac", PairLink.parse("syncr://pair?h=10.0.2.2&p=5050&m=$mac")!!.name)
        assertEquals(mac, PairLink.parse("SYNCR://pair?h=10.0.2.2&p=5050&m=${mac.uppercase()}")!!.macId)
    }

    @Test fun refusesAnythingElse() {
        val ok = "syncr://pair?h=192.168.1.20&p=5050&m=$mac&n=Mac"
        assertNotNull(PairLink.parse(ok))
        for (bad in listOf(
            null, "", "https://pair?h=192.168.1.20&p=5050&m=$mac", "syncr://other?h=192.168.1.20&p=5050&m=$mac", "syncr://pair",
            ok.replace("&p=5050", ""), ok.replace("p=5050", "p=0"), ok.replace("p=5050", "p=65536"), ok.replace("p=5050", "p=-1"), ok.replace("p=5050", "p=50x"),
            ok.replace("&m=$mac", ""), ok.replace(mac, "nothex"), ok.replace(mac, mac.take(31)), ok.replace(mac, mac + "0"),
            ok.replace("h=192.168.1.20", "h="), ok.replace("&n=Mac", "&n=Mac&n=Other"), ok + "&e=soon", ok + "&e=-5", ok + "&x",
            ok.replace("192.168.1.20", "8.8.8.8"), ok.replace("192.168.1.20", "evil.example.com"), ok.replace("192.168.1.20", "192.168.1.20.5"),
            ok.replace("192.168.1.20", "192.168.1.300"), ok.replace("192.168.1.20", "172.32.0.1"), "syncr://pair?" + "a=b&".repeat(200) + ok.substringAfter('?'),
        )) assertNull("should be refused: $bad", PairLink.parse(bad))
    }

    @Test fun onlyAddressesOnThisNetwork() {
        for (good in listOf("10.1.2.3", "10.0.2.2", "172.16.0.9", "172.31.255.1", "192.168.0.1", "169.254.3.4", "macbook-air.local", "Sids-Mac.LOCAL"))
            assertTrue(good, PairLink.local(good))
        for (bad in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "1.2.3", "localhost", "example.com", "a b.local", ".local", "-x.local", "127.0.0.1"))
            assertFalse(bad, PairLink.local(bad))
    }

    @Test fun theNameIsPrintableAndShort() {
        assertEquals("Mac", PairLink.cleanName("\u0007Mac\u001b"))
        assertEquals(64, PairLink.cleanName("x".repeat(200))!!.length)
        assertNull(PairLink.cleanName("   "))
        assertNull(PairLink.cleanName(null))
    }

    @Test fun anExpiredCodeIsKnownByItsTime() {
        val link = PairLink("10.0.0.2", 5050, mac, "Mac", expiresAt = 1_000)
        assertFalse(link.expired(999)); assertFalse(link.expired(1_000)); assertTrue(link.expired(1_001))
        assertFalse(PairLink("10.0.0.2", 5050, mac, "Mac").expired(Long.MAX_VALUE))
    }
}
