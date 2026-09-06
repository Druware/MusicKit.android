package com.druware.musickit

/**
 * A MusicKit operation failed: the bridge to the page, the page itself, or Apple's API.
 *
 * [message] is written to be shown to a user. When Apple answered with an error envelope its
 * `errors[0].title` and `detail` are folded into the message, and the HTTP status is in [code].
 *
 * @param message what went wrong, as a sentence fit to show a user.
 * @param code a short code — an HTTP status, or Apple's own error code — when there is one.
 * @param cause the failure this one describes, when it wraps one.
 */
class MusicKitException(
    message: String,
    /** The machine-readable code, or null when the failure had none. */
    val code: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    // Exception.getMessage() is nullable; this one never is, because every construction site
    // supplies a sentence.
    override val message: String
        get() = super.message!!
}
