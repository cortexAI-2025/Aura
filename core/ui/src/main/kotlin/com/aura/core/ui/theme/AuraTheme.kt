package com.aura.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AuraDarkColorScheme = darkColorScheme(
    primary = AuraPulse,
    onPrimary = AuraOnSurface,
    primaryContainer = AuraPulseVariant,
    onPrimaryContainer = AuraGlowSoft,
    secondary = AuraGlow,
    onSecondary = AuraVoid,
    secondaryContainer = AuraSurfaceVariant,
    onSecondaryContainer = AuraGlowSoft,
    background = AuraVoid,
    onBackground = AuraOnSurface,
    surface = AuraSurface,
    onSurface = AuraOnSurface,
    surfaceVariant = AuraSurfaceVariant,
    onSurfaceVariant = AuraOnSurfaceDim,
    error = AuraError,
    outline = AuraOnSurfaceDim,
)

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AuraDarkColorScheme,
        typography = AuraTypography,
        content = content,
    )
}
