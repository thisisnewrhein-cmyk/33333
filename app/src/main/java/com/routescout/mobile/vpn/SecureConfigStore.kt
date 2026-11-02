package com.routescout.mobile.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureConfigStore(context: Context) {
    private val configFile = File(context.applicationContext.filesDir, "wireguard-profile.enc")

    fun save(configText: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(configText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val payload = ByteBuffer.allocate(Int.SIZE_BYTES + iv.size + encrypted.size)
            .putInt(iv.size)
            .put(iv)
            .put(encrypted)
            .array()
        configFile.writeBytes(payload)
    }

    fun load(): String? {
        if (!configFile.exists()) return null

        val payload = ByteBuffer.wrap(configFile.readBytes())
        require(payload.remaining() >= Int.SIZE_BYTES) { "Encrypted profile is damaged" }
        val ivSize = payload.int
        require(ivSize in 12..16 && payload.remaining() > ivSize) { "Encrypted profile is damaged" }

        val iv = ByteArray(ivSize)
        payload.get(iv)
        val encrypted = ByteArray(payload.remaining())
        payload.get(encrypted)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    fun delete() {
        configFile.delete()
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        keyStore.load(null)
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        keyStore.load(null)
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
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
        const val KEY_ALIAS = "routescout_wireguard_profile_key"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
