package com.mehdiacho.yap.navigation

object Routes {
    const val ONBOARDING = "onboarding"
    const val CAPTURE = "capture"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val AUTH = "auth"
    const val PROFILE = "profile"
    const val INSIGHTS = "insights"
    const val SUBSCRIPTION = "subscription"
    const val PLANS = "subscription/plans"
    const val TRASH = "trash"
    const val SPIKE = "spike" // temporary: on-device transcription feasibility spike
    const val SESSION_DETAIL = "session_detail/{sessionId}"

    // Monetization (design section 10)
    const val BYOK_KEY = "byok/key"
    const val BYOK_UNLOCK = "byok/unlock"

    // Settings sub-screens (design section 09)
    const val SETTINGS_CAPTURE = "settings/capture"
    const val SETTINGS_PRIVACY = "settings/privacy"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_TRANSCRIPTION = "settings/transcription"
    const val SETTINGS_ABOUT = "settings/about"
    const val SETTINGS_FEEDBACK = "settings/feedback"
    const val SETTINGS_RESCUE = "settings/rescue"
    
    fun createSessionDetailRoute(sessionId: String): String {
        return "session_detail/$sessionId"
    }
}
