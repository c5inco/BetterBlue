package com.betterblue.kit.policy

import com.betterblue.kit.ApiErrorType

/**
 * When a failure means the session itself is dead and a fresh login is
 * warranted.
 *
 * Only errors that genuinely indicate an invalid session clear the auth token
 * and trigger a re-login — on Kia every re-login is another `authUser` call,
 * and enough of those trip the anti-fraud logic into demanding MFA again.
 * Transient failures (server errors, concurrent-request rejections, network
 * blips) are surfaced to the caller without touching the session.
 */
fun shouldReauthenticate(errorType: ApiErrorType): Boolean =
    when (errorType) {
        ApiErrorType.INVALID_CREDENTIALS,
        ApiErrorType.INVALID_VEHICLE_SESSION,
        ApiErrorType.FAILED_RETRY_LOGIN,
        ApiErrorType.KIA_INVALID_REQUEST,
        -> true

        ApiErrorType.REQUIRES_MFA,
        ApiErrorType.INVALID_PIN,
        ApiErrorType.SERVER_ERROR,
        ApiErrorType.CONCURRENT_REQUEST,
        ApiErrorType.REGION_NOT_SUPPORTED,
        ApiErrorType.STATUS_VERIFICATION_TIMEOUT,
        ApiErrorType.GENERAL,
        -> false
    }

/**
 * Whether a failed COMMAND may be re-sent after re-authenticating.
 *
 * Deliberately NARROWER than [shouldReauthenticate]: commands change vehicle
 * state, so a blind retry can act on the car twice. Only retry when the error
 * proves the backend rejected the request before it ever reached the vehicle
 * — i.e. an authentication failure.
 *
 * [ApiErrorType.KIA_INVALID_REQUEST] is excluded on purpose: it is a generic
 * "request rejected" that Kia's anti-fraud layer can return *after* accepting
 * a command, so retrying it risks a second lock/unlock.
 */
fun shouldRetryCommand(errorType: ApiErrorType): Boolean =
    when (errorType) {
        ApiErrorType.INVALID_CREDENTIALS,
        ApiErrorType.INVALID_VEHICLE_SESSION,
        ApiErrorType.FAILED_RETRY_LOGIN,
        -> true

        else -> false
    }
