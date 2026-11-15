package io.github.driveenglishlab.pleos.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GleoPendingChoiceGatewayTest {
    private open class Adapter:GleoProjectAdapter {
        override val capabilities=VoiceCapabilities()
        override val schemaApproval=Capability.Verified("test-only-existing-schema")
        override val actionParamJsonVerified=true
        override val events:Flow<GleoRuntimeEvent> = emptyFlow()
        val capture=GleoCaptureBinding(UUID.randomUUID(),Capture.unverified(CapturePath.GLEO))
        var broadcasts=0
        override val callback=object:GleoTransportPort {
            override val capabilities=VoiceCapabilities()
            override fun callback(value:GleoCallback):GleoDelivery { broadcasts++;return GleoDelivery.CallbackSent }
        }
        override fun acceptsSender(uid:Int,packageName:String?)=false
        override suspend fun bindReplyOutput(owner:OwnerContext,outputId:UUID,text:String)=false
        override fun captureFor(action:GleoProductAction)=capture
        // New approval deliberately uses the real interface's default false implementation.
    }
    private class Handler:GleoProductHandler {
        var calls=0;var captured:GleoCaptureBinding?=null
        override suspend fun playback(command:String,title:String?,mode:String?,capture:GleoCaptureBinding?):String {
            calls++;captured=capture;check(title==null && mode==null);return "Fixture explicit pending choice"
        }
        override suspend fun question(text:String,title:String?):String=error("No question dispatch")
        override suspend fun practice(command:String,text:String?,title:String?,capture:GleoCaptureBinding?):String=error("No practice dispatch")
    }
    private fun action(command:String)=requireNotNull(GleoProductActionParser.parse("io.github.driveenglishlab.pleos","DRIVE_ENGLISH_PLAYBACK","{\"command\":\"$command\"}"))

    @Test fun existingSchemaApprovalDoesNotDispatchEitherNewPendingCommand()=runBlocking {
        val adapter=Adapter();val handler=Handler();val gateway=GleoGateway(adapter,handler)
        for(command in GleoProductActionParser.pendingChoiceCommands) {
            assertFalse(adapter.pendingChoiceCommandVerified(command))
            assertEquals("fail",gateway.handle(action(command)).status)
        }
        assertEquals(0,handler.calls);assertEquals(0,adapter.broadcasts)
        assertTrue(adapter.capabilities.originalEnglish is Capability.Unavailable)
    }

    @Test fun actualPerCommandFixtureApprovalDoesNotApproveOtherCommandOrManufactureCaptureFidelity()=runBlocking {
        val adapter=object:Adapter(){override fun pendingChoiceCommandVerified(command:String)=command=="pending_wait"}
        val handler=Handler();val gateway=GleoGateway(adapter,handler)
        assertEquals("success",gateway.handle(action("pending_wait")).status)
        assertEquals(1,handler.calls);assertEquals(adapter.capture,handler.captured)
        assertEquals(EnglishEvidence.UNVERIFIED,handler.captured?.capture?.englishEvidence)
        assertEquals("fail",gateway.handle(action("pending_original")).status)
        assertEquals(1,handler.calls);assertEquals(0,adapter.broadcasts)
    }
}
