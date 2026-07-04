package com.mehdiacho.yap.navigation

object Routes {
    const val CAPTURE = "capture"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val AUTH = "auth"
    const val SUBSCRIPTION = "subscription"
    const val TRASH = "trash"
    const val SPIKE = "spike" // temporary: on-device transcription feasibility spike
    const val SESSION_DETAIL = "session_detail/{sessionId}"
    
    fun createSessionDetailRoute(sessionId: String): String {
        return "session_detail/$sessionId"
    }
}
