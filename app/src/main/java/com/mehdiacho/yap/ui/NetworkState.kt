package com.mehdiacho.yap.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Reactive online/offline flag via the default network callback, plus a manual re-check
 * (used by the offline badge's tap-to-refresh). Shared by Capture and Library.
 */
@Composable
fun rememberOfflineState(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(ConnectivityManager::class.java) }
    fun compute(): Boolean {
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true
    }
    var offline by remember { mutableStateOf(compute()) }
    DisposableEffect(Unit) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: android.net.Network) { offline = false }
            override fun onLost(n: android.net.Network) { offline = compute() }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return offline to { offline = compute() }
}
