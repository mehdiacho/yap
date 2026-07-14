package com.mehdiacho.yap.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
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

    /** Google sign-in via Credential Manager. Call with an Activity context. */
    suspend fun signInWithGoogle(activityContext: Context): SignInResult {
        val cm = CredentialManager.create(activityContext)
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(activityContext.getString(R.string.default_web_client_id))
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = cm.getCredential(activityContext, request)
        val idToken = GoogleIdTokenCredential.createFrom(result.credential.data).idToken
        return linkOrSignIn(GoogleAuthProvider.getCredential(idToken, null))
    }

    /** Email/password — same call handles create-on-anonymous (link) and existing accounts. */
    suspend fun signInWithEmail(email: String, password: String): SignInResult =
        linkOrSignIn(EmailAuthProvider.getCredential(email, password))

    fun signOut() = auth.signOut() // next ensureSignedIn() creates a fresh guest

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
