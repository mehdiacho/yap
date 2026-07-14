package com.mehdiacho.yap.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared chrome flag for the Capture screen. Several capture states in the design kit are
 * immersive (Recording, Paused, Processing, Quota, Permission-denied, Error) and render WITHOUT
 * the bottom navigation bar. CaptureScreen sets [immersive]; MainActivity observes it to hide the
 * nav bar on the Capture route. Idle and Offline are NOT immersive (nav stays).
 */
object CaptureChrome {
    val immersive = MutableStateFlow(false)
}

/**
 * Library multi-select replaces the bottom navigation with its own 4-action bar
 * (Group / Formalize / Pin / Delete). LibraryScreen sets [selectionActive]; MainActivity
 * hides the nav bar on the Library route while it is true.
 */
object LibraryChrome {
    val selectionActive = MutableStateFlow(false)
}
