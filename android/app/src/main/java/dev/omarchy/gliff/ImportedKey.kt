package dev.omarchy.gliff

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only keys explicitly selected by the user enter this app-private encrypted store. */
class ImportedKey(private val context: Context) {
    private val file get() = java.io.File(context.noBackupFilesDir, "imported-key.aes")
    val exists get() = file.exists()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("gliff-import", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("gliff-import", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(bytes: ByteArray, valid: () -> Boolean = { true }) {
        require(bytes.size in 1..65536) { "Key must be at most 64 KiB" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(bytes)
        val temp = java.io.File(context.noBackupFilesDir, "imported-key.tmp")
        temp.outputStream().use { it.write(cipher.iv.size); it.write(cipher.iv); it.write(encrypted) }
        if (!valid()) { temp.delete(); throw java.io.InterruptedIOException("Key import cancelled") }
        check(temp.renameTo(file)) { "Could not save imported key" }
    }
    fun read(): ByteArray {
        val data = file.readBytes()
        val size = data[0].toInt() and 255
        require(size == 12 && data.size > size + 1)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, 1 + size)))
            doFinal(data, 1 + size, data.size - size - 1)
        }
    }
}
