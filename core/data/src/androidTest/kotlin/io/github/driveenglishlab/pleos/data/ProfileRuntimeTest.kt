package io.github.driveenglishlab.pleos.data

import android.os.Bundle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.LearningReply
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real SDK -> JWT/API -> DB. Host runner removes the owned anonymous Auth user. */
@RunWith(AndroidJUnit4::class)
class ProfileRuntimeTest {
    @Test fun anonymousSdkRestoresTheSameProfileAndUpdatesRealSettings() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveProfileRuntime") == "true")
        check(BuildConfig.SUPABASE_URL == "https://odfhxyelkwdaekoxhzwt.supabase.co")
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val name = "live-profile-" + UUID.randomUUID(); val store = AuthSessionStore(context, name)
        val db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java).build()
        var auth = SupabaseProfileAuth(store)
        fun repository() = ProfileRepository(db, auth) { sessions -> LearningApi(context, "http://127.0.0.1:8008", sessions, allowLocalHttp = true) }
        try {
            assertTrue("Real public catalog read failed.", CatalogRepository(context).catalogPage() is io.github.driveenglishlab.pleos.domain.CatalogRead.Stored)
            val local = db.learning().provisionalOwner()
            val first = repository().connect(); assertTrue("Real SDK/bootstrap failed.", first is LearningReply.Stored)
            first as LearningReply.Stored
            assertEquals(local.profileId, first.value.profileContext.profileId)
            assertEquals("active", db.learning().currentOwner()!!.status)
            val subject = auth.currentSession()!!.subjectId
            auth.close(); auth = SupabaseProfileAuth(AuthSessionStore(context, name))
            val restored = repository(); val second = restored.connect()
            assertTrue("Protected SDK session restore failed.", second is LearningReply.Stored)
            assertEquals(subject, auth.currentSession()!!.subjectId)
            assertEquals(first.value.profileContext, (second as LearningReply.Stored).value.profileContext)
            val request = WirePersonalSettingsPatch(UUID.randomUUID().toString(), second.value.profileContext,
                second.value.settings.version, WirePersonalSettingsPatchSet(followupPolicy = "none"), true, "5")
            val saved = restored.updateSettings(request); assertTrue("Real settings update failed.", saved is LearningReply.Stored)
            assertTrue((saved as LearningReply.Stored).value.settings.onboardingComplete)
            assertEquals("none", saved.value.settings.followupPolicy)
            assertTrue(restored.refresh() is LearningReply.Stored)
        } finally {
            // Only a UUID is reported. Tokens/headers/session payloads never enter instrumentation output.
            store.identity().session?.user?.id?.let {
                instrumentation.sendStatus(2, Bundle().apply { putString("live_profile_test_user", UUID.fromString(it).toString()) })
            }
            auth.close(); db.close()
            File(context.noBackupFilesDir, "$name.enc").delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("${context.packageName}.$name") }
        }
    }
}
