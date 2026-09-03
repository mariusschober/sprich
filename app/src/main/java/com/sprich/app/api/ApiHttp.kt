package com.sprich.app.api

import com.sprich.app.speech.remote.ApiFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Optional provider-authored explanation is transient UI data, never the exception/log message. */
class ApiException(val failure: ApiFailure, val publicDetail: String? = null) : IOException(failure.toString())

/** One connection pool. Authentication is always attached to the individual request. */
object ApiHttp {
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<WebSocket>()
    private val connectionPool = lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
    val client: OkHttpClient get() = connectionPool.value

    private val epoch = MutableStateFlow(0L)
    val permissionEpoch = epoch.asStateFlow()
    val currentEpoch: Long get() = epoch.value

    @Synchronized fun track(socket: WebSocket, expectedEpoch: Long) {
        sockets.add(socket)
        if (expectedEpoch != currentEpoch) { sockets.remove(socket); socket.cancel() }
    }
    @Synchronized fun untrack(socket: WebSocket) { sockets.remove(socket) }

    /** Revocation is synchronous, so even a result already queued on Main loses insertion authority. */
    @Synchronized fun revoke() {
        epoch.value += 1
        if (connectionPool.isInitialized()) client.dispatcher.cancelAll()
        sockets.forEach { it.cancel() }
        sockets.clear()
    }
}

/** Bounded SSE lines/events. Work and body reads stay on OkHttp's worker and are cancelled with the caller. */
suspend fun Call.readApiEvents(
    onHttpFailure: (Response) -> ApiException = { ApiException(ApiFailure.fromHttpCode(it.code)) },
    onEvent: (String) -> Unit,
): Unit = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(ApiException(ApiFailure.fromException(e)))
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (!it.isSuccessful) throw onHttpFailure(it)
                    if (it.body?.contentType()?.let { type -> type.type == "text" && type.subtype == "event-stream" } != true) throw ApiException(ApiFailure.InvalidResponse)
                    val source = it.body!!.source()
                    val data = StringBuilder()
                    var bytes = 0L
                    var events = 0
                    while (!source.exhausted()) {
                        val line = source.readUtf8LineStrict(65_536)
                        bytes += line.length
                        if (bytes > 8_388_608) throw ApiException(ApiFailure.InvalidResponse)
                        when {
                            line.isEmpty() && data.isNotEmpty() -> {
                                if (++events > 10_000) throw ApiException(ApiFailure.InvalidResponse)
                                onEvent(data.toString()); data.setLength(0)
                            }
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substring(5).removePrefix(" "))
                                if (data.length > 65_536) throw ApiException(ApiFailure.InvalidResponse)
                            }
                        }
                    }
                    // A missing blank delimiter is a truncated event, not a successful final response.
                    if (data.isNotEmpty()) throw ApiException(ApiFailure.InvalidResponse)
                }
                if (continuation.isActive) continuation.resume(Unit)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(if (e is ApiException) e else ApiException(ApiFailure.fromException(e)))
            }
        }
    })
}

/** Reads and closes the entire bounded body on OkHttp's worker. Cancellation also interrupts body reads. */
suspend fun Call.readApiBody(maxBytes: Long = 65_536,
    onHttpFailure: (Response) -> ApiException = { ApiException(ApiFailure.fromHttpCode(it.code)) },
): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(ApiException(ApiFailure.fromException(e)))
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val text = response.use {
                    if (!it.isSuccessful) throw onHttpFailure(it)
                    val source = it.body?.source() ?: throw ApiException(ApiFailure.InvalidResponse)
                    val buffer = Buffer()
                    while (buffer.size <= maxBytes) {
                        if (source.read(buffer, maxBytes + 1 - buffer.size) == -1L) break
                    }
                    if (buffer.size > maxBytes) throw ApiException(ApiFailure.InvalidResponse)
                    buffer.readUtf8()
                }
                if (continuation.isActive) continuation.resume(text)
            } catch (e: Exception) {
                // JSON and provider bodies never become exception messages or logs.
                if (continuation.isActive) continuation.resumeWithException(
                    if (e is ApiException) e else ApiException(ApiFailure.fromException(e)))
            }
        }
    })
}
