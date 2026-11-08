package dev.syncr.prototype.companion

import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class SubscriptionTransportTest {
    @Test fun handlesChunkedJsonAndOnlyCompleteSse() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val transport = SubscriptionTransport()
            server.enqueue(MockResponse().setChunkedBody("{\"models\":[]}", 3))
            assertEquals("{\"models\":[]}", transport.exchange(Request.Builder().url(server.url("/models")).build(), SubscriptionTransport::bounded))
            val delta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"An explanation\"}\n\n"
            val completed = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
            server.enqueue(MockResponse().setChunkedBody(delta + completed, 9))
            assertEquals("An explanation", transport.exchange(Request.Builder().url(server.url("/responses")).build(), SubscriptionTransport::stream))
            server.enqueue(MockResponse().setBody(delta))
            try { transport.exchange(Request.Builder().url(server.url("/responses")).build(), SubscriptionTransport::stream); fail("Partial stream accepted") }
            catch (_: IllegalStateException) { }
        }
    }
    @Test fun errorsAndRedirectsAreNotRetriedAndCancellationStopsRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val transport = SubscriptionTransport()
            for (code in listOf(401, 429, 500, 302)) {
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", server.url("/wrong")))
                try { transport.exchange(Request.Builder().url(server.url("/responses")).build(), SubscriptionTransport::bounded); fail("HTTP $code accepted") }
                catch (_: IllegalStateException) { }
            }
            assertEquals(4, server.requestCount)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = launch { transport.exchange(Request.Builder().url(server.url("/waiting")).build(), SubscriptionTransport::bounded) }
            delay(200); pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(5, server.requestCount)
        }
    }
}
