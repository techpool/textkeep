package io.github.techpool.textkeep

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class UploadDestinationTest {
    private val original = UploadDestination("https://original.example", "original-owner", "original-device")
    private val changed = listOf(original.copy(server="https://other.example"), original.copy(account="other-owner"), original.copy(device="other-device"), null)
    private val body = "{\"records\":[{\"body\":\"synthetic message\"}]}".toRequestBody()
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("A changed destination must reject the upload") } catch (_: IllegalStateException) { }
    }
    private fun client(requests: MutableList<Request>, intercept: () -> Int = {200}) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(intercept()).message("Synthetic response").body("{}".toResponseBody()).build()
        }.build()

    @Test fun changedDestinationIsRejectedBeforeCredentialsOrNetwork() = runBlocking {
        for (current in changed) {
            val requests = mutableListOf<Request>()
            var tokens = 0
            rejected { original.request(client(requests), {current}, {tokens++; "synthetic-id-token"}, "synthetic-device-token", body).close() }
            assertEquals(0, tokens)
            assertTrue(requests.isEmpty())
        }
        rejected { UploadDestination.capture(original.binding, "https://other.example", original.account, original.device) }
    }

    @Test fun configurationChangedWhileTokenRefreshSuspendsCannotReceiveOldBody() = runBlocking {
        for (replacement in changed) {
            val requests = mutableListOf<Request>()
            var current: UploadDestination? = original
            rejected { original.request(client(requests), {current}, {current=replacement; "synthetic-id-token"}, "synthetic-device-token", body).close() }
            assertTrue("No batch may be sent after the identity boundary changes", requests.isEmpty())
        }
    }

    @Test fun responseAndRetryRetainTheCapturedDestination() = runBlocking {
        val requests = mutableListOf<Request>()
        var current: UploadDestination? = original
        val http = client(requests) { current=original.copy(server="https://other.example"); 401 }
        original.request(http, {current}, {"synthetic-id-token"}, "synthetic-device-token", body).use { assertEquals(401, it.code) }
        rejected { original.request(http, {current}, {"synthetic-retry-token"}, "synthetic-device-token", body).close() }
        assertEquals(1, requests.size)
        assertEquals("https://original.example/api/v1/sync/batches", requests.single().url.toString())
        assertEquals("Bearer synthetic-id-token", requests.single().header("Authorization"))
        assertEquals("synthetic-device-token", requests.single().header("X-Device-Token"))
    }
}
