package com.routescout.mobile.vpn

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenVpnProfileSanitizerTest {

    @Test
    fun addsServerCertificatePurposeCheckWhenMissing() {
        val sanitized = OpenVpnProfileSanitizer.sanitize(validProfile())

        assertTrue(sanitized.contains("remote-cert-tls server"))
        assertTrue(sanitized.contains("auth-user-pass"))
    }

    @Test
    fun doesNotDuplicateExistingServerCertificatePurposeCheck() {
        val sanitized = OpenVpnProfileSanitizer.sanitize(
            validProfile(extra = "remote-cert-tls server")
        )

        assertEquals(1, sanitized.lines().count { it.trim() == "remote-cert-tls server" })
    }

    @Test
    fun rejectsClientCertificatePurposeForRemotePeer() {
        var rejected = false
        try {
            OpenVpnProfileSanitizer.sanitize(validProfile(extra = "remote-cert-tls client"))
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun removesLocalScriptAndManagementDirectives() {
        val sanitized = OpenVpnProfileSanitizer.sanitize(
            validProfile(extra = "up /tmp/untrusted.sh\nmanagement 127.0.0.1 7505")
        )

        assertFalse(sanitized.contains("untrusted.sh"))
        assertFalse(sanitized.contains("management 127.0.0.1"))
        assertTrue(sanitized.contains("<ca>"))
        assertTrue(sanitized.contains("BEGIN CERTIFICATE"))
    }

    @Test
    fun rejectsExternalCertificatePaths() {
        var rejected = false
        try {
            OpenVpnProfileSanitizer.sanitize(
                """
                client
                remote 198.51.100.10 443
                ca /sdcard/attacker-ca.pem
                """.trimIndent()
            )
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun requiresTrustAnchorOrPeerFingerprint() {
        var rejected = false
        try {
            OpenVpnProfileSanitizer.sanitize(
                """
                client
                remote 198.51.100.10 443
                """.trimIndent()
            )
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private fun validProfile(extra: String = ""): String =
        """
        client
        dev tun
        proto tcp-client
        remote 198.51.100.10 443
        <ca>
        -----BEGIN CERTIFICATE-----
        TEST-CERTIFICATE-DATA
        -----END CERTIFICATE-----
        </ca>
        $extra
        """.trimIndent()
}
