package io.github.techpool.textkeep

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

/** Immutable destination of the selected queue, retained through token refresh. */
internal data class UploadDestination(val server: String, val account: String, val device: String) {
    val binding get() = "$server|$account|$device"

    fun requireCurrent(current: UploadDestination?) {
        check(current == this) { "Upload paused: account, server or device changed" }
    }

    suspend fun request(
        client: OkHttpClient,
        current: suspend () -> UploadDestination?,
        token: suspend () -> String,
        deviceToken: String,
        body: RequestBody,
    ): Response {
        requireCurrent(current())
        val idToken = token()
        // Authentication may suspend while the user changes configuration.
        requireCurrent(current())
        return client.newCall(Request.Builder().url(server.trimEnd('/') + "/api/v1/sync/batches")
            .header("Authorization", "Bearer $idToken").header("X-Device-Token", deviceToken)
            .post(body).build()).execute()
    }

    companion object {
        fun capture(expectedBinding: String, server: String, account: String, device: String) =
            UploadDestination(server, account, device).also {
                check(it.binding == expectedBinding) { "Upload paused: selected queue belongs to another destination" }
            }
    }
}
