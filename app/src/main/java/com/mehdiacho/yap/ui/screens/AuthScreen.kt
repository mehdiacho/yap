package com.mehdiacho.yap.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.data.AuthManager
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
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
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = edge)) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = cs.onSurface) }
            }

            Spacer(Modifier.height(6.dp))
            Text("Sign in", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                letterSpacing = (-0.4).sp, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Text("Your notes and recordings back up to your account and sync across devices. " +
                "Everything you've captured as a guest carries over.",
                fontSize = 14.sp, color = cs.onSurfaceVariant, lineHeight = 20.sp)

            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = { PeekabooToggle(showPassword) { showPassword = !showPassword } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    run {
                        val r = AuthManager.signInWithEmail(email.trim(), password)
                        if (!r.existingAccount) {
                            runCatching { AuthManager.sendEmailVerification() }
                            android.widget.Toast.makeText(context,
                                "Verification email sent to ${email.trim()}",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !busy && email.isNotBlank() && password.length >= 6,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text("Continue with email", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Text("New emails create an account automatically.", fontSize = 12.sp,
                color = YapTheme.extended.subdued, modifier = Modifier.padding(top = 6.dp))
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
            ) { Text("Forgot password?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }

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
