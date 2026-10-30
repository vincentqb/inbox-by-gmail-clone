package com.baestheorem.inbox.gmail

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class SendTransportTest {
    @Test fun doesNotReplayAPostWhoseResponseWasLost() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("A duplicate must never reach this response"))
            val request = Request.Builder().url(server.url("/send")).post("mail".toRequestBody()).build()
            assertThrows(IOException::class.java) { Net.singleSendClient.newCall(request).execute().close() }
            assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
            assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun doesNotFollowRedirectsThatCouldRepostAMessage() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/again")))
            server.enqueue(MockResponse().setBody("Must not receive another POST"))
            val request = Request.Builder().url(server.url("/send")).post("mail".toRequestBody()).build()
            Net.singleSendClient.newCall(request).execute().use { assertEquals(307, it.code) }
            assertEquals(1, server.requestCount)
            assertFalse(Net.singleSendClient.retryOnConnectionFailure)
            assertFalse(Net.singleSendClient.followRedirects)
            assertFalse(Net.singleSendClient.followSslRedirects)
        } finally { server.shutdown() }
    }
}
