package com.betterblue.kit

import com.betterblue.kit.http.HttpResult
import com.betterblue.kit.http.InMemoryCookieJar
import com.betterblue.kit.http.await
import com.betterblue.kit.json.asIntOrNull
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.log.HttpLog
import com.betterblue.kit.log.HttpRequestType
import com.betterblue.kit.log.SensitiveDataRedactor
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Region
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Base class for API clients providing shared HTTP request execution, logging,
 * and error handling. Subclasses implement [ApiClient] directly for their
 * region/brand.
 *
 * Thread-safety: per-client mutable state (config, memoized tokens, cookies)
 * is only touched from `suspend` methods; subclasses guard their own `var`s
 * with a `Mutex` where concurrent calls are possible.
 */
abstract class ApiClientBase(
    @Volatile var config: ApiClientConfig,
    baseHttpClient: OkHttpClient = defaultHttpClient,
) {
    /** Cookie storage private to this client instance (see [InMemoryCookieJar]). */
    val cookieJar: InMemoryCookieJar = InMemoryCookieJar()

    /**
     * Per-instance HTTP client: shares the base client's pools but has its own
     * cookie jar, so account sessions never bleed into each other.
     */
    val httpClient: OkHttpClient =
        baseHttpClient
            .newBuilder()
            .cookieJar(cookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    open val apiName: String get() = "ApiClient"

    // Convenience accessors
    val username: String get() = config.username
    val password: String get() = config.password
    val pin: String get() = config.pin
    val accountId: String get() = config.accountId
    val region: Region get() = config.region
    val brand: Brand get() = config.brand

    /**
     * Default device registration: generate a stable device id and remember it
     * in the configuration so remember-me tokens stay valid across client
     * re-initializations.
     */
    open suspend fun registerDevice(): String? {
        val deviceId = UUID.randomUUID().toString().uppercase()
        config = config.copy(deviceId = deviceId)
        return deviceId
    }

    // HTTP request execution

    /** Performs an HTTP request with logging and error handling. */
    suspend fun performRequest(
        url: String,
        method: HttpMethod = HttpMethod.GET,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        requestType: HttpRequestType,
        vin: String? = null,
        validateResponse: Boolean = true,
    ): HttpResult {
        val contentType =
            headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value
                ?: "application/json"

        val requestBody: RequestBody? =
            when {
                body != null -> {
                    body.toRequestBody(contentType.toMediaType())
                }

                // OkHttp requires a body for POST/PUT; the APIs' bodiless POSTs
                // (Kia stop commands are GETs, but e.g. Hyundai startCharge POSTs
                // an empty body) send zero bytes.
                method == HttpMethod.POST || method == HttpMethod.PUT -> {
                    ByteArray(0).toRequestBody(contentType.toMediaType())
                }

                else -> {
                    null
                }
            }

        val request =
            Request
                .Builder()
                .url(url)
                .method(method.name, requestBody)
                .apply {
                    for ((key, value) in headers) header(key, value)
                    if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) } && requestBody != null) {
                        header("Content-Type", "application/json")
                    }
                }.build()

        return performLoggedRequest(
            request = request,
            requestType = requestType,
            requestBodyText = body?.toString(Charsets.UTF_8),
            vin = vin,
            validateResponse = validateResponse,
        )
    }

    /** Performs an HTTP request and additionally parses the response as a JSON object. */
    suspend fun performJsonRequest(
        url: String,
        method: HttpMethod = HttpMethod.GET,
        headers: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
        requestType: HttpRequestType,
        vin: String? = null,
        validateResponse: Boolean = true,
    ): Pair<JsonObject, HttpResult> {
        val result =
            performRequest(
                url = url,
                method = method,
                headers = headers,
                body = body?.toString()?.toByteArray(Charsets.UTF_8),
                requestType = requestType,
                vin = vin,
                validateResponse = validateResponse,
            )
        return parseJsonObject(result.body) to result
    }

    protected suspend fun performLoggedRequest(
        request: Request,
        requestType: HttpRequestType,
        requestBodyText: String?,
        vin: String? = null,
        validateResponse: Boolean = true,
    ): HttpResult {
        val startTime = Instant.now()
        val requestHeaders = request.headers.toMap()

        BBLogger.debug(
            BBLogCategory.API,
            "[$apiName] Sending ${requestType.displayName} request | URL: ${request.url} | Method: ${request.method}",
        )

        val result =
            try {
                httpClient.newCall(request).await().use { response ->
                    HttpResult(
                        body = response.body.bytes(),
                        code = response.code,
                        headers = response.headers,
                        finalUrl = response.request.url.toString(),
                    )
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                logHttpRequest(
                    requestType = requestType,
                    method = request.method,
                    url = request.url.toString(),
                    requestHeaders = requestHeaders,
                    requestBody = requestBodyText,
                    responseStatus = null,
                    responseHeaders = emptyMap(),
                    responseBody = null,
                    error = e.message ?: e.toString(),
                    apiError = null,
                    startTime = startTime,
                    vin = vin,
                )
                throw ApiException("Network error: ${e.message ?: e}", apiName = apiName, cause = e)
            }

        val responseBody = result.bodyString()
        val apiError = extractApiError(result.body)

        BBLogger.debug(BBLogCategory.API, "[$apiName] Response ${result.code} for ${requestType.displayName}")

        logHttpRequest(
            requestType = requestType,
            method = request.method,
            url = request.url.toString(),
            requestHeaders = requestHeaders,
            requestBody = requestBodyText,
            responseStatus = result.code,
            responseHeaders = result.headers.toMap(),
            responseBody = responseBody,
            error = null,
            apiError = apiError,
            startTime = startTime,
            vin = vin,
        )

        if (validateResponse) {
            validateHttpResponse(result, responseBody)
        }

        return result
    }

    // Error handling

    /**
     * HTTP + CCSP response validation. CCSP (the EU/AU/IN "Connected Car
     * Service Platform") reports application-level failures inside the body —
     * `retCode: "F"` plus a numeric `resCode` — usually paired with an
     * unhelpful HTTP 400, so decode those first.
     */
    fun validateHttpResponse(result: HttpResult, responseBody: String?) {
        checkCcspResponseForErrors(result.body)

        if (result.code == 401) {
            throw ApiException.invalidCredentials(
                "Authentication expired: ${responseBody ?: "Unknown error"}",
                apiName = apiName,
            )
        }

        if (result.code == 502) {
            throw ApiException.serverError(
                "Server error (502): ${responseBody ?: "Unknown error"}",
                apiName = apiName,
            )
        }

        if (result.code >= 400) {
            throw ApiException(
                message = "HTTP ${result.code}: ${httpStatusText(result.code)}",
                code = result.code,
                apiName = apiName,
            )
        }
    }

    /**
     * Translate a CCSP `retCode: "F"` error envelope into a typed
     * [ApiException]. A no-op for any response that isn't a CCSP envelope, so
     * the US/Canada clients are unaffected. Codes track Home Assistant's
     * `hyundai_kia_connect_api` `_check_response_for_errors`.
     */
    fun checkCcspResponseForErrors(data: ByteArray) {
        val json =
            try {
                parseJsonObject(data)
            } catch (_: Exception) {
                return
            }
        if (json["retCode"].asStringOrNull() != "F") return
        val resCode = json["resCode"].asStringOrNull() ?: return
        val resMsg = json["resMsg"].asStringOrNull() ?: "Unknown error"

        when (resCode) {
            // "Key not authorized" / token expired
            "7501" -> throw ApiException.invalidCredentials(
                "Authentication expired — please sign in again.", apiName = apiName,
            )

            // Invalid deviceId — re-registering the device fixes it
            "4002" -> throw ApiException.invalidVehicleSession(
                "Invalid device ID — please sign out and back in.", apiName = apiName,
            )

            // A previous command is still queued server-side
            "4004" -> throw ApiException.concurrentRequest(
                "A previous command is still being processed. Please wait a moment and try again.",
                apiName = apiName,
            )

            // Control action not supported for this vehicle
            "4005" -> throw ApiException(
                message = "This action isn't supported for this vehicle.",
                code = 400,
                apiName = apiName,
            )

            // Request/response timeout
            "4081", "9999" -> throw ApiException.serverError(
                "The request timed out. Please try again.", apiName = apiName,
            )

            // Remote control temporarily unavailable
            "5031" -> throw ApiException.serverError(
                "Remote control is temporarily unavailable. Please try again later.",
                apiName = apiName,
            )

            // Exceeds number of requests
            "5091" -> throw ApiException.serverError(
                "Too many requests — please wait a while before trying again.",
                apiName = apiName,
            )

            // No data found yet
            "5921" -> throw ApiException(
                message = "No data available from the vehicle yet. Try refreshing in a moment.",
                code = 400,
                apiName = apiName,
            )

            else -> throw ApiException(
                message = "Server returned $resCode: $resMsg",
                code = 400,
                apiName = apiName,
            )
        }
    }

    fun extractApiError(data: ByteArray?): String? {
        if (data == null) return null
        val json =
            try {
                parseJsonObject(data)
            } catch (_: Exception) {
                return null
            }

        val status = json["status"] as? JsonObject
        if (status != null) {
            val errorCode = status["errorCode"].asIntOrNull()
            val errorMessage = status["errorMessage"].asStringOrNull()
            if (errorCode != null && errorCode != 0 && errorMessage != null) {
                return "API Error $errorCode: $errorMessage"
            }
        }

        val topErrorCode = json["errorCode"].asIntOrNull()
        if (topErrorCode != null && topErrorCode != 0) {
            val errorMessage = json["errorMessage"].asStringOrNull() ?: "Unknown error"
            return "API Error $topErrorCode: $errorMessage"
        }

        json["error"].asStringOrNull()?.let { return "API Error: $it" }

        return null
    }

    // Logging

    @Suppress("LongParameterList")
    private fun logHttpRequest(
        requestType: HttpRequestType,
        method: String,
        url: String,
        requestHeaders: Map<String, String>,
        requestBody: String?,
        responseStatus: Int?,
        responseHeaders: Map<String, String>,
        responseBody: String?,
        error: String?,
        apiError: String?,
        startTime: Instant,
        vin: String?,
    ) {
        val sink = config.logSink ?: return
        val duration = Duration.between(startTime, Instant.now()).toMillis() / 1000.0

        // Oversized values go first, and regardless of the redaction setting:
        // a surround-view response is megabytes of base64 JPEG, which would
        // bloat the persisted log and drag the redaction regexes across the
        // whole payload.
        val sizedRequestBody = SensitiveDataRedactor.elideOversizedValues(requestBody)
        val sizedResponseBody = SensitiveDataRedactor.elideOversizedValues(responseBody)

        val loggedRequestHeaders: Map<String, String>
        val loggedRequestBody: String?
        val loggedResponseHeaders: Map<String, String>
        val loggedResponseBody: String?
        if (config.redactPii) {
            loggedRequestHeaders = SensitiveDataRedactor.redactHeaders(requestHeaders)
            loggedRequestBody = SensitiveDataRedactor.redact(sizedRequestBody)
            loggedResponseHeaders = SensitiveDataRedactor.redactHeaders(responseHeaders)
            loggedResponseBody = SensitiveDataRedactor.redact(sizedResponseBody)
        } else {
            loggedRequestHeaders = requestHeaders
            loggedRequestBody = sizedRequestBody
            loggedResponseHeaders = responseHeaders
            loggedResponseBody = sizedResponseBody
        }

        sink.log(
            HttpLog(
                timestamp = startTime,
                accountId = accountId,
                requestType = requestType,
                method = method,
                url = url,
                requestHeaders = loggedRequestHeaders,
                requestBody = loggedRequestBody,
                responseStatus = responseStatus,
                responseHeaders = loggedResponseHeaders,
                responseBody = loggedResponseBody,
                error = error,
                apiError = apiError,
                duration = duration,
                stackTrace = captureStackTrace(),
                vin = vin,
            ),
        )
    }

    private fun captureStackTrace(): String =
        Thread
            .currentThread()
            .stackTrace
            .drop(2)
            .take(10)
            .joinToString("\n") { it.toString() }

    companion object {
        val json: Json = Json { ignoreUnknownKeys = true }

        /**
         * Shared connection/dispatcher pools. Timeouts are generous because
         * real-time vehicle polls legitimately take a minute or more.
         */
        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120))
                .writeTimeout(Duration.ofSeconds(30))
                .build()
        }

        fun parseJsonObject(data: ByteArray): JsonObject {
            val text = data.toString(Charsets.UTF_8)
            if (text.isBlank()) return JsonObject(emptyMap())
            return try {
                json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }
        }

        private fun httpStatusText(code: Int): String =
            when (code) {
                400 -> "bad request"
                401 -> "unauthorized"
                403 -> "forbidden"
                404 -> "not found"
                405 -> "method not allowed"
                408 -> "request timeout"
                409 -> "conflict"
                429 -> "too many requests"
                500 -> "internal server error"
                502 -> "bad gateway"
                503 -> "service unavailable"
                504 -> "gateway timeout"
                else -> "server error"
            }
    }
}

private fun okhttp3.Headers.toMap(): Map<String, String> =
    names().associateWith { name -> get(name) ?: "" }
