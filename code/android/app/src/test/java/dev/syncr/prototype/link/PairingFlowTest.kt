package dev.syncr.prototype.link

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remaining-v1 P.1 on the host: what a scanned `syncr://pair` link leads to. The link names the Mac; connecting to it asks for the four digits
 * the Mac shows; typing them pairs. The Mac's messages are the ones the phone has always had. (Test sockets are on 127.0.0.1, which the
 * parser rightly refuses in a scanned link, so the address is swapped for the loopback one after the link is read.)
 */
class PairingFlowTest {
    private val mac = FakeMac(macName = "Studio Mac", macId = "0123456789abcdef0123456789abcdef")
    private val store = MemoryStore()
    private val notes = NotesFixture()
    private val temp: File = Files.createTempDirectory("syncr-pairflow-").toFile()
    private val statuses = java.util.concurrent.CopyOnWriteArrayList<SessionStatus>()
    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
    private lateinit var client: LinkClient

    private fun start() {
        val config = ClientConfig("Test phone", store, notes, Clock.SYSTEM, temp, FakeOut(), PenFeed(), { ByteArray(4) }, { logs += it },
            ids = IdPool({ "s" }))
        client = LinkClient(config, { p -> Endpoint("127.0.0.1", mac.port, p.macName, p.macId) }, object : ClientListener {
            override fun onStatus(status: SessionStatus) { statuses += status }
            override fun onTarget(target: StreamTarget) {}
        })
        client.notesChanged(notes.notes, null)
        client.start()
    }

    @After fun tearDown() {
        if (this::client.isInitialized) client.stop(wait = true)
        mac.close()
        temp.deleteRecursively()
    }

    private fun scanned(expires: Long? = null): PairLink {
        val e = expires?.let { "&e=$it" } ?: ""
        val parsed = PairLink.parse("syncr://pair?h=192.168.1.20&p=${mac.port}&m=0123456789abcdef0123456789abcdef&n=Studio%20Mac$e")!!
        assertEquals(mac.port, parsed.port)
        return parsed.copy(host = "127.0.0.1")
    }

    @Test fun scanningTheCodeAsksForTheDigitsAndPairs() {
        start()
        val link = scanned()
        client.connect(link.endpoint)
        assertTrue("the Mac asks for the four digits", mac.await { statuses.lastOrNull()?.phase == Phase.NEEDS_CODE })
        assertEquals("Studio Mac", statuses.last().macName)
        assertNull("a phone that was never paired sends no token", mac.hellos.first().token)
        client.submitCode("0000")
        assertTrue(mac.await { statuses.lastOrNull()?.triesLeft == 4 })
        client.submitCode("4242")
        assertTrue("connected", mac.await { statuses.lastOrNull()?.phase == Phase.CONNECTED })
        assertEquals("Studio Mac", statuses.last().macName) // the name the pairing screen waits for
        assertEquals(link.macId, store.current?.macId)
        assertEquals("Studio Mac", store.current?.macName)
    }

    @Test fun fiveWrongDigitsEndThePairing() {
        start()
        client.connect(scanned().endpoint)
        assertTrue(mac.await { statuses.lastOrNull()?.phase == Phase.NEEDS_CODE })
        for (i in 1..5) { client.submitCode("000$i"); Thread.sleep(50) }
        assertTrue("the phone says why", mac.await { statuses.any { it.message?.contains("Too many wrong codes") == true } })
        assertNull(store.current)
    }

    @Test fun aCodeForAnotherMacDoesNotSendTheStoredToken() {
        start()
        // Paired with another Mac before the code is scanned (set after start, so the client has not tried that Mac itself).
        store.current = Pairing("ffffffffffffffffffffffffffffffff", "Old Mac", "ab".repeat(32))
        client.connect(scanned().endpoint)
        assertTrue(mac.await { statuses.lastOrNull()?.phase == Phase.NEEDS_CODE })
        assertNull("the old Mac's token goes only to the old Mac", mac.hellos.single().token)
        client.submitCode("4242")
        assertTrue(mac.await { statuses.lastOrNull()?.phase == Phase.CONNECTED })
        assertEquals("Studio Mac", store.current?.macName)
    }

    @Test fun anExpiredLinkIsKnownBeforeAnythingConnects() {
        assertTrue(scanned(expires = 1).expired(System.currentTimeMillis() / 1000))
        assertTrue(!scanned(expires = System.currentTimeMillis() / 1000 + 100).expired(System.currentTimeMillis() / 1000))
        assertNotNull(PairLink.parse("syncr://pair?h=192.168.1.20&p=5050&m=0123456789abcdef0123456789abcdef&e=1"))
    }
}
