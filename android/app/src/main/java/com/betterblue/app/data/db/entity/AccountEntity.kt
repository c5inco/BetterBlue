package com.betterblue.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A BlueLink / Kia Connect account. Secret fields (`password`, `pin`,
 * `refreshToken`, `rememberMeToken`, `serializedAuthToken`) are stored
 * encrypted with the app's Keystore-backed [com.betterblue.app.data.security.CredentialCipher]
 * — unlike the iOS original, which kept them plaintext in SwiftData.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val username: String,
    val encryptedPassword: String,
    val encryptedRefreshToken: String?,
    val encryptedPin: String,
    /** Brand raw value: "hyundai" | "kia" | "fake". */
    val brand: String,
    /** Region raw value: "US" | "CA" | "EU" | ... */
    val region: String,
    /** Epoch millis. */
    val dateCreated: Long,
    val encryptedRememberMeToken: String? = null,
    /** JSON-serialized AuthToken, encrypted. */
    val encryptedAuthToken: String? = null,
    /**
     * Device-trust anchor. Kept across routine re-auth (see
     * AccountRepository.handleApiError) — only an explicit session reset
     * drops it, because a rotating device id makes MFA-gated backends
     * re-challenge on every login.
     */
    val deviceId: String? = null,
    /** HyundaiCanadaVariant serial name; null = default (web portal). */
    val hyundaiCanadaVariant: String? = null,
    /**
     * Epoch millis of the most recent successful fetchVehicles call. Drives
     * the two-hour staleness check before background status fetches.
     */
    val lastVehiclesFetch: Long? = null,
)
