package com.mehdiacho.yap.data

import android.content.Context
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Turns an invite link into an actual attributed signup.
 *
 * ## Why the link is a Play Store URL
 *
 * The invite used to read `yap.app/r/CODE` — a domain nobody owns, pointing at nothing. Making
 * it real would mean buying a domain, hosting `assetlinks.json` for App Links, and handling the
 * case the link is opened on a phone without the app, which is the *only* case that matters for
 * a referral.
 *
 * Google Play already solves exactly this. A Play listing URL carries a `referrer` parameter
 * through the store, through the install, and hands it to the app on first launch — so the
 * friend taps the link, lands on the listing, installs, opens, and the code is already there.
 * No domain, no web page, and it works on the path a referral actually takes.
 *
 * ## Read once, ever
 *
 * The Play referrer does not expire, so a bug that re-read it would re-apply the same code
 * every launch. The server rejects a second attempt, but relying on someone else's error to
 * enforce our own once-only rule is how duplicate credit happens. The flag is local and
 * permanent.
 */
object InstallReferral {

    private const val TAG = "YapReferral"
    private const val PREFS = "yap_install_referrer"
    private const val KEY_CHECKED = "checked"

    /** Always the release listing: a tester on a dev build must not share a dead link. */
    private const val PLAY_ID = "app.greenforrest.yap"

    /** The invite link for [code]. */
    fun inviteLink(code: String): String =
        "https://play.google.com/store/apps/details?id=$PLAY_ID&referrer=" +
            java.net.URLEncoder.encode("ref=$code", "UTF-8")

    /** Shown in the UI instead of the full URL, which is too long to read as a link. */
    fun inviteLabel(code: String): String = "play.google.com/…  ·  code $code"

    /**
     * Checks the Play install referrer and applies any code in it. Safe to call on every
     * launch; it does nothing after the first.
     */
    fun applyIfPresent(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CHECKED, false)) return

        val client = InstallReferrerClient.newBuilder(context.applicationContext).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                // Mark checked regardless of outcome. FEATURE_NOT_SUPPORTED and
                // SERVICE_UNAVAILABLE mean this device will never give us one, and retrying
                // forever would bind to the Play service on every cold start for nothing.
                prefs.edit().putBoolean(KEY_CHECKED, true).apply()
                if (responseCode != InstallReferrerClient.InstallReferrerResponse.OK) {
                    Log.d(TAG, "install referrer unavailable: $responseCode")
                    runCatching { client.endConnection() }
                    return
                }
                val raw = runCatching { client.installReferrer.installReferrer }.getOrNull()
                runCatching { client.endConnection() }
                val code = parseCode(raw) ?: return
                Log.i(TAG, "applying referral code from install referrer")
                CoroutineScope(Dispatchers.IO).launch {
                    // Best-effort and silent. The friend never asked for this and shouldn't
                    // see an error about it; the server's own guards (self-referral, account
                    // age, already-applied) are the authority on whether it counts.
                    runCatching { AccountClient.applyReferral(code) }
                        .onFailure { Log.d(TAG, "referral not applied: ${it.message}") }
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                // Nothing to do: we take one shot at this, and the flag is already set.
            }
        })
    }

    /**
     * Pulls `ref=CODE` out of a referrer string.
     *
     * The value arrives as a query string that may or may not be URL-encoded, and organic
     * installs put Play's own `utm_*` tracking in the same field — so this looks for our key
     * specifically rather than assuming the whole string is ours.
     */
    fun parseCode(referrer: String?): String? {
        if (referrer.isNullOrBlank()) return null
        val decoded = runCatching { java.net.URLDecoder.decode(referrer, "UTF-8") }.getOrDefault(referrer)
        val code = decoded.split('&')
            .map { it.trim() }
            .firstOrNull { it.startsWith("ref=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.uppercase()
            ?: return null
        // The server issues 6 characters from a fixed alphabet. Anything else is either Play's
        // own tracking that happens to contain "ref=" or someone editing the URL by hand.
        return code.takeIf { it.length in 4..12 && it.all { c -> c.isLetterOrDigit() } }
    }
}
