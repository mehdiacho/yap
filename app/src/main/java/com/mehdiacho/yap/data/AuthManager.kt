package com.mehdiacho.yap.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.mehdiacho.yap.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sign-in that upgrades the anonymous guest in place: linking a Google/email credential
 * keeps the SAME uid, so all existing notes/audio carry over. On collision (credential
 * already belongs to an account) we sign into that account instead.
 */
object AuthManager {
    private val auth get() = FirebaseAuth.getInstance()

    /** [existingAccount] is true when the credential belonged to an already-registered account
     *  (a returning user), false when this sign-in created/linked a brand-new account. */
    data class SignInResult(val user: FirebaseUser, val existingAccount: Boolean)

    val currentUser: FirebaseUser? get() = auth.currentUser
    val isAnonymous: Boolean get() = auth.currentUser?.isAnonymous != false

    fun authState(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /** The user backed out of the Google sheet. Not a failure — nothing to report. */
    class SignInCancelled : Exception("cancelled")

    /**
     * Google sign-in via Credential Manager. Call with an Activity context.
     *
     * Two passes, because one is not enough. The first asks for a Google ID token from the
     * credentials the device already holds: one tap, no picker, and the right experience for
     * almost everyone. It fails with [NoCredentialException] when the device has nothing to
     * hand over — and "nothing to hand over" is not the same as "no Google account here".
     * Google Identity also reports it after the user dismisses the sheet (there is a
     * ~24-hour cool-down before it will offer again), while Play services is updating, and
     * on devices where the accounts present are filtered out.
     *
     * Answering that with a message was a dead end, and a tester hit exactly that on
     * 2026-09-08: "Continue with Google is unavailable, but I confirmed with other apps work
     * with that." So the second pass runs the explicit Sign in with Google flow, which shows
     * the account picker, offers to add an account, and is not subject to the same
     * filtering or cool-down. Only if that comes back empty too is there really nothing to
     * sign in with, and only then does the user see an error.
     */
    suspend fun signInWithGoogle(activityContext: Context): SignInResult {
        val cm = CredentialManager.create(activityContext)
        val serverClientId = activityContext.getString(R.string.default_web_client_id)

        suspend fun ask(option: CredentialOption): GetCredentialResponse =
            cm.getCredential(
                activityContext,
                GetCredentialRequest.Builder().addCredentialOption(option).build(),
            )

        val result = try {
            ask(
                GetGoogleIdOption.Builder()
                    .setServerClientId(serverClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            )
        } catch (cancelled: GetCredentialCancellationException) {
            throw SignInCancelled()
        } catch (first: GetCredentialException) {
            try {
                ask(GetSignInWithGoogleOption.Builder(serverClientId).build())
            } catch (cancelled: GetCredentialCancellationException) {
                throw SignInCancelled()
            } catch (none: NoCredentialException) {
                throw Exception(
                    "No Google account is available on this device. Add one in Settings → " +
                        "Passwords & accounts, or sign in with an email address instead."
                )
            } catch (second: GetCredentialException) {
                // Both flows refused. Name the type: it is the one thing that makes a bug
                // report about this actionable instead of "it says unavailable".
                throw Exception(
                    "Google sign-in couldn't start on this device (${second.type}). " +
                        "Try again in a minute, or sign in with an email address."
                )
            }
        }

        val credential = result.credential
        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw Exception("Google returned a credential Yap can't use. Try email sign-in.")
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        return linkOrSignIn(GoogleAuthProvider.getCredential(idToken, null))
    }

    /** Fires Firebase's verification email at the freshly created account (non-blocking UX). */
    suspend fun sendEmailVerification() {
        auth.currentUser?.let { awaitTask(it.sendEmailVerification()) }
    }

    /** Email/password — same call handles create-on-anonymous (link) and existing accounts. */
    suspend fun signInWithEmail(email: String, password: String): SignInResult =
        linkOrSignIn(EmailAuthProvider.getCredential(email, password))

    fun signOut() {
        // Drop the cached profile first. It is keyed to nothing — it is just "the last
        // profile we saw" — so leaving it means the guest who lands here a moment later
        // wears the previous account's name and picture until a fetch corrects it.
        ProfileClient.clearCache()
        PersonaClient.clearCache()
        // Signing out leaves nobody signed in, and it stays that way until the user records
        // or otherwise commits something — browsing an empty library does not mint a guest.
        auth.signOut()
    }

    /** Sends Firebase's password-reset email. Throws with a readable message on failure. */
    suspend fun sendPasswordReset(email: String) {
        awaitTask(auth.sendPasswordResetEmail(email))
    }

    private suspend fun linkOrSignIn(credential: AuthCredential): SignInResult {
        val user = auth.currentUser
        return try {
            if (user != null && user.isAnonymous) {
                // Linking upgrades the guest in place — by definition a brand-new account.
                SignInResult(awaitTask(user.linkWithCredential(credential)).user!!, existingAccount = false)
            } else {
                val result = awaitTask(auth.signInWithCredential(credential))
                SignInResult(result.user!!, existingAccount = result.additionalUserInfo?.isNewUser == false)
            }
        } catch (e: FirebaseAuthUserCollisionException) {
            // Credential already has an account — sign into it (guest data stays on the old uid).
            SignInResult(awaitTask(auth.signInWithCredential(credential)).user!!, existingAccount = true)
        }
    }

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
