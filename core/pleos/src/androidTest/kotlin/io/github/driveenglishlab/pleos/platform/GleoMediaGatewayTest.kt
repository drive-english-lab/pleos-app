package io.github.driveenglishlab.pleos.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GleoMediaGatewayTest {
    @Test fun unverifiedSchemaAndAnswerTextCannotInvokeMediaCommands() = runBlocking {
        val owner = OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID()); var calls = 0
        val port = ListeningCommandPort { _, _ -> calls++; ListeningCommandResult.Accepted }
        assertTrue(GleoMediaGateway(port).handle(owner,"next") is ListeningCommandResult.Unavailable)
        val mapped = GleoMediaGateway(port,Capability.Verified("test-only-reviewed-command-mapping"),mapOf("next" to ListeningMediaCommand.NEXT))
        assertTrue(mapped.handle(owner,"I would not stop the project next year.") is ListeningCommandResult.Unavailable)
        assertEquals(ListeningCommandResult.Accepted,mapped.handle(owner,"next")); assertEquals(1,calls)
    }
}
