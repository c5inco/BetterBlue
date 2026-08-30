package com.betterblue.kit.log

/** Utility for redacting sensitive data from JSON strings and headers. */
object SensitiveDataRedactor {
    private val tokenKeys =
        listOf(
            "access_token", "refresh_token", "accessToken", "refreshToken",
            "serializedAuthToken", "rememberMeToken", "Accesstoken", "Pauth",
            "TransactionId", "Cookie", "__cf_bm", "otpKey", "otpValidationKey",
        ).joinToString("|")

    private val emailKeys =
        listOf(
            "username", "email", "userId", "loginId", "notificationEmail",
        ).joinToString("|")

    private val phoneKeys =
        listOf(
            "phone", "phoneNumber", "mobileNumber", "cellPhone",
            "telematicsPhoneNumber", "number",
        ).joinToString("|")

    private val idKeys =
        listOf(
            "accountId", "account_id", "userId", "user_id",
            "memberId", "member_id", "idmId", "nadid",
            "billingAccountNumber", "enrollmentId", "enrollmentCode",
        ).joinToString("|")

    // Device identifiers get partial masking instead of blanket redaction —
    // see the rule below for why.
    private val deviceKeys =
        listOf(
            "deviceid", "deviceId", "deviceKey", "clientuuid",
        ).joinToString("|")

    // The coordinate rule's alternation must stay on one line — splitting it
    // would change what it matches.
    @Suppress("ktlint:standard:max-line-length")
    private val redactionRules: List<Pair<Regex, String>> =
        listOf(
            // Passwords, PINs, and one-time codes (otpNo is the code the user
            // types into the MFA prompt — never include it in a report)
            Regex(""""(password|pin|PIN|otpNo)"\s*:\s*"[^"]*"""") to
                "\"$1\":\"[REDACTED]\"",
            // Bearer tokens
            Regex("""Bearer\s+[A-Za-z0-9._-]+""") to
                "Bearer [REDACTED]",
            // Token/secret fields (handles escaped quotes)
            Regex(""""($tokenKeys)"\s*:\s*"(?:[^"\\]|\\.)*"""") to
                "\"$1\":\"[REDACTED]\"",
            // Latitude/longitude coordinates. Two spellings are accepted: the bare
            // keys, and any camelCase key ENDING in one of them — the surround-view
            // payload reports position as `gpsDetail.coordLat` / `coordLon`.
            // Requiring a capital on the prefixed form keeps innocent words like
            // "flat" and "gallon" from matching. The value alternation is
            // quoted-OR-bare so a malformed quoted value can't leave a tail behind.
            Regex(""""((?:latitude|longitude|lat|lng|lon)|\w+(?:Latitude|Longitude|Lat|Lng|Lon))"\s*:\s*(?:"[-+]?\d+\.?\d*"|[-+]?\d+\.?\d*)""") to
                "\"$1\":\"[REDACTED]\"",
            // Coordinate pairs in arrays or objects
            Regex("""[-+]?\d{1,3}\.\d{3,10}\s*,\s*[-+]?\d{1,3}\.\d{3,10}""") to
                "[LOCATION_REDACTED]",
            // Names
            Regex(""""(firstName|lastName)"\s*:\s*"[^"]*"""") to
                "\"$1\":\"[REDACTED]\"",
            // Email addresses in JSON fields (keep first char + TLD)
            Regex(""""($emailKeys)"\s*:\s*"([^"@])[^"@]*@[^".]*(\.[^"]+)"""") to
                "\"$1\":\"$2***@***$3\"",
            // Emails embedded in URL paths
            Regex("""(/)[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to
                "$1[EMAIL_REDACTED]",
            // Phone numbers
            Regex(""""($phoneKeys)"\s*:\s*"[^"]*"""") to
                "\"$1\":\"[REDACTED]\"",
            // Account/user IDs
            Regex(""""($idKeys)"\s*:\s*"[^"]*"""") to
                "\"$1\":\"[REDACTED]\"",
            // Device identifiers — partially masked rather than blanked, so a
            // reader can tell whether the id CHANGED between requests. A rotating
            // device id is the signature of the Hyundai Canada MFA loop: that
            // region has no refresh endpoint, so the `deviceid` header is the only
            // thing letting the backend recognize an install and skip the OTP
            // challenge. A device id is an opaque installation identifier, not a
            // credential. Keeps the first 8 and last 4 characters.
            Regex(""""($deviceKeys)"\s*:\s*"([A-Za-z0-9]{8})[A-Za-z0-9._-]*([A-Za-z0-9]{4})"""") to
                "\"$1\":\"$2…$3\"",
            // Fallback: a device id too short to partially mask is still blanked.
            // The `…` exclusion keeps this from re-redacting an already-masked value.
            Regex(""""($deviceKeys)"\s*:\s*"[^"…]*"""") to
                "\"$1\":\"[REDACTED]\"",
            // Physical address fields
            Regex(""""(street|postalCode)"\s*:\s*"[^"]*"""") to
                "\"$1\":\"[REDACTED]\"",
            // VIN numbers (keep first 3 and last 4)
            Regex(""""(vin|VIN)"\s*:\s*"([A-HJ-NPR-Z0-9]{3})[A-HJ-NPR-Z0-9]{10}([A-HJ-NPR-Z0-9]{4})"""") to
                "\"$1\":\"$2**********$3\"",
            // Registration IDs
            Regex(""""(regId|regID|regid)"\s*:\s*"[^"]*"""") to
                "\"$1\":\"[REDACTED]\"",
        )

    /** Redacts passwords, tokens, locations, emails, VINs, etc. from a JSON string. */
    fun redact(text: String?): String? {
        var redacted: String = text ?: return null
        for ((pattern, replacement) in redactionRules) {
            redacted = pattern.replace(redacted, replacement)
        }
        return redacted
    }

    /**
     * Replaces JSON string values longer than [threshold] with a note of their
     * length, leaving the surrounding structure intact.
     *
     * Surround-view responses carry megabytes of base64 JPEG in a single
     * field. HTTP logs are persisted, exported, and shared, so storing that
     * verbatim would bloat all three — and the imagery tells a reader nothing
     * the metadata doesn't.
     */
    fun elideOversizedValues(text: String?, threshold: Int = 4096): String? {
        if (text == null) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= threshold) return text

        val quote = '"'.code.toByte()
        val backslash = '\\'.code.toByte()

        val output = ArrayList<Byte>(bytes.size / 2)

        var index = 0
        while (index < bytes.size) {
            if (bytes[index] != quote) {
                output.add(bytes[index])
                index += 1
                continue
            }

            // Walk to the closing quote, honoring backslash escapes.
            var cursor = index + 1
            var escaped = false
            while (cursor < bytes.size) {
                val byte = bytes[cursor]
                if (escaped) {
                    escaped = false
                } else if (byte == backslash) {
                    escaped = true
                } else if (byte == quote) {
                    break
                }
                cursor += 1
            }

            if (cursor >= bytes.size) {
                // Unterminated string — copy the remainder verbatim.
                for (i in index until bytes.size) output.add(bytes[i])
                break
            }

            val length = cursor - index - 1
            if (length > threshold) {
                for (b in "\"[$length characters elided]\"".toByteArray(Charsets.UTF_8)) output.add(b)
            } else {
                for (i in index..cursor) output.add(bytes[i])
            }
            index = cursor + 1
        }

        // Cutting only at quote bytes keeps multi-byte scalars intact.
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    /** Redacts sensitive HTTP headers. */
    fun redactHeaders(headers: Map<String, String>): Map<String, String> {
        // Keys that should always be fully redacted (case-insensitive match)
        val sensitiveKeys =
            setOf(
                "cookie", "set-cookie", "__cf_bm", "transactionid",
                "password", "pin", "bluelinkservicepin",
                "clientsecret", "client_secret", "secretkey",
            )

        return headers.mapValues { (key, value) ->
            val lowerKey = key.lowercase()
            when {
                // Authorization headers get special treatment (keep "Bearer" prefix)
                lowerKey == "authorization" -> "Bearer [REDACTED]"

                sensitiveKeys.contains(lowerKey) -> "[REDACTED]"

                lowerKey.contains("auth") || lowerKey.contains("token") -> "[REDACTED]"

                else -> value
            }
        }
    }
}
