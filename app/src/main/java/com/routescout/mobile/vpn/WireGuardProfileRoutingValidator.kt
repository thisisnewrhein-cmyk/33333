package com.routescout.mobile.vpn

internal object WireGuardProfileRoutingValidator {
    fun validate(
        packageName: String,
        includedApplications: Collection<String>,
        excludedApplications: Collection<String>
    ) {
        require(packageName.isNotBlank()) { "Application package name is missing" }

        if (packageName in excludedApplications) {
            throw IllegalArgumentException(
                "RouteScout исключён из VPN-профиля. Удали приложение из списка исключений, чтобы проверить туннель."
            )
        }

        if (includedApplications.isNotEmpty() && packageName !in includedApplications) {
            throw IllegalArgumentException(
                "В VPN-профиле разрешены только отдельные приложения, но RouteScout не включён в этот список."
            )
        }
    }
}
