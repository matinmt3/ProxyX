package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

private val AppFontFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_bold, FontWeight.Bold)
)

private fun appText(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = AppFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = height.sp,
    letterSpacing = 0.sp
)

val Typography = Typography(
    displayLarge = appText(57, 64),
    displayMedium = appText(45, 52),
    displaySmall = appText(36, 44),
    headlineLarge = appText(32, 40, FontWeight.Bold),
    headlineMedium = appText(28, 36, FontWeight.Bold),
    headlineSmall = appText(24, 32, FontWeight.Bold),
    titleLarge = appText(22, 28, FontWeight.SemiBold),
    titleMedium = appText(16, 24, FontWeight.SemiBold),
    titleSmall = appText(14, 20, FontWeight.SemiBold),
    bodyLarge = appText(16, 24),
    bodyMedium = appText(14, 20),
    bodySmall = appText(12, 18),
    labelLarge = appText(14, 20, FontWeight.SemiBold),
    labelMedium = appText(12, 18, FontWeight.Medium),
    labelSmall = appText(11, 16, FontWeight.Medium)
)
