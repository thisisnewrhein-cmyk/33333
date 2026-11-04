package com.routescout.mobile.vpn

import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.cert.CertificateFactory
import java.util.Locale

/**
 * Sanitizes untrusted VPN Gate OpenVPN profiles before handing them to another client.
 *
 * This is a constrained parser, not a complete OpenVPN parser. It validates the
 * structure of trust material and rejects local file references/proxy settings
 * that this importer cannot safely normalize. The TLS engine still performs
 * cryptographic chain and peer verification when the tunnel is established.
 */
internal object OpenVpnProfileSanitizer {
    private val inlineBlocks = setOf(
        "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2",
        "pkcs12", "crl-verify", "dh", "extra-certs", "secret",
        "peer-fingerprint", "auth-user-pass", "http-proxy-user-pass",
        "auth-gen-token-secret"
    )

    private val discardedInlineBlocks = setOf(
        "auth-user-pass", "http-proxy-user-pass", "auth-gen-token-secret"
    )

    private val fileDirectives = setOf(
        "ca", "capath", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2",
        "pkcs12", "crl-verify", "dh", "extra-certs", "secret",
        "askpass", "tls-export-cert", "http-proxy-user-pass"
    )

    private val strippedDirectives = setOf(
        "up", "down", "route-up", "route-pre-down", "ipchange", "plugin",
        "daemon", "log", "log-append", "status", "writepid", "cd", "chroot",
        "user", "group", "setcon", "iproute", "tmp-dir", "script-security",
        "client-connect", "client-disconnect", "learn-address", "tls-verify",
        "auth-user-pass-verify", "tls-crypt-v2-verify", "client-config-dir",
        "verify-hash", "auth-gen-token-secret"
    )

    private val unsupportedProxyDirectives = setOf(
        "http-proxy", "http-proxy-option", "socks-proxy"
    )

    private val sha256Fingerprint = Regex(
        "^(?:[0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}$|^[0-9a-fA-F]{64}$"
    )

    fun sanitize(source: String): String {
        if (source.isBlank()) throw IOException("The OpenVPN profile is empty")
        if (source.length > MAX_PROFILE_CHARS) {
            throw IOException("The OpenVPN profile exceeds the supported size limit")
        }

        val output = StringBuilder()
        val caPem = StringBuilder()
        var activeBlock: String? = null
        var hasClient = false
        var hasRemote = false
        var hasAuthUserPass = false
        var hasInlineCaCertificate = false
        var caCertificateOpen = false
        var hasPeerFingerprint = false

        source.lineSequence().forEach { line ->
            val trimmed = line.trim()

            if (activeBlock != null) {
                val block = activeBlock

                if (trimmed.equals("</$block>", ignoreCase = true)) {
                    if (block == "ca") {
                        if (caCertificateOpen) {
                            throw IOException("The inline CA certificate is incomplete")
                        }
                        validateCaCertificates(caPem.toString())
                        hasInlineCaCertificate = true
                    }

                    activeBlock = null
                    when {
                        block == "auth-user-pass" -> output.appendLine("auth-user-pass")
                        block in discardedInlineBlocks -> Unit
                        else -> output.appendLine(line)
                    }
                    return@forEach
                }

                if (block in discardedInlineBlocks) {
                    return@forEach
                }

                if (block == "ca") {
                    caPem.appendLine(line)
                    if (trimmed == "-----BEGIN CERTIFICATE-----") {
                        if (caCertificateOpen) {
                            throw IOException("The inline CA certificate has a nested BEGIN marker")
                        }
                        caCertificateOpen = true
                    } else if (trimmed == "-----END CERTIFICATE-----") {
                        if (!caCertificateOpen) {
                            throw IOException("The inline CA certificate has an unmatched END marker")
                        }
                        caCertificateOpen = false
                    }
                }

                if (block == "peer-fingerprint" &&
                    trimmed.isNotBlank() &&
                    !trimmed.startsWith("#") &&
                    !trimmed.startsWith(";")
                ) {
                    val value = stripInlineComment(trimmed)
                    if (!sha256Fingerprint.matches(value)) {
                        throw IOException("The inline peer fingerprint is not a valid SHA-256 fingerprint")
                    }
                    hasPeerFingerprint = true
                }

                output.appendLine(line)
                return@forEach
            }

            val openingBlock = inlineBlocks.firstOrNull {
                trimmed.equals("<$it>", ignoreCase = true)
            }
            if (openingBlock != null) {
                activeBlock = openingBlock
                if (openingBlock == "auth-user-pass") hasAuthUserPass = true
                if (openingBlock !in discardedInlineBlocks) output.appendLine(line)
                return@forEach
            }

            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                output.appendLine(line)
                return@forEach
            }

            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            val directive = parts.first().lowercase(Locale.ROOT)

            if (directive in unsupportedProxyDirectives) {
                throw IOException("OpenVPN proxy profiles are not supported by the safe importer")
            }

            if (directive.startsWith("management")) return@forEach
            if (directive.startsWith("pkcs11-")) return@forEach
            if (directive in strippedDirectives) return@forEach

            if (directive in fileDirectives) {
                throw IOException("The OpenVPN profile references an external '$directive' file")
            }

            if (directive == "auth-user-pass") {
                hasAuthUserPass = true
                // Remove any supplied local path and let the receiving client prompt.
                output.appendLine("auth-user-pass")
                return@forEach
            }

            if (directive == "peer-fingerprint") {
                val values = stripInlineComment(parts.getOrNull(1).orEmpty())
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                if (values.isEmpty() || values.any { !sha256Fingerprint.matches(it) }) {
                    throw IOException("The peer fingerprint is not a valid SHA-256 fingerprint")
                }
                hasPeerFingerprint = true
                output.appendLine(line)
                return@forEach
            }

            if (directive == "client" || directive == "tls-client") hasClient = true

            if (directive == "dev") {
                val device = stripInlineComment(parts.getOrNull(1).orEmpty())
                    .lowercase(Locale.ROOT)
                if (!device.matches(Regex("tun[0-9]*"))) {
                    throw IOException("Only OpenVPN TUN profiles are supported")
                }
            }

            if (directive == "remote") {
                val remoteParts = trimmed.split(Regex("\\s+"))
                val port = remoteParts.getOrNull(2)?.toIntOrNull()
                if (remoteParts.size >= 3 && port != null && port in 1..65535) {
                    hasRemote = true
                }
            }

            if (directive == "remote-cert-tls") {
                val role = stripInlineComment(parts.getOrNull(1).orEmpty())
                    .lowercase(Locale.ROOT)
                if (role != "server") {
                    throw IOException("The OpenVPN profile must verify a remote TLS server certificate")
                }
            }

            output.appendLine(line)
        }

        if (activeBlock != null) {
            throw IOException("The OpenVPN profile contains an unfinished inline block")
        }
        if (!hasClient || !hasRemote) {
            throw IOException("The OpenVPN profile is missing a valid client or remote endpoint")
        }
        if (!hasInlineCaCertificate && !hasPeerFingerprint) {
            throw IOException("The OpenVPN profile has no parseable inline X.509 CA certificate or SHA-256 peer fingerprint")
        }

        val hasRemoteCertTlsServer = output.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed.startsWith("remote-cert-tls", ignoreCase = true) &&
                stripInlineComment(trimmed.substringAfter("remote-cert-tls", ""))
                    .equals("server", ignoreCase = true)
        }
        if (!hasRemoteCertTlsServer && !hasPeerFingerprint) output.appendLine("remote-cert-tls server")

        // Never introduce a credential prompt when the source profile did not request it.
        if (hasAuthUserPass && output.lineSequence().none { it.trim() == "auth-user-pass" }) {
            output.appendLine("auth-user-pass")
        }

        return output.toString().trimEnd() + "\n"
    }

    private fun validateCaCertificates(pem: String) {
        val certificateBlocks = Regex(
            "-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----"
        ).findAll(pem).toList()
        if (certificateBlocks.isEmpty()) {
            throw IOException("The inline CA block contains no X.509 certificate")
        }

        val factory = CertificateFactory.getInstance("X.509")
        try {
            certificateBlocks.forEach { block ->
                factory.generateCertificate(
                    ByteArrayInputStream(block.value.toByteArray(Charsets.US_ASCII))
                )
            }
        } catch (error: Exception) {
            throw IOException("The inline CA block is not a parseable X.509 certificate bundle", error)
        }
    }

    private fun stripInlineComment(value: String): String =
        value.substringBefore("#").substringBefore(";").trim()

    private const val MAX_PROFILE_CHARS = 256 * 1024
}
