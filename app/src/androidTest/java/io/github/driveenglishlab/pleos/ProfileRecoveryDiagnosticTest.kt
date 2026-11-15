package io.github.driveenglishlab.pleos

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.LearningDatabase
import io.github.driveenglishlab.pleos.data.ProfileRepository
import io.github.driveenglishlab.pleos.data.IncrementalLearningApi
import io.github.driveenglishlab.pleos.domain.LearningReply
import io.github.driveenglishlab.pleos.domain.OwnerBindingStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in actual-data diagnostics. Existing identity inspection is read-only; catalog diagnosis
 * separately uses guarded normal Main restoration before one additional read-only GET. */
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
    /** Normal Main restoration may update legitimate caches/session bytes. Only the one extra GET
     * is read-only. No synthetic safety/provider state or direct Auth/profile mutation is used. */
    @UnstableApi
    @Test fun inspectActualCatalogOnceAfterSafeNormalMainRestoration()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("runActualCatalogDiagnostic")=="true")
        val context=instrumentation.targetContext
        assertEquals("io.github.driveenglishlab.pleos",context.packageName)
        val dbFile=context.getDatabasePath("learning.db")
        assertTrue("Existing product database required.",dbFile.isFile)
        SQLiteDatabase.openDatabase(dbFile.path,null,SQLiteDatabase.OPEN_READONLY).use { assertEquals(5,it.version) }
        val db=Room.databaseBuilder(context,LearningDatabase::class.java,"learning.db").build()
        fun report(outcome:String,code:String,getCount:Int,categories:Int?=null,items:Int?=null,flags:Map<String,Boolean> = emptyMap()) {
            val safeCode=code.takeIf{it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,79}"))}?:"UNCLASSIFIED_FAILURE"
            instrumentation.sendStatus(0,Bundle().apply { putString("stream",
                "ActualCatalogDiagnostic outcome=$outcome code=$safeCode additional_get_count=$getCount http_status=NOT_EXPOSED category_count=${categories?:"null"} item_count=${items?:"null"} "+
                    flags.entries.joinToString(" "){"${it.key}=${it.value}"}+"\n") })
        }
        try {
            val original=diagnosticSnapshot(context,dbFile)
            val local=db.learning().currentOwner()
            val evidence=ProfileRepository(context,db,"",false).debugRecoveryDiagnostics()
            val safeOwner=local?.takeIf { it.status=="active" && db.learning().authCreation(it.profileId)!=null }
            val protectedScope=io.github.driveenglishlab.pleos.data.CurrentAccountScope(context,db)
            val safe=safeOwner!=null && evidence["protected_evidence"]=="AVAILABLE" &&
                evidence["session_present"]=="true" && evidence["encrypted_file_present"]=="true" &&
                protectedScope.permitsProtectedOperation(io.github.driveenglishlab.pleos.domain.OwnerContext(
                    java.util.UUID.fromString(safeOwner.profileId),safeOwner.privacyEpoch,safeOwner.revision,java.util.UUID.randomUUID()))
            if(!safe) { report("SKIPPED","SAFE_RESTORE_PREREQUISITE_UNAVAILABLE",0);return@runBlocking }
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use {
                val host=(context.applicationContext as PleosApplication).productHost
                // App-owned test reflection reuses the actual initialized Host port; no new Auth client/import.
                val profiles=host.javaClass.getDeclaredField("profiles").apply{isAccessible=true}.get(host) as ProfileRepository
                val api=host.javaClass.getDeclaredField("incrementalApi").apply{isAccessible=true}.get(host) as IncrementalLearningApi
                val scope=withTimeoutOrNull(20_000L) {
                    var found=profiles.current()
                    while(found==null || host.coordinator.state.value.owner!=found.owner ||
                        host.coordinator.state.value.ownerBinding!=OwnerBindingStatus.VERIFIED) {
                        delay(250);found=profiles.current()
                    }
                    found
                }
                val restored=diagnosticSnapshot(context,dbFile)
                val normalFlags=original.mapValues{(key,value)->restored[key]==value}.mapKeys{"normal_${it.key}_unchanged"}
                if(scope==null) {report("SKIPPED","MAIN_SCOPE_UNAVAILABLE",0,flags=normalFlags);return@use}
                if(scope.owner.profileId.toString()!=safeOwner!!.profileId || scope.owner.privacyEpoch!=safeOwner.privacyEpoch) {
                    report("SKIPPED","MAIN_OWNER_CHANGED",0,flags=normalFlags);return@use
                }
                val reply=api.catalog(scope,emptyList(),20L,null) // Exactly one additional GET; no retry or preparation.
                val after=diagnosticSnapshot(context,dbFile)
                val getFlags=restored.mapValues{(key,value)->after[key]==value}.mapKeys{"get_${it.key}_unchanged"}
                when(reply) {
                    is LearningReply.Stored -> report("STORED","NONE",1,reply.value.categoryConfig.categories.size,reply.value.items.size,normalFlags+getFlags)
                    is LearningReply.Failed -> report("FAILED",reply.code,1,flags=normalFlags+getFlags)
                }
                assertTrue("Additional catalog GET changed protected owner/settings/outbox evidence; inspect bounded status flags.",getFlags.values.all{it})
            }
        } finally {db.close()}
    }

    private fun diagnosticSnapshot(context:Context,dbFile:File):Map<String,String> {
        val hashes=linkedMapOf<String,String>()
        SQLiteDatabase.openDatabase(dbFile.path,null,SQLiteDatabase.OPEN_READONLY).use { sql ->
            fun digest(query:String):String {
                val hash=MessageDigest.getInstance("SHA-256")
                sql.rawQuery(query,null).use { c -> while(c.moveToNext()) for(i in 0 until c.columnCount) {
                    val value=if(c.isNull(i))byteArrayOf(0) else c.getString(i).toByteArray(Charsets.UTF_8)
                    hash.update(java.nio.ByteBuffer.allocate(4).putInt(value.size).array());hash.update(value)
                } }
                return hash.digest().joinToString(""){"%02x".format(it)}
            }
            hashes["owner"]=digest("SELECT * FROM local_owner ORDER BY rowid")
            hashes["settings"]=digest("SELECT * FROM learning_record WHERE kind IN ('profile_view','profile_settings_command') ORDER BY rowid")
            val tables=sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND (name LIKE '%outbox%' OR name LIKE '%journal%') ORDER BY name",null).use { c ->
                buildList {while(c.moveToNext()){val name=c.getString(0);require(name.matches(Regex("[a-z_]+")));add(name)}}
            }
            hashes["outbox"]=MessageDigest.getInstance("SHA-256").digest(tables.joinToString("\n"){name->name+":"+digest("SELECT * FROM \"$name\" ORDER BY rowid")}.toByteArray()).joinToString(""){"%02x".format(it)}
        }
        val protected=context.noBackupFilesDir.listFiles().orEmpty().filter{it.isFile && it.name.startsWith("learning-") && (it.name.endsWith(".enc") || it.name.endsWith(".enc.bak"))}.sortedBy{it.name}
        hashes["protected"]=MessageDigest.getInstance("SHA-256").digest(protected.joinToString("\n"){it.name+":"+digestOrAbsent(it)}.toByteArray()).joinToString(""){"%02x".format(it)}
        return hashes // Local comparison only. Never output hashes, identifiers, session or payload.
    }

    private fun digestOrAbsent(file:File):String?=if(!file.isFile)null else
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
}
