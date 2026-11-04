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
        // A valid SHA-256 peer pin is itself an accepted identity check.
        assertFalse(sanitized.contains("remote-cert-tls server"))
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
        MIIDKzCCAhOgAwIBAgIUaxsG5uJMvP4ypgrNllWf8pwAAvQwDQYJKoZIhvcNAQEL
        BQAwHTEbMBkGA1UEAwwSUm91dGVTY291dCBUZXN0IENBMB4XDTI2MTEwNDEwNDEw
        MFoXDTM2MTEwMTEwNDEwMFowHTEbMBkGA1UEAwwSUm91dGVTY291dCBUZXN0IENB
        MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArjtSz9y1MNyQiJujg+4l
        Q5dfz12apmYENC8YLKEmLwxrPj1/pEfK3u4nsayFgMurpDXdBNoECR9XPugaA2Rm
        9/XtLGI1BtBGxOcLr+9nTEqfQN4ziGW7mZPknXTFaCIOuplB37sKQXQzx/i1bZPg
        Fk2o4aRLl76j6GT0piL8PWt2OwFWIq6RJFkVu5w0ZbSfyEIRNeC7cVFNbX7qbiJa
        6ANDZ+vry4/qZK+dGGzJukOwSwZxh/zI0saH/QgL52EEjhX+JlbpC9tlL5mlUFIf
        93Ieff1KuzqXfqakqkcLCZkkuU7hCDYvrY8Rns3ThXOafwAkWpmOUQxo2rO0yXsr
        +QIDAQABo2MwYTAdBgNVHQ4EFgQUlS7DsJuyzCFCUsSDD1qtSDNtSjAwHwYDVR0j
        BBgwFoAUlS7DsJuyzCFCUsSDD1qtSDNtSjAwDwYDVR0TAQH/BAUwAwEB/zAOBgNV
        HQ8BAf8EBAMCAQYwDQYJKoZIhvcNAQELBQADggEBAKin+k8qW/3xWE3HwMuthHio
        4n5sTVE27aOaE1UmnSNtmqOkhcikbl/Zbxw4mFvS9/9L39psOhFVCVmwgCvvOuaT
        2pzue/8ypjrcuBNi7ZnaL1RDHRH8Mie+co5LINC0VE5AXswHPtQcvli3s5N7kioX
        OZwzexC1YUD0escsnZZR/sr8J9D5zvmDtFll06OcINr173XQj4iVRwTz8OHHFQH0
        DiDfoQ0HcmybZ1X6VxZXhTgCYKeMo/JUcNqCgrH+zF08t5sHN0dyQsCHBsczKFDE
        yDJ4415dXN906qd+VoNqdZFl4iEHfgnZY5x4ww0/ea5C2ivEz/wsvrwVCJzR8Q4=
        -----END CERTIFICATE-----
        </ca>
        $extra
        """.trimIndent()
}
