package com.druware.musickit

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume

/**
 * The switch that lets the tests which need a real Apple developer token run.
 *
 * Mirrors the desktop library's gated integration test: those tests need a Card Server to mint a
 * token from, so rather than fail on a machine that has none, they are skipped with a reason.
 *
 * Supply the server with an instrumentation argument:
 * ```
 * ./gradlew :musickit:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.cardServer=https://cards.example.com
 * ```
 */
internal object IntegrationGate {

    private const val ARGUMENT = "cardServer"

    /** The Card Server's base URL, or null when the run was not given one. */
    val cardServer: String?
        get() = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT)
            ?.takeIf { it.isNotBlank() }

    /** Skips the calling test, with a reason, unless a Card Server was named. */
    fun requireCardServer(): String {
        val server = cardServer
        Assume.assumeTrue(
            "Skipped: this test needs a real Apple developer token. Re-run with " +
                "-Pandroid.testInstrumentationRunnerArguments.$ARGUMENT=<card server base URL>.",
            server != null,
        )

        return server!!
    }
}
