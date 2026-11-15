package io.github.driveenglishlab.pleos.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthRecoveryDiagnosticsTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun absentProbeCreatesNeitherProtectedFileNorKey()=runBlocking {
        val name="diagnostic-absent-${UUID.randomUUID()}";val alias="${context.packageName}.$name"
        val store=AuthSessionStore(context,name)
        val state=store.diagnosticState()
        assertEquals(ProtectedIdentityEvidence.ABSENT,state.evidence)
        assertEquals(false,state.encryptedFilePresent);assertNull(state.creationStarted);assertNull(state.sessionPresent)
        assertFalse(File(context.noBackupFilesDir,"$name.enc").exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.containsAlias(alias))
    }
    @Test fun creationFenceAndUnreadableCiphertextRemainIntactAndNeverBecomeANewGuest()=runBlocking {
        val name="diagnostic-fence-${UUID.randomUUID()}";val alias="${context.packageName}.$name"
        val file=File(context.noBackupFilesDir,"$name.enc");val store=AuthSessionStore(context,name)
        try {
            store.beginCreation()
            val original=file.readBytes()
            val state=store.diagnosticState()
            assertEquals(true,state.creationStarted);assertEquals(false,state.sessionPresent)
            assertEquals(ProtectedIdentityEvidence.ABSENT,state.evidence)
            assertTrue(original.contentEquals(file.readBytes()))
            val corrupt=original.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
            file.writeBytes(corrupt)
            val failure=AuthSessionStore(context,name).diagnosticState()
            assertEquals(ProtectedIdentityEvidence.UNREADABLE,failure.evidence)
            assertEquals(ProfileConnectionFailure.CIPHERTEXT_INVALID,failure.failure)
            assertNull(failure.creationStarted);assertNull(failure.sessionPresent)
            assertTrue(corrupt.contentEquals(file.readBytes()))
        } finally {
            file.delete();File(file.path+".bak").delete()
            KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}
        }
    }
    @Test fun backupIsInspectedWithoutAtomicRestoreAndMissingKeyNeverCreatesAReplacement()=runBlocking {
        val name="diagnostic-backup-${UUID.randomUUID()}";val alias="${context.packageName}.$name"
        val file=File(context.noBackupFilesDir,"$name.enc");val backup=File(file.path+".bak")
        val store=AuthSessionStore(context,name)
        try {
            store.identity() // Test-owned initial bytes: creationStarted=false.
            val older=file.readBytes()
            store.beginCreation()
            val newer=file.readBytes()
            backup.writeBytes(older)
            val state=AuthSessionStore(context,name).diagnosticState()
            assertEquals(false,state.creationStarted) // AtomicFile's preferred backup, without rename.
            assertEquals(false,state.sessionPresent)
            assertTrue(file.exists());assertTrue(backup.exists())
            assertTrue(newer.contentEquals(file.readBytes()));assertTrue(older.contentEquals(backup.readBytes()))
            val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            keys.deleteEntry(alias)
            val missing=AuthSessionStore(context,name).diagnosticState()
            assertEquals(ProtectedIdentityEvidence.UNREADABLE,missing.evidence)
            assertEquals(ProfileConnectionFailure.KEYSTORE_UNAVAILABLE,missing.failure)
            assertFalse(keys.containsAlias(alias))
            assertTrue(newer.contentEquals(file.readBytes()));assertTrue(older.contentEquals(backup.readBytes()))
        } finally {
            file.delete();backup.delete()
            KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}
        }
    }
}
