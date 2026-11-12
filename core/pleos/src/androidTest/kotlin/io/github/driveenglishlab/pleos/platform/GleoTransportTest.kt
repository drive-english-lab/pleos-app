package io.github.driveenglishlab.pleos.platform

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GleoTransportTest {
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var sent: Intent? = null
        override fun sendBroadcast(intent: Intent, receiverPermission: String?) { sent = intent }
    }
    private fun callback(data: String? = null) = GleoCallback("DRIVE_ENGLISH_PLAYBACK", "success",
        "The synthetic playback action was received.", data, "silent")

    @Test fun unknownProjectRouteCannotSendOrClaimSpeechCapabilities() {
        val context = RecordingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport = GleoTransport(context)
        assertTrue(transport.callback(callback()) is GleoDelivery.Unavailable)
        assertNull(context.sent); assertTrue(transport.capabilities.outputCompletion is Capability.Unavailable)
        assertTrue(transport.capabilities.originalEnglish is Capability.Unavailable)
    }

    @Test fun approvedEnvelopeTransmissionDoesNotBecomeOutputEndOrOriginalEnglishProof() {
        val context = RecordingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        // Explicit fixture route, intercepted above. No actual Gleo broadcast is sent.
        val transport = GleoTransport(context, VerifiedGleoCallbackRoute("test.verified.gleo", null, "test-only-route"), setOf("DRIVE_ENGLISH_PLAYBACK"))
        assertTrue(transport.callback(callback("[\"invalid-object\"]")) is GleoDelivery.Unavailable); assertNull(context.sent)
        assertEquals(GleoDelivery.CallbackSent, transport.callback(callback("[{\"title\":\"Synthetic fixture\"}]")))
        assertEquals("test.verified.gleo", context.sent!!.getPackage())
        val response = JSONObject(context.sent!!.getStringExtra("response")!!)
        assertEquals("success", response.getString("status")); assertEquals("silent", response.getString("mode"))
        assertTrue(transport.capabilities.outputCompletion is Capability.Unavailable)
        assertTrue(transport.capabilities.originalEnglish is Capability.Unavailable)
    }
}
