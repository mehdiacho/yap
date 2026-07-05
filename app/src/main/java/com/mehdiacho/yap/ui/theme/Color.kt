package com.mehdiacho.yap.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// Yap Material 3 palette — seed indigo #5B5BD6, coral #FF6B6B recording accent.
// Derived from the approved design kit brand sheet.
// ============================================================

// ---- Light scheme ----
val md_theme_light_primary = Color(0xFF5B5BD6)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFE4E0FF)
val md_theme_light_onPrimaryContainer = Color(0xFF3A37A0) // spec §1.1
val md_theme_light_secondary = Color(0xFF5D5C72)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFE3E0F9)
val md_theme_light_onSecondaryContainer = Color(0xFF191A2C)
val md_theme_light_tertiary = Color(0xFF2E6B4F)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFB8F0CF)
val md_theme_light_onTertiaryContainer = Color(0xFF002111)
val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onErrorContainer = Color(0xFF410002)
val md_theme_light_background = Color(0xFFFCFBFF)
val md_theme_light_onBackground = Color(0xFF1B1B21)
val md_theme_light_surface = Color(0xFFFCFBFF)
val md_theme_light_onSurface = Color(0xFF1B1B21)
val md_theme_light_surfaceVariant = Color(0xFFE5E1EC)
val md_theme_light_onSurfaceVariant = Color(0xFF47464F)
val md_theme_light_outline = Color(0xFF77767F)
val md_theme_light_outlineVariant = Color(0xFFC8C5D0)
val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_theme_light_surfaceContainerLow = Color(0xFFF7F5FC)
val md_theme_light_surfaceContainer = Color(0xFFF2EFF7)
val md_theme_light_surfaceContainerHigh = Color(0xFFEDEBF3) // spec §1.1
val md_theme_light_surfaceContainerHighest = Color(0xFFE6E3ED)

// ---- Dark scheme ----
val md_theme_dark_primary = Color(0xFFC5C0FF)
val md_theme_dark_onPrimary = Color(0xFF2B2A60)
val md_theme_dark_primaryContainer = Color(0xFF4341A8)
val md_theme_dark_onPrimaryContainer = Color(0xFFE4E0FF)
val md_theme_dark_secondary = Color(0xFFC6C4DD)
val md_theme_dark_onSecondary = Color(0xFF2F2F42)
val md_theme_dark_secondaryContainer = Color(0xFF454559)
val md_theme_dark_onSecondaryContainer = Color(0xFFE3E0F9)
val md_theme_dark_tertiary = Color(0xFF9CD5B3)
val md_theme_dark_onTertiary = Color(0xFF00391F)
val md_theme_dark_tertiaryContainer = Color(0xFF12522F)
val md_theme_dark_onTertiaryContainer = Color(0xFFB8F0CF)
val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_theme_dark_background = Color(0xFF131318)
val md_theme_dark_onBackground = Color(0xFFE5E1E9)
val md_theme_dark_surface = Color(0xFF131318)
val md_theme_dark_onSurface = Color(0xFFE5E1E9)
val md_theme_dark_surfaceVariant = Color(0xFF48464F)
val md_theme_dark_onSurfaceVariant = Color(0xFFA5A2AD) // spec §1.2
val md_theme_dark_outline = Color(0xFF918F9A)
val md_theme_dark_outlineVariant = Color(0xFF48464F)
val md_theme_dark_surfaceContainerLowest = Color(0xFF0E0D12)
val md_theme_dark_surfaceContainerLow = Color(0xFF1B1B21)
val md_theme_dark_surfaceContainer = Color(0xFF1F1E25)
val md_theme_dark_surfaceContainerHigh = Color(0xFF2A2931)
val md_theme_dark_surfaceContainerHighest = Color(0xFF35343C)

// ============================================================
// Brand-extended semantic colors (not part of M3 ColorScheme).
// Exposed via YapTheme.extended — see Theme.kt.
// ============================================================

// Recording (coral) — used ONLY for the active-recording state.
val RecordingLight = Color(0xFFFF6B6B)
val OnRecordingLight = Color(0xFFFFFFFF)
val RecordingDark = Color(0xFFFF8A8A)
val OnRecordingDark = Color(0xFF5C1414)

// Refined (green) — the "Refined" status.
val RefinedLight = Color(0xFF2E6B4F)
val RefinedContainerLight = Color(0xFFB8F0CF)
val OnRefinedContainerLight = Color(0xFF0F5132)
val RefinedDark = Color(0xFF9CD5B3)
val RefinedContainerDark = Color(0xFF12522F)
val OnRefinedContainerDark = Color(0xFFB8F0CF)

// Offline / pending (amber).
val OfflineLight = Color(0xFFB26A00)
val OfflineContainerLight = Color(0xFFFFDDB0)
val OnOfflineContainerLight = Color(0xFF7A4E00)
val OfflineDark = Color(0xFFF5BE6B)
val OfflineContainerDark = Color(0xFF5A3D00)
val OnOfflineContainerDark = Color(0xFFFFDDB0)

// Subdued — timestamps, eyebrow labels, placeholders (spec §1.1 / §1.2).
val SubduedLight = Color(0xFF8B8896)
val SubduedDark = Color(0xFF918F9A)
// Success soft — safe-state confirmations, "Synced" pill.
val SuccessSoftLight = Color(0xFFEAF6EE)
val SuccessSoftDark = Color(0xFF163A29)
// Draft neutral chip.
val DraftNeutralLight = Color(0xFFE7E5EC)
val DraftNeutralDark = Color(0xFF2A2931)
// Warning soft — offline / draft-not-refined banners.
val WarningSoftLight = Color(0xFFFFF4E0)
val WarningSoftDark = Color(0xFF3A2600)

// Legacy aliases (kept so older references still compile).
val RecordingColorLight = RecordingLight
val RecordingColorDark = RecordingDark
