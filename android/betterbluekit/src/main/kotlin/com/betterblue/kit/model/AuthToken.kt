package com.betterblue.kit.model

import com.betterblue.kit.json.InstantEpochMillisSerializer
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

@Serializable
data class AuthToken(
    val accessToken: String,
    val refreshToken: String,
    @Serializable(with = InstantEpochMillisSerializer::class)
    val expiresAt: Instant,
) {
    /** Valid while more than a 5-minute buffer remains before expiry. */
    val isValid: Boolean
        get() = Instant.now() < expiresAt.minus(Duration.ofSeconds(300))
}
