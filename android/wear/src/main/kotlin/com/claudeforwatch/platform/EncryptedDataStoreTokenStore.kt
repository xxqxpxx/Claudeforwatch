package com.claudeforwatch.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.claudeforwatch.core.auth.Credentials
import com.claudeforwatch.core.auth.TokenStore
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable Android Keystore key (docs/PROTOCOL.md §1.1, alias
 * `cfw_auth`). Output layout: 12-byte IV ‖ ciphertext+tag.
 */
class KeystoreCipher(private val alias: String = ALIAS) {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_SIZE) { "ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_SIZE))
        return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    companion object {
        const val ALIAS = "cfw_auth"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}

private object BytesSerializer : Serializer<ByteArray> {
    override val defaultValue: ByteArray = ByteArray(0)
    override suspend fun readFrom(input: InputStream): ByteArray =
        try { input.readBytes() } catch (e: Exception) { throw CorruptionException("unreadable", e) }
    override suspend fun writeTo(t: ByteArray, output: OutputStream) = output.write(t)
}

/**
 * A DataStore file holding one encrypted blob. Decrypt failure (key wiped after a factory
 * reset/restore, corruption) ⇒ the file is cleared and `null` is returned (PROTOCOL §1.1).
 */
class EncryptedBlobStore(context: Context, fileName: String, private val cipher: KeystoreCipher = KeystoreCipher()) {
    private val store: DataStore<ByteArray> = DataStoreFactory.create(
        serializer = BytesSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { ByteArray(0) },
        produceFile = { context.applicationContext.dataStoreFile(fileName) },
    )

    suspend fun read(): ByteArray? {
        val blob = store.data.first()
        if (blob.isEmpty()) return null
        return try {
            cipher.decrypt(blob)
        } catch (e: Exception) {
            clear()
            null
        }
    }

    suspend fun write(plain: ByteArray) {
        val blob = cipher.encrypt(plain)
        store.updateData { blob }
    }

    suspend fun clear() {
        store.updateData { ByteArray(0) }
    }
}

/** [TokenStore] over `auth.pb` (PROTOCOL §1.1). The JSON record never leaves memory unencrypted. */
class EncryptedDataStoreTokenStore(private val blobs: EncryptedBlobStore) : TokenStore {
    constructor(context: Context) : this(EncryptedBlobStore(context, "auth.pb"))

    override suspend fun load(): Credentials? {
        val bytes = blobs.read() ?: return null
        return try {
            Credentials.decode(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            blobs.clear()
            null
        }
    }

    override suspend fun save(credentials: Credentials) = blobs.write(credentials.encode().toByteArray(Charsets.UTF_8))

    override suspend fun clear() = blobs.clear()
}
