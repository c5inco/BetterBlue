package com.betterblue.kit.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The result of one HTTP exchange: the fully-read body, the status code, the
 * (case-insensitive) response headers, and the URL of the FINAL request in the
 * redirect chain — the EU login flows read their OAuth `code` out of it.
 */
class HttpResult(
    val body: ByteArray,
    val code: Int,
    val headers: Headers,
    val finalUrl: String,
) {
    fun bodyString(): String = body.toString(Charsets.UTF_8)

    /** Case-insensitive header lookup (OkHttp's Headers already are). */
    fun header(name: String): String? = headers[name]
}

/** Suspends until the call completes; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { cancel() }
}

/**
 * A minimal in-memory cookie jar, private to one API client instance. The EU
 * login choreography (Kia EU especially) depends on cookies persisting across
 * the authorize → certs → signin sequence, and those cookies must never leak
 * between accounts — so each client gets its own jar, mirroring URLSession's
 * per-session implicit cookie storage on iOS.
 */
class InMemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()
    private val lock = Any()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            for (cookie in cookies) {
                val list = store.getOrPut(cookie.domain) { mutableListOf() }
                list.removeAll { it.name == cookie.name && it.path == cookie.path }
                list.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val nowMillis = Instant.now().toEpochMilli()
        synchronized(lock) {
            return store.values.flatten().filter { cookie ->
                cookie.expiresAt > nowMillis && cookie.matches(url)
            }
        }
    }

    /** All stored cookies for a domain, regardless of path — used by the Cloudflare handshake. */
    fun cookiesForDomain(domain: String): List<Cookie> = synchronized(lock) {
        store.entries.filter { domain.endsWith(it.key) }.flatMap { it.value }.toList()
    }

    fun clear() = synchronized(lock) { store.clear() }
}
