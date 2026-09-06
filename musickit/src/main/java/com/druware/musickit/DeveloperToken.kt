package com.druware.musickit

import java.time.Instant

/**
 * A developer token and the instant it stops being usable.
 *
 * @param token the ES256 Apple Media Services JWT. Never logged, never persisted.
 * @param expiresAt when the issuing server says it expires, in UTC.
 */
data class DeveloperToken(val token: String, val expiresAt: Instant)
