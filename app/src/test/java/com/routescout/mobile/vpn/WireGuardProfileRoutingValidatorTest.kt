package com.routescout.mobile.vpn

import org.junit.Assert.assertThrows
import org.junit.Test

class WireGuardProfileRoutingValidatorTest {

    @Test
    fun allowsAllAppsWhenNoAppFilterIsConfigured() {
        WireGuardProfileRoutingValidator.validate(
            packageName = "com.routescout.mobile",
            includedApplications = emptyList(),
            excludedApplications = emptyList()
        )
    }

    @Test
    fun allowsRouteScoutWhenItIsExplicitlyIncluded() {
        WireGuardProfileRoutingValidator.validate(
            packageName = "com.routescout.mobile",
            includedApplications = listOf("com.routescout.mobile", "com.example.browser"),
            excludedApplications = emptyList()
        )
    }

    @Test
    fun rejectsRouteScoutWhenItIsExcluded() {
        assertThrows(IllegalArgumentException::class.java) {
            WireGuardProfileRoutingValidator.validate(
                packageName = "com.routescout.mobile",
                includedApplications = emptyList(),
                excludedApplications = listOf("com.routescout.mobile")
            )
        }
    }

    @Test
    fun rejectsRouteScoutWhenAllowListDoesNotContainIt() {
        assertThrows(IllegalArgumentException::class.java) {
            WireGuardProfileRoutingValidator.validate(
                packageName = "com.routescout.mobile",
                includedApplications = listOf("com.example.browser"),
                excludedApplications = emptyList()
            )
        }
    }

    @Test
    fun explicitExclusionTakesPrecedenceOverAllowList() {
        assertThrows(IllegalArgumentException::class.java) {
            WireGuardProfileRoutingValidator.validate(
                packageName = "com.routescout.mobile",
                includedApplications = listOf("com.routescout.mobile"),
                excludedApplications = listOf("com.routescout.mobile")
            )
        }
    }
}
