package com.routescout.mobile.vpn

/**
 * Public setup information for Proton VPN's free plan.
 *
 * RouteScout deliberately sends users to Proton's official account portal to
 * generate a profile; it does not automate login or call undocumented endpoints.
 */
object ProtonFreeProvider {
    const val CONFIG_PORTAL_URL = "https://account.protonvpn.com/downloads"

    val freeCountries = listOf(
        "Netherlands",
        "Japan",
        "Romania",
        "Poland",
        "Norway",
        "Switzerland",
        "Singapore",
        "Mexico",
        "Canada",
        "United States"
    )
}
