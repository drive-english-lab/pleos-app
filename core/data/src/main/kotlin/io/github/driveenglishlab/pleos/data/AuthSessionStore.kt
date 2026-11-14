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
import java.util.concurrent.ConcurrentHashMap
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
    private val storageName:String by lazy {
        if(name==DEFAULT_NAME)resolvedBinding(context).reference else name
    }
    private val file by lazy { AtomicFile(File(context.noBackupFilesDir,"$storageName.enc")) }
    private val alias by lazy { "${context.packageName}.$storageName" }
    private val mutex=locks.getOrPut(context.packageName){Mutex()}
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    companion object {
        private const val DEFAULT_NAME="learning-auth-v1"
        private const val SELECTOR_NAME="learning-auth-active-v1"
        private val locks=ConcurrentHashMap<String,Mutex>()
        private val selectionJson=Json{encodeDefaults=true;ignoreUnknownKeys=false}
        private fun resolvedBinding(context:Context):ProtectedAuthBinding {
            val saved=AuthSessionStore(context,SELECTOR_NAME).readExistingIdentity()?:return ProtectedAuthBinding(DEFAULT_NAME,0,null)
            return selectionJson.decodeFromString(ProtectedAuthBinding.serializer(),requireNotNull(saved.accountState)).also {
                require(it.revision>=1 && it.operationId!=null && it.reference.matches(Regex("learning-account-target-[a-f0-9-]{36}")))
                UUID.fromString(it.operationId);UUID.fromString(it.reference.removePrefix("learning-account-target-"))
            }
        }
        /** Pointer reads never inspect or reset an unreadable source session. */
        internal suspend fun activeBinding(context:Context):ProtectedAuthBinding=withContext(Dispatchers.IO){
            val selector=AuthSessionStore(context,SELECTOR_NAME)
            selector.mutex.withLock{resolvedBinding(context)}
        }
        /** Only a journaled, server-confirmed target may become the active store.
         * The old ciphertext, key, creation fence and isolated records remain untouched.
         */
        internal suspend fun selectVerifiedTarget(context:Context,expected:ProtectedAuthBinding,target:String,operation:UUID)=withContext(Dispatchers.IO){
            require(target=="learning-account-target-$operation")
            val selector=AuthSessionStore(context,SELECTOR_NAME)
            selector.mutex.withLock {
                val current=resolvedBinding(context)
                if(current.reference==target && current.operationId==operation.toString())return@withLock
                require(current==expected){"Protected active store changed."}
                val identity=requireNotNull(AuthSessionStore(context,target).readExistingIdentity())
                require(identity.session!=null){"Verified target session unavailable."}
                val proof=selectionJson.decodeFromString(ProtectedLinkedTarget.serializer(),requireNotNull(identity.accountState))
                require(proof.operationId==operation.toString() && proof.sourceBinding==expected && proof.confirmedProfile?.profileKind=="linked")
                UUID.fromString(requireNotNull(proof.verifiedSubject))
                identity.session.user?.id?.let{require(it==proof.verifiedSubject)}
                val next=ProtectedAuthBinding(target,current.revision+1,operation.toString())
                val previous=selector.readExistingIdentity()?:StoredIdentity()
                selector.write(previous.copy(accountState=selectionJson.encodeToString(ProtectedAuthBinding.serializer(),next)))
            }
        }
    }
    /** Direct preferred-file read: no AtomicFile recovery, key generation or installation creation. */
    internal fun readExistingIdentity():StoredIdentity? {
        val backup=File(file.baseFile.path+".bak")
        val source=if(backup.exists())backup else file.baseFile
        if(!source.exists())return null
        val bytes=source.inputStream().use{input->require(input.available()<=128*1024);input.readBytes().also{require(it.size in 30..128*1024)}}
        return decodeIdentity(bytes,::existingKey)
    }

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
            try {
                if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
                    return@withLock io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.ABSENT
                if (read().session == null) io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.ABSENT
                else io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.AVAILABLE
            } catch (_: java.io.IOException) { io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.READ_RETRYABLE }
            catch (_: Exception) { io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.UNREADABLE }
        } }

    /** A missing file stays missing. This probe never creates a key, installation or session. */
    internal suspend fun diagnosticState():ProtectedIdentityDiagnostic=withContext(Dispatchers.IO) { mutex.withLock {
        try {
            if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return@withLock ProtectedIdentityDiagnostic(
                false,null,null,io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.ABSENT)
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
