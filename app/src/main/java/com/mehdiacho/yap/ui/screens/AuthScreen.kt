package com.mehdiacho.yap.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.data.AuthManager
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    // login = returning user (no policy, no checklist); !login = new account (policy + checklist).
    var login by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true; error = null
            try { block(); onBack() } catch (e: Exception) {
                error = e.message ?: "Sign-in failed"
            } finally { busy = false }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val edge = (maxWidth.value * 0.061f).dp.coerceIn(20.dp, 28.dp)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = edge),
        ) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = cs.onSurface) }
            }

            Spacer(Modifier.height(6.dp))
            Text(if (login) "Sign in" else "Create account", fontFamily = Outfit, fontWeight = FontWeight.Bold,
                fontSize = 24.sp, letterSpacing = (-0.4).sp, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                if (login) "Your notes and recordings back up to your account and sync across devices. " +
                    "Everything you've captured as a guest carries over."
                else "Set up an account so your notes sync across devices. Your guest notes carry over.",
                fontSize = 14.sp, color = cs.onSurfaceVariant, lineHeight = 20.sp)

            Spacer(Modifier.height(24.dp))
            // Asked at signup, not later. It's the difference between the app greeting you
            // by name from the first launch and greeting you by your email prefix forever,
            // because a "complete your profile" prompt is one nobody comes back to.
            if (!login) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Your name") },
                    singleLine = true, shape = YapShapes.card,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
            }
            OutlinedTextField(
                value = email, onValueChange = { email = it; error = null }, label = { Text("Email") }, singleLine = true,
                isError = error != null, shape = YapShapes.card,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it; error = null }, label = { Text("Password") },
                singleLine = true, isError = error != null, shape = YapShapes.card,
                visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = { PeekabooToggle(showPassword) { showPassword = !showPassword } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            // Requirements shown from the first keystroke, in create-account mode only.
            if (!login) PasswordChecklist(password)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    run {
                        val r = AuthManager.signInWithEmail(email.trim(), password)
                        if (!login && !r.existingAccount) {
                            // Best-effort: the account exists and the user is in. Failing to
                            // record the name must not turn a successful signup into an error
                            // screen — the profile screen can set it later.
                            runCatching { com.mehdiacho.yap.data.ProfileClient.setName(name.trim()) }
                            runCatching { AuthManager.sendEmailVerification() }
                            android.widget.Toast.makeText(context,
                                "Verification email sent to ${email.trim()}",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !busy && email.isNotBlank() &&
                    (if (login) password.length >= 6
                     else name.isNotBlank() && passwordMeetsPolicy(password)),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text(if (login) "Sign in" else "Create account", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }

            // Mode switch — turns the same page into sign-in or sign-up so the checklist and
            // button label always match intent (fixes the grayed-out-with-no-hint confusion).
            TextButton(onClick = { login = !login; error = null },
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (login) "New here? Create an account" else "Already have an account? Sign in",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (login) {
                TextButton(
                    enabled = email.isNotBlank() && !busy,
                    onClick = {
                        scope.launch {
                            try {
                                AuthManager.sendPasswordReset(email.trim())
                                error = null
                                android.widget.Toast.makeText(context,
                                    "Reset link sent to ${email.trim()}", android.widget.Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                error = e.message ?: "Couldn't send the reset email."
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Forgot password?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }

            if (busy) {
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = cs.error, fontSize = 13.sp)
            }

            // Docked-bottom: Google (animated gradient) above the guest link (§3.10).
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f), color = cs.outlineVariant)
                Text("  or  ", color = YapTheme.extended.subdued, fontSize = 13.sp)
                HorizontalDivider(Modifier.weight(1f), color = cs.outlineVariant)
            }
            Spacer(Modifier.height(14.dp))
            GoogleGradientButton(busy = busy) { run { AuthManager.signInWithGoogle(context) } }
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp)) {
                Text("Continue as guest", fontSize = 14.sp, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
