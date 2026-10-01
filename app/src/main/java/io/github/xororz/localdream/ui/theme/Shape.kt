package io.github.xororz.localdream.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// MD3 Expressive bumps corner radii up across the board for a softer, more lively feel.
// extraSmall -> chip/snackbar, small -> text field/menu, medium -> card,
// large -> FAB/nav drawer, extraLarge -> dialog/bottom sheet.
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
