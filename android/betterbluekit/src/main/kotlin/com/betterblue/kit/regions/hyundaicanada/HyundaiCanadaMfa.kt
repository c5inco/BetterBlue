package com.betterblue.kit.regions.hyundaicanada

// Hyundai Canada MFA / OTP flow. Mirrors HyundaiCanada+MFA.swift.
//
// Hyundai Canada gates new-device logins behind a one-time code delivered by
// email or SMS (errorCode 7110). The flow:
//
//   1. POST /v2/login                        → 7110 ("OTP Required")
//   2. POST /mfa/selverifmeth                → userInfoUuid, emailList, userPhone
//   3. POST /mfa/sendotp     {METHOD}        → otpKey
//   4. POST /mfa/validateotp {otpNo, otpKey} → otpValidationKey
//   5. POST /mfa/genmfatkn   {validationKey} → token (accessToken, refreshToken)
//
// We map this onto the existing [com.betterblue.kit.ApiClient] MFA contract:
//
//   * login() performs steps 1–2 and throws a requires-MFA ApiException with
//     the data harvested from selverifmeth (steps 3–5 happen below).
//   * sendMfaCode performs step 3.
//   * verifyMfaCode performs steps 4 and 5 in sequence and stashes the
//     resulting auth token; the placeholder return values keep the existing
//     UI flow happy.
//   * completeMfaLogin returns the stashed token from step 5.

import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiException
import com.betterblue.kit.HttpMethod
import com.betterblue.kit.MfaMethod
import com.betterblue.kit.MfaVerification
import com.betterblue.kit.json.asIntOrNull
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.log.HttpRequestType
import com.betterblue.kit.model.AuthToken
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

// Login-time challenge detection

/**
 * Detects the `errorCode == "7110"` (OTP Required) response shape without
 * invoking the throwing parser. The server marks this as a *failure*
 * (`responseCode == true` in the modern bool form, `1` in the older int form)
 * carrying an `error.errorCode` of "7110".
 */
internal fun isOtpRequiredResponse(data: ByteArray): Boolean {
    val json = ApiClientBase.parseJsonObject(data)
    val header = json["responseHeader"] as? JsonObject ?: return false
    if (isCanadaResponseSuccess(header["responseCode"])) return false
    val error = json["error"] as? JsonObject ?: return false
    // The Python reference compares as a string ("7110"). The API has been
    // seen sending it as both a string and a number, so accept either form.
    if (error["errorCode"].asStringOrNull() == "7110") return true
    return error["errorCode"].asIntOrNull() == 7110
}

/**
 * Calls `mfa/selverifmeth` to learn which contact methods the account has on
 * file, stashes the userInfoUuid + email for later `sendotp` / `genmfatkn`
 * calls, then throws a requires-MFA exception so the existing MFA UI can pick
 * up the challenge. Always throws.
 */
internal suspend fun HyundaiCanadaClient.beginMfaFlow(cookie: String): Nothing {
    BBLogger.info(BBLogCategory.MFA, "HyundaiCanada: OTP required (errorCode 7110), starting MFA flow")

    val loginMfaHeaders = headers() + ("Cookie" to cookie)

    val (_, response) = performJsonRequest(
        url = "$apiBaseUrl/mfa/selverifmeth",
        method = HttpMethod.POST,
        headers = loginMfaHeaders,
        body = buildJsonObject {
            put("mfaApiCode", "0107")
            put("userAccount", username)
        },
        requestType = HttpRequestType.SEND_MFA,
    )

    val json = parseCanadaResponse(response.body, context = "mfa/selverifmeth")
    val result = json["result"] as? JsonObject
        ?: throw ApiException.logError("Invalid Canada selverifmeth response", apiName = apiName)

    val userInfoUuid = result["userInfoUuid"].asStringOrNull() ?: ""
    val emailList = (result["emailList"] as? JsonArray)?.mapNotNull { it.asStringOrNull() } ?: emptyList()
    val phone = result["userPhone"].asStringOrNull()

    // Match the Python reference (hyundai_kia_connect_api): when `emailList`
    // is empty fall back to the original `username`, NOT the
    // server-normalised `userAccount` field. The server upper-cases its echo
    // (e.g. "gingmar@gmail.com" comes back as "GINGXXX@GMAIL.COM"), and
    // downstream `validateotp` hashes `userAccount` as part of the OTP check
    // — sending the upper-cased form yields errorCode 7999 even with a
    // correct code.
    val email = emailList.firstOrNull() ?: username
    mfaUserInfoUuid = userInfoUuid
    mfaEmail = email
    mfaPhone = phone?.takeIf { it.isNotEmpty() }
    mfaOtpKey = null
    mfaCompletedAuthToken = null

    throw ApiException.requiresMfa(
        xid = userInfoUuid,
        otpKey = null,
        // Email delivery is always available since we fall back to `username`
        // (the user's email-shaped login) when `selverifmeth` doesn't surface
        // a separate email list.
        hasEmail = true,
        hasPhone = !phone.isNullOrEmpty(),
        email = email,
        phone = phone?.takeIf { it.isNotEmpty() },
        apiName = apiName,
    )
}

internal suspend fun HyundaiCanadaClient.sendMfaCodeImpl(xid: String, method: MfaMethod) {
    // We deliberately ignore the `otpKey` param: Hyundai Canada doesn't issue
    // an otpKey until AFTER `sendotp`. Use the `userInfoUuid` we stashed
    // during login() (passed back to us here as `xid`) instead.
    if (xid.isEmpty()) {
        throw ApiException.logError("MFA flow not initialized", apiName = apiName)
    }
    val email = mfaEmail ?: username

    val body = buildJsonObject {
        put("mfaApiCode", "0107")
        put("userInfoUuid", xid)
        put("userAccount", email)
        when (method) {
            MfaMethod.EMAIL -> {
                put("otpMethod", "E")
                put("userPhone", "")
            }

            MfaMethod.SMS -> {
                put("otpMethod", "S")
                // Echo back whatever phone digits selverifmeth returned — the
                // server uses this to locate the SMS destination.
                put("userPhone", mfaPhone ?: "")
            }
        }
    }

    val (_, response) = performJsonRequest(
        url = "$apiBaseUrl/mfa/sendotp",
        method = HttpMethod.POST,
        headers = mfaHeaders(),
        body = body,
        requestType = HttpRequestType.SEND_MFA,
    )

    val json = parseCanadaResponse(response.body, context = "mfa/sendotp")
    val result = json["result"] as? JsonObject
    val otpKey = result?.get("otpKey").asStringOrNull()
        ?: throw ApiException.logError("Invalid Canada sendotp response", apiName = apiName)
    mfaOtpKey = otpKey
}

internal suspend fun HyundaiCanadaClient.verifyMfaCodeImpl(xid: String, code: String): MfaVerification {
    if (xid.isEmpty()) {
        throw ApiException.logError("MFA flow not initialized", apiName = apiName)
    }
    val storedOtpKey = mfaOtpKey
        ?: throw ApiException.logError(
            "MFA verify called before sendMfaCode established otpKey",
            apiName = apiName,
        )

    // `validateotp` and `genmfatkn` need the *original* user-typed username
    // for their `userAccount` field — the Python reference uses `username`
    // directly there, and the server returns errorCode 7999 if we send the
    // server-normalised (often upper-cased) form back. `mfaEmail` is only
    // used as the `otpEmail` payload in `genmfatkn`.
    val validationKey = validateOtp(code = code, otpKey = storedOtpKey)
    val auth = genMfaToken(validationKey = validationKey, otpEmail = mfaEmail ?: username)

    // Stash so completeMfaLogin (called immediately after by the existing
    // protocol flow) can return the real token.
    mfaCompletedAuthToken = auth
    // Drain the otpKey now that it's been consumed; future MFA attempts must
    // restart from sendMfaCode.
    mfaOtpKey = null

    // Hyundai Canada has no separate "remember me" channel — the refresh
    // token *is* the long-lived credential. Surface it here so the host
    // persists it.
    return MfaVerification(rememberMeToken = auth.refreshToken, sid = auth.accessToken)
}

/**
 * Step 4 of the OTP flow — exchange the code the user typed for an
 * `otpValidationKey` we can hand to `genmfatkn`. `userAccount` is the
 * *original* username the user logged in with — the server-normalised form
 * returned by `selverifmeth` is rejected here with errorCode 7999 even when
 * the OTP is correct.
 */
private suspend fun HyundaiCanadaClient.validateOtp(code: String, otpKey: String): String {
    val (_, response) = performJsonRequest(
        url = "$apiBaseUrl/mfa/validateotp",
        method = HttpMethod.POST,
        headers = mfaHeaders(),
        body = buildJsonObject {
            put("otpNo", code)
            put("userAccount", username)
            put("otpKey", otpKey)
            put("mfaApiCode", "0107")
        },
        requestType = HttpRequestType.VERIFY_MFA,
    )

    // 7999 = "We apologize, but your request could not be processed."
    // Hyundai's catch-all when the OTP is wrong / expired or any other
    // validation step trips. Surface it as an invalid-credentials error so
    // the UI shows "Try again or request a new code" instead of the cryptic
    // raw server text.
    val raw = ApiClientBase.parseJsonObject(response.body)
    val rawHeader = raw["responseHeader"] as? JsonObject
    val rawError = raw["error"] as? JsonObject
    if (rawHeader != null &&
        !isCanadaResponseSuccess(rawHeader["responseCode"]) &&
        rawError?.get("errorCode").asStringOrNull() == "7999"
    ) {
        throw ApiException.invalidCredentials(
            "Verification rejected. Try again or request a new code.",
            apiName = apiName,
        )
    }

    val json = parseCanadaResponse(response.body, context = "mfa/validateotp")
    val result = json["result"] as? JsonObject
        ?: throw ApiException.logError("Invalid Canada validateotp response", apiName = apiName)

    val verified = result["verifiedOtp"].asJsonBooleanOrNull() ?: false
    val validationKey = result["otpValidationKey"].asStringOrNull()
    if (!verified || validationKey == null) {
        throw ApiException.invalidCredentials("OTP verification failed", apiName = apiName)
    }
    return validationKey
}

/**
 * Step 5 of the OTP flow — trade the validation key for a real session token.
 * `userAccount` uses the *original* username (per Python ref) while
 * `otpEmail` is the email-form returned by `selverifmeth`.
 */
private suspend fun HyundaiCanadaClient.genMfaToken(validationKey: String, otpEmail: String): AuthToken {
    val (_, response) = performJsonRequest(
        url = "$apiBaseUrl/mfa/genmfatkn",
        method = HttpMethod.POST,
        headers = mfaHeaders(),
        body = buildJsonObject {
            put("userAccount", username)
            put("otpEmail", otpEmail)
            put("mfaApiCode", "0107")
            put("otpValidationKey", validationKey)
            put("mfaYn", "Y")
        },
        requestType = HttpRequestType.VERIFY_MFA,
    )

    val json = parseCanadaResponse(response.body, context = "mfa/genmfatkn")
    val result = json["result"] as? JsonObject
    val token = result?.get("token") as? JsonObject
    val accessToken = token?.get("accessToken").asStringOrNull()
        ?: throw ApiException.logError("Invalid Canada genmfatkn response", apiName = apiName)

    val expiresIn = token?.get("expireIn").asIntOrNull() ?: 3600
    val refreshToken = token?.get("refreshToken").asStringOrNull() ?: ""

    return AuthToken(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = Instant.now().plus(Duration.ofSeconds(expiresIn.toLong())),
    )
}

/**
 * Default header set for any MFA endpoint — mirrors the standard [headers]
 * helper plus the cached cookie when we have one.
 */
private fun HyundaiCanadaClient.mfaHeaders(): Map<String, String> = buildMap {
    putAll(headers())
    cloudFlareCookie?.let { put("Cookie", it) }
}

internal fun HyundaiCanadaClient.completeMfaLoginImpl(): AuthToken {
    val token = mfaCompletedAuthToken
        ?: throw ApiException.logError(
            "completeMfaLogin called before successful verifyMfaCode",
            apiName = apiName,
        )
    // One-shot: clear stashed state so a subsequent MFA flow has to re-run
    // from the top.
    mfaCompletedAuthToken = null
    mfaUserInfoUuid = null
    return token
}
