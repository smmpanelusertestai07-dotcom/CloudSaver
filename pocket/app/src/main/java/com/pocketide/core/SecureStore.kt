package com.pocketide.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals small secrets (the GitHub tokens) so only this install can open them. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore and never leaves it. Android erases
 * the key on uninstall, which only means signing in to GitHub again: nothing else is kept here.
 * No user authentication is bound to the key: the app lock guards the screens, and the
 * background connection must keep working with the screen off.
 */
class KeystoreBox(private val alias: String = "pocketide.secure.v1") : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + body
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 2 && sealed[0] == VERSION) { "Unknown sealed format" }
        val ivLength = sealed[1].toInt()
        val iv = sealed.copyOfRange(2, 2 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(sealed, 2 + ivLength, sealed.size - 2 - ivLength)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
    }
}

/**
 * Named sealed blobs in the app's private storage, written atomically (temp file + rename), so a
 * kill mid-write never leaves half a token behind.
 */
class SecureStore(private val dir: File, private val box: SecretBox) {
    fun put(name: String, value: ByteArray) {
        dir.mkdirs()
        val target = file(name)
        val temp = File(dir, "$name.tmp")
        temp.writeBytes(box.seal(value))
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Could not save $name" }
        }
    }

    fun get(name: String): ByteArray? {
        val f = file(name)
        if (!f.isFile) return null
        return runCatching { box.open(f.readBytes()) }.getOrNull()
    }

    fun putString(name: String, value: String) = put(name, value.toByteArray(Charsets.UTF_8))

    fun getString(name: String): String? = get(name)?.toString(Charsets.UTF_8)

    fun delete(name: String) {
        file(name).delete()
    }

    fun has(name: String) = file(name).isFile

    /** Every sealed blob, for "Delete PocketIDE's data from this phone". */
    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun file(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+"))) { "Bad secure name" }
        return File(dir, "$name.bin")
    }
}
