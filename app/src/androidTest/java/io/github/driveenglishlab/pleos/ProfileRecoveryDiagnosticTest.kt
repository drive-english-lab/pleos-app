package io.github.driveenglishlab.pleos

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.LearningDatabase
import io.github.driveenglishlab.pleos.data.ProfileRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, actual product data only. Never calls connect, signup, refresh or credential import. */
@RunWith(AndroidJUnit4::class)
class ProfileRecoveryDiagnosticTest {
    @Test fun inspectInstalledProductIdentityWithoutChangingIt()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runProfileRecoveryDiagnostic")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assertEquals("Diagnostic must target the installed product app.","io.github.driveenglishlab.pleos",context.packageName)
        assertNotEquals(context.packageName,instrumentation.context.packageName)
        val dbFile=context.getDatabasePath("learning.db")
        assertTrue("Existing product database required; diagnostic will not create one.",dbFile.isFile)
        // Do not migrate a different installed revision as part of a diagnostic read.
        SQLiteDatabase.openDatabase(dbFile.path,null,SQLiteDatabase.OPEN_READONLY).use {
            assertEquals("Installed database must already use the current schema.",5,it.version)
        }
        val protectedFiles=listOf(File(context.noBackupFilesDir,"learning-auth-v1.enc"),File(context.noBackupFilesDir,"learning-auth-v1.enc.bak"))
        val before=protectedFiles.map(::digestOrAbsent)
        val db=Room.databaseBuilder(context,LearningDatabase::class.java,"learning.db").build()
        try {
            // Constructor configures ports only. No profile creation or backend method is invoked.
            val repository=ProfileRepository(context,db,"",allowLocalHttp=false)
            val diagnostic=repository.debugRecoveryDiagnostics()
            assertEquals(setOf("stage","failure","protected_evidence","encrypted_file_present","creation_started","session_present",
                "owner_present","auth_creation_fence_present","last_connection_stage","last_connection_failure"),diagnostic.keys)
            assertTrue(diagnostic.values.all{it.matches(Regex("[A-Z_]+|true|false|null"))})
            assertTrue("Protected files must be byte-identical after the read-only probe.",before==protectedFiles.map(::digestOrAbsent))
            // Only the bounded allowlist is sent to the runner; hashes and identifiers stay local.
            instrumentation.sendStatus(0,Bundle().apply {
                putString("stream",diagnostic.entries.joinToString(prefix="ProfileRecoveryDiagnostic ",postfix="\n",separator=" "){"${it.key}=${it.value}"})
            })
        } finally { db.close() }
    }
    private fun digestOrAbsent(file:File):String?=if(!file.isFile)null else
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
}
