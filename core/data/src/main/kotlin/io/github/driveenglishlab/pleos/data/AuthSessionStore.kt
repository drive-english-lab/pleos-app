package io.github.driveenglishlab.pleos.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class StoredIdentity(
    val installationId: String = UUID.randomUUID().toString(),
    val creationStarted: Boolean = false,
    val session: UserSession? = null,
    val bootstrapProfileId: String? = null,
    val bootstrapOperationId: String? = null,
    val accountState: String? = null,
) { override fun toString() = "StoredIdentity(credentials=redacted)" }

/** Ciphertext only, outside backup; unreadable state is an error, never an empty session.
 * One process-wide instance owns this file. Neither SDK nor callers use loadSessionOrNull.
 */
class AuthSessionStore internal constructor(context: Context, name: String = "learning-auth-v1") : SessionManager {
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.enc"))
    private val alias = "${context.packageName}.$name"
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        // Existing ciphertext without its key is never replaced by a new identity.
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) { "Protected session key unavailable." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun existingKey():SecretKey = KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        .getKey(alias,null) as? SecretKey ?: throw java.security.UnrecoverableKeyException("Protected session key unavailable.")

    private fun read(): StoredIdentity {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return StoredIdentity().also(::write)
        val bytes = file.openRead().use { input ->
            require(input.available() <= 128 * 1024)
            input.readBytes().also { require(it.size in 30..128 * 1024) }
        }
        return decodeIdentity(bytes,::key)
    }

    private fun decodeIdentity(bytes:ByteArray,keyProvider:()->SecretKey):StoredIdentity {
        require(bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        return json.decodeFromString<StoredIdentity>(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8))
    }

    private fun write(value: StoredIdentity) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(json.encodeToString(StoredIdentity.serializer(), value).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    /** Read-only recovery evidence. Absence never creates a replacement installation. */
    internal suspend fun protectedEvidence(): io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence =
        withContext(Dispatchers.IO) { mutex.withLock {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
                return@withLock io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.ABSENT
            try {
                if (read().session == null) io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.ABSENT
                else io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.AVAILABLE
            } catch (_: java.io.IOException) { io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.READ_RETRYABLE }
            catch (_: Exception) { io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.UNREADABLE }
        } }

    /** A missing file stays missing. This probe never creates a key, installation or session. */
    internal suspend fun diagnosticState():ProtectedIdentityDiagnostic=withContext(Dispatchers.IO) { mutex.withLock {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return@withLock ProtectedIdentityDiagnostic(
            false,null,null,io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.ABSENT)
        try {
            // AtomicFile.openRead() can rename its backup. Inspect the same preferred bytes
            // through FileInputStream instead, without repairing or replacing either file.
            val backup=File(file.baseFile.path+".bak")
            val source=if(backup.exists())backup else file.baseFile
            val bytes=source.inputStream().use { input ->
                require(input.available()<=128*1024)
                input.readBytes().also{require(it.size in 30..128*1024)}
            }
            val saved=decodeIdentity(bytes,::existingKey)
            ProtectedIdentityDiagnostic(true,saved.creationStarted,saved.session!=null,
                if(saved.session==null)io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.ABSENT
                else io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.AVAILABLE)
        } catch(error:kotlinx.coroutines.CancellationException) { throw error }
          catch(error:Exception) { ProtectedIdentityDiagnostic(true,null,null,
            if(error is java.io.IOException)io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.READ_RETRYABLE
            else io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.UNREADABLE,profileConnectionFailure(error)) }
    } }

    internal suspend fun identity(): StoredIdentity = withContext(Dispatchers.IO) { mutex.withLock { read() } }
    internal suspend fun beginCreation() = withContext(Dispatchers.IO) { mutex.withLock {
        val value = read(); check(!value.creationStarted && value.session == null)
        write(value.copy(creationStarted = true, session = null))
    } }
    internal suspend fun bootstrapOperation(profileId: UUID): UUID = withContext(Dispatchers.IO) { mutex.withLock {
        val value = read()
        if (value.bootstrapProfileId != null) {
            check(value.bootstrapProfileId == profileId.toString()) { "Profile transition required." }
            UUID.fromString(value.bootstrapOperationId)
        } else UUID.randomUUID().also { operation -> write(value.copy(bootstrapProfileId = profileId.toString(), bootstrapOperationId = operation.toString())) }
    } }

    internal suspend fun saveAccountState(value: String?) = withContext(Dispatchers.IO) { mutex.withLock {
        write(read().copy(accountState = value))
    } }
    /** Explicit, journalled transition only. The caller fences Room before changing credentials. */
    internal suspend fun replaceForTransition(expectedSubject: String?, replacement: StoredIdentity) = withContext(Dispatchers.IO) { mutex.withLock {
        val previous = read()
        check(previous.session?.user?.id == expectedSubject)
        check(replacement.installationId == previous.installationId)
        write(replacement)
    } }
    internal suspend fun initializeIsolated(identity: StoredIdentity) = withContext(Dispatchers.IO) { mutex.withLock {
        val old = read()
        check(old.session == null || old.session.user?.id == identity.session?.user?.id)
        write(identity)
    } }

    override suspend fun saveSession(session: UserSession) = withContext(Dispatchers.IO) { mutex.withLock {
        val value = read()
        check(value.session?.user?.id == null || value.session.user?.id == session.user?.id) { "Explicit account transition required." }
        write(value.copy(creationStarted = true, session = session))
    } }
    override suspend fun loadSession(): UserSession = identity().session ?: error("No protected session stored.")
    // Clearing credentials retains the creation fence: a failure must not silently make a new guest.
    override suspend fun deleteSession() = withContext(Dispatchers.IO) { mutex.withLock {
        val value = read(); write(value.copy(creationStarted = true, session = null))
    } }
    override fun toString() = "AuthSessionStore(credentials=redacted)"
}
