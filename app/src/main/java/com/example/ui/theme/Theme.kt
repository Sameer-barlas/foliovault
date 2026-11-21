package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val FolioVaultLightColorScheme = lightColorScheme(
    primary = OxbloodPrimary,
    onPrimary = OxbloodOnPrimary,
    primaryContainer = OxbloodContainer,
    onPrimaryContainer = OxbloodOnContainer,
    secondary = BrassSecondary,
    onSecondary = BrassOnSecondary,
    secondaryContainer = BrassContainer,
    onSecondaryContainer = BrassOnContainer,
    tertiary = ForestSuccess,
    onTertiary = OxbloodOnPrimary,
    tertiaryContainer = ForestSuccessContainer,
    onTertiaryContainer = EspressoInk,
    background = ParchmentCanvas,
    onBackground = EspressoInk,
    surface = ParchmentCanvas,
    onSurface = EspressoInk,
    surfaceVariant = ParchmentCard,
    onSurfaceVariant = MutedText,
    surfaceContainerLowest = OxbloodOnPrimary,
    surfaceContainerLow = ParchmentCanvas,
    surfaceContainer = ParchmentCard,
    surfaceContainerHigh = ParchmentCardElevated,
    surfaceContainerHighest = ParchmentDivider,
    outline = ParchmentDivider,
    outlineVariant = ParchmentDivider,
    error = CrimsonAlert,
    onError = OxbloodOnPrimary,
    errorContainer = CrimsonAlertContainer,
    onErrorContainer = CrimsonAlert,
)

private val FolioVaultReaderDarkColorScheme = darkColorScheme(
    primary = OxbloodPrimary,
    onPrimary = OxbloodOnPrimary,
    secondary = BrassSecondary,
    onSecondary = OxbloodOnPrimary,
    background = ReaderSurroundDark,
    onBackground = ReaderInkLight,
    surface = ReaderSurfaceDark,
    onSurface = ReaderInkLight,
    surfaceVariant = ReaderSurfaceDark,
    onSurfaceVariant = ParchmentDivider,
    outline = MutedText,
    error = CrimsonAlert,
    onError = OxbloodOnPrimary,
)

val FolioVaultShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun FolioVaultTheme(
    readerDarkSurround: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (readerDarkSurround) {
        FolioVaultReaderDarkColorScheme
    } else {
        FolioVaultLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FolioVaultTypography,
        shapes = FolioVaultShapes,
        content = content,
    )
}
