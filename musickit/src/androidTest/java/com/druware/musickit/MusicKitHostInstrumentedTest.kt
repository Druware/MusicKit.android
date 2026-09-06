package com.druware.musickit

import android.app.Activity
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The whole stack against a real developer token: configure, storefront, catalog search.
 *
 * Gated on a Card Server being named — see [IntegrationGate]. Nothing here signs in or plays: the
 * sign-in popup needs a human, and playback needs a subscription and a device that is not the
 * emulator.
 */
@RunWith(AndroidJUnit4::class)
class MusicKitHostInstrumentedTest {

    private lateinit var scenario: ActivityScenario<MusicKitTestActivity>
    private lateinit var activity: Activity

    private val lines = CopyOnWriteArrayList<String>()
    private var host: MusicKitHost? = null

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MusicKitTestActivity::class.java)
        scenario.onActivity { activity = it }
    }

    /**
     * The transcript, read out of the flow's own replay rather than collected: the diagnostic flow
     * holds the most recent lines for exactly this reason, and a collector would need a scope that
     * outlives the test body.
     */
    private fun transcript(): List<String> = host?.diagnostic?.replayCache.orEmpty()

    @After
    fun tearDown() {
        lines += transcript()
        runBlocking { host?.close() }
        host = null
        scenario.close()
        Log.i(TAG, lines.joinToString("\n"))
    }

    private suspend fun standUp(): MusicKitHost {
        val cardServer = IntegrationGate.requireCardServer()

        val options = MusicKitOptions(
            developerTokenProvider = CardServerDeveloperTokenProvider(
                http = OkHttpClient(),
                baseUrl = { cardServer },
            ),
            activity = activity,
            appName = "Druware.MusicKit instrumented test",
            appBuild = "0.1.0",
        )

        return MusicKitHost.create(options).also { host = it }
    }

    @Test
    fun configureSucceedsWithARealDeveloperToken(): Unit = runBlocking {
        val built = standUp()

        assertNotNull(
            "MusicKit configured without resolving a storefront, so the token was not accepted",
            built.storefrontId,
        )
        assertNotNull(built.developerTokenExpiresAt)

        // Nobody has been asked to sign in, and "not asked" is not "refused".
        assertTrue(
            "authorization was ${built.authorizationStatus.value} before anyone was asked",
            built.authorizationStatus.value == MusicAuthorizationStatus.NotDetermined ||
                built.authorizationStatus.value == MusicAuthorizationStatus.Authorized,
        )
    }

    @Test
    fun catalogSearchAnswersOnTheDeveloperTokenAlone(): Unit = runBlocking {
        val built = standUp()

        val songs = built.searchCatalogSongs("beatles", 5)

        assertFalse("the catalogue answered with nothing", songs.isEmpty())
        assertTrue("more songs came back than were asked for", songs.size <= 5)
        assertTrue(
            "a song came back with no identifier",
            songs.all { it.id.isNotBlank() },
        )

        Log.i(TAG, "catalog search returned: ${songs.joinToString { "${it.title} / ${it.artistName}" }}")
    }

    @Test
    fun theTranscriptNeverCarriesACredential(): Unit = runBlocking {
        val built = standUp()
        built.searchCatalogSongs("beatles", 1)

        // A developer token is an ES256 JWT, so it always starts with this header.
        assertTrue(
            "a developer token reached the diagnostic transcript",
            transcript().none { it.contains("eyJ") },
        )
        assertFalse("the transcript was empty, so it proved nothing", transcript().isEmpty())
    }

    private companion object {
        const val TAG = "MusicKitInstrumented"
    }
}
