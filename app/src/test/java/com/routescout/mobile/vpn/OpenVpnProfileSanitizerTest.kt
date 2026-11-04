package com.routescout.mobile.vpn

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenVpnProfileSanitizerTest {

    @Test
    fun addsServerCertificatePurposeCheckWithoutInventingAuthenticationPrompt() {
        val sanitized = OpenVpnProfileSanitizer.sanitize(validProfile())

        assertTrue(sanitized.contains("remote-cert-tls server"))
        assertTrue(sanitized.contains("<ca>"))
        assertTrue(sanitized.contains("</ca>"))
        assertFalse(sanitized.contains("auth-user-pass"))
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
        assertRejected(validProfile(extra = "remote-cert-tls client"))
    }

    @Test
    fun removesLocalScriptAndManagementDirectives() {
        val sanitized = OpenVpnProfileSanitizer.sanitize(
            validProfile(extra = "up /tmp/untrusted.sh\nmanagement 127.0.0.1 7505")
        )

        assertFalse(sanitized.contains("untrusted.sh"))
        assertFalse(sanitized.contains("management 127.0.0.1"))
        assertTrue(sanitized.contains("BEGIN CERTIFICATE"))
    }

    @Test
    fun rejectsExternalCertificatePaths() {
        assertRejected(
            """
            client
            remote 198.51.100.10 443
            ca /sdcard/attacker-ca.pem
            """.trimIndent()
        )
    }

    @Test
    fun requiresTrustAnchorOrPeerFingerprint() {
        assertRejected(
            """
            client
            remote 198.51.100.10 443
            """.trimIndent()
        )
    }

    @Test
    fun rejectsCaBlockWithoutPemCertificate() {
        assertRejected(
            """
            client
            remote 198.51.100.10 443
            <ca>
            not a certificate
            </ca>
            """.trimIndent()
        )
    }

    @Test
    fun acceptsAndPreservesValidInlineSha256PeerFingerprint() {
        val fingerprint = "00:".repeat(31) + "00"
        val sanitized = OpenVpnProfileSanitizer.sanitize(
            """
            client
            remote 198.51.100.10 443
            <peer-fingerprint>
            $fingerprint
            </peer-fingerprint>
            """.trimIndent()
        )

        assertTrue(sanitized.contains("<peer-fingerprint>"))
        assertTrue(sanitized.contains("</peer-fingerprint>"))
        assertTrue(sanitized.contains(fingerprint))
        assertTrue(sanitized.contains("remote-cert-tls server"))
    }

    @Test
    fun rejectsMalformedPeerFingerprint() {
        assertRejected(
            """
            client
            remote 198.51.100.10 443
            peer-fingerprint AA:BB:CC
            """.trimIndent()
        )
    }

    @Test
    fun stripsEmbeddedAuthCredentialsButKeepsExplicitPrompt() {
        val sanitized = OpenVpnProfileSanitizer.sanitize(
            validProfile(
                extra = """
                <auth-user-pass>
                private-user
                private-password
                </auth-user-pass>
                """.trimIndent()
            )
        )

        assertTrue(sanitized.contains("auth-user-pass"))
        assertFalse(sanitized.contains("private-user"))
        assertFalse(sanitized.contains("private-password"))
    }

    @Test
    fun removesLocalAuthFilePathFromExplicitAuthDirective() {
        val sanitized = OpenVpnProfileSanitizer.sanitize(
            validProfile(extra = "auth-user-pass /sdcard/credentials.txt")
        )

        assertTrue(sanitized.contains("auth-user-pass"))
        assertFalse(sanitized.contains("credentials.txt"))
    }

    @Test
    fun rejectsUnsupportedProxyProfilesInsteadOfSilentlyChangingThem() {
        assertRejected(validProfile(extra = "http-proxy proxy.example 8080"))
    }

    @Test
    fun rejectsOversizedProfiles() {
        assertRejected(validProfile() + "x".repeat(300 * 1024))
    }

    private fun assertRejected(profile: String) {
        var rejected = false
        try {
            OpenVpnProfileSanitizer.sanitize(profile)
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue("Expected profile to be rejected", rejected)
    }

    private fun validProfile(extra: String = ""): String =
        """
        client
        dev tun
        proto tcp-client
        remote 198.51.100.10 443
        <ca>
        -----BEGIN CERTIFICATE-----
        MIIB-test-certificate-payload
        -----END CERTIFICATE-----
        </ca>
        $extra
        """.trimIndent()
}
