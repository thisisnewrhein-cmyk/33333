package com.routescout.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RouteScoutColors = darkColorScheme(
    primary = RouteCoral,
    secondary = RouteCyan,
    tertiary = RoutePurple,
    background = RouteBackground,
    surface = RouteSurface,
    onPrimary = RouteBackground,
    onSecondary = RouteBackground,
    onBackground = RouteText,
    onSurface = RouteText
)

@Composable
fun RouteScoutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RouteScoutColors,
        typography = Typography(),
        content = content
    )
}
