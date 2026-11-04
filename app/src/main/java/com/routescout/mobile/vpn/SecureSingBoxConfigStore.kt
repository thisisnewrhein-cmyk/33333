// SPDX-License-Identifier: GPL-3.0-only

package com.routescout.mobile.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted sing-box profile storage backed by Android Keystore AES-GCM. */
class SecureSingBoxConfigStore(context: Context) {
    private val atomicFile = AtomicFile(
        context.applicationContext.filesDir.resolve("singbox-profile.enc")
    )

    fun save(configText: String) {
        require(configText.isNotBlank()) { "Profile must not be empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(configText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val payload = ByteBuffer.allocate(Int.SIZE_BYTES + iv.size + encrypted.size)
            .putInt(iv.size)
            .put(iv)
            .put(encrypted)
            .array()

        val output = atomicFile.startWrite()
        try {
            output.write(payload)
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    fun load(): String? {
        if (!atomicFile.baseFile.exists()) return null
        val payload = atomicFile.openRead().use { it.readBytes() }
        val buffer = ByteBuffer.wrap(payload)
        require(buffer.remaining() >= Int.SIZE_BYTES) { "Encrypted sing-box profile is damaged" }
        val ivSize = buffer.int
        require(ivSize in 12..16 && buffer.remaining() > ivSize) {
            "Encrypted sing-box profile is damaged"
        }
        val iv = ByteArray(ivSize)
        buffer.get(iv)
        val encrypted = ByteArray(buffer.remaining())
        buffer.get(encrypted)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    fun delete() {
        atomicFile.delete()
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        keyStore.load(null)
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        keyStore.load(null)
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "routescout_singbox_profile_key"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
