package dev.syncr.prototype.companion

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class SubscriptionTransport {
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    suspend fun <T> exchange(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Connection failed or timed out. No request was retried automatically."))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        check(it.isSuccessful) { "ChatGPT request failed (HTTP ${it.code}). Check sign-in, model access and plan usage limits." }
                        read(it)
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    companion object {
        fun bounded(response: Response): String {
            val source = requireNotNull(response.body).source()
            require(!source.request(2_000_001L)) { "Server response exceeded its limit." }
            return source.readUtf8()
        }
        fun stream(response: Response): String = stream(response, null)

        fun stream(response: Response, onText: ((String) -> Unit)?): String {
            val parser = ResponseStream(onText); val input = requireNotNull(response.body).source()
            var bytes = 0L; val data = StringBuilder()
            while (!input.exhausted()) {
                val line = input.readUtf8LineStrict(256_000)
                bytes += line.length
                require(bytes <= 2_000_000) { "Response stream exceeded its limit." }
                if (line.isEmpty()) {
                    if (data.isNotEmpty() && data.toString() != "[DONE]") parser.event(data.toString())
                    data.setLength(0)
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
            }
            if (data.isNotEmpty() && data.toString() != "[DONE]") parser.event(data.toString())
            return parser.result()
        }
    }
}
