package com.mehdiacho.yap.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import com.mehdiacho.yap.data.AuthManager
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- password policy

/** One signup password rule: label + predicate. */
data class PasswordRule(val label: String, val test: (String) -> Boolean)

val PasswordRules = listOf(
    PasswordRule("At least 6 characters") { it.length >= 6 },
    PasswordRule("One uppercase letter") { pw -> pw.any { it.isUpperCase() } },
    PasswordRule("One number") { pw -> pw.any { it.isDigit() } },
    PasswordRule("One symbol (!@#\$…)") { pw -> pw.any { !it.isLetterOrDigit() && !it.isWhitespace() } },
)

fun passwordMeetsPolicy(pw: String): Boolean = PasswordRules.all { it.test(pw) }

/**
 * Live requirements checklist — each rule flips to a green tick the moment the password
 * satisfies it, so the rules are visible BEFORE the first failed attempt.
 */
@Composable
fun PasswordChecklist(password: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(start = 6.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        PasswordRules.forEach { rule ->
            val met = rule.test(password)
            val tint by animateColorAsState(
                if (met) Color(0xFF34A853) else cs.outlineVariant, tween(220), label = "ruleTint")
            val textColor by animateColorAsState(
                if (met) cs.onSurface else cs.onSurfaceVariant, tween(220), label = "ruleText")
            val pop by animateFloatAsState(
                if (met) 1f else 0.82f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "rulePop")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(16.dp).scale(pop).clip(CircleShape)
                        .background(if (met) tint else Color.Transparent)
                        .let { if (met) it else it.background(cs.surfaceContainerHigh) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (met) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(rule.label, fontSize = 12.sp, color = textColor,
                    fontWeight = if (met) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

// ---------------------------------------------------------------- visibility toggle

/**
 * Show/hide toggle for a password field.
 *
 * Was a see-no-evil monkey emoji that spun 360° on tap. Emoji standing in for an icon is one
 * of the loudest tells that a UI was generated rather than drawn — the glyph renders at a
 * different weight and baseline from every real icon around it, and it can't take a tint, so
 * it ignores the theme entirely. The spin was decoration on top: motion that told the user
 * nothing the changed glyph didn't already say.
 */
@Composable
fun PeekabooToggle(visible: Boolean, onToggle: () -> Unit) {
    Icon(
        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
        if (visible) "Hide password" else "Show password",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onToggle() }
            .padding(8.dp)
            .size(21.dp),
    )
}

// ---------------------------------------------------------------- Google button

private val GoogleBlue = Color(0xFF4285F4)
private val GoogleRed = Color(0xFFEA4335)
private val GoogleYellow = Color(0xFFFBBC05)
private val GoogleGreen = Color(0xFF34A853)

/**
 * "Continue with Google" with a slowly drifting Google-colors gradient (Gemini-live vibe).
 * White logo chip + bold white label keep the text clearly legible over the moving colors.
 */
@Composable
fun GoogleGradientButton(
    text: String = "Continue with Google",
    busy: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = rememberInfiniteTransition(label = "gg")
    // River flow: the gradient tile drifts steadily leftward by exactly one period, then loops.
    // TileMode.Repeated tiles the palette infinitely so colors keep entering from the right and
    // exiting left; first == last color makes the wrap seamless (no bounce, no solid-color edge).
    val shift by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(7200, easing = LinearEasing)), label = "ggShift")
    val colors = listOf(GoogleBlue, GoogleRed, GoogleYellow, GoogleGreen, GoogleBlue)
    val span = 900f
    val off = -shift * span
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(50))
            .background(
                Brush.linearGradient(
                    colors,
                    start = Offset(off, 0f),
                    end = Offset(off + span, 0f),
                    tileMode = androidx.compose.ui.graphics.TileMode.Repeated,
                )
            )
            .clickable(enabled = !busy) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = GoogleBlue)
                else Text("G", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GoogleBlue)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (busy) "Signing you in…" else text,
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
        }
    }
}

// ---------------------------------------------------------------- forgot password

/**
 * "Forgot password?", with the guard it was missing.
 *
 * Both sign-in screens called `sendPasswordReset` straight out of `onClick` with nothing
 * disabling the button — the `busy` flag they passed is only set by the sign-in path, never by
 * this one. So every tap sent an email, and because a `TextButton` accepts a second tap while
 * the first request is still in the air, an ordinary double-tap sent two. Firebase's own
 * throttling doesn't catch this: it happily emits several before it starts rejecting, and when
 * it does reject, the message it returns is not one you'd want to show anybody.
 *
 * Three things stop it now. The button disables the moment a send starts, so a request in
 * flight can't be duplicated. It stays disabled for a cooldown afterwards, so leaning on it
 * doesn't queue a run of identical emails. And it says the link has been sent, which is the
 * actual reason people tap twice — nothing on screen had confirmed the first one.
 */
@Composable
fun ForgotPasswordButton(
    email: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var sentAt by remember { mutableStateOf(0L) }

    // Ticks once a second only while a cooldown is running, so the label can count down
    // without the screen recomposing forever afterwards.
    var now by remember { mutableStateOf(0L) }
    LaunchedEffect(sentAt) {
        if (sentAt == 0L) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (now - sentAt >= RESET_COOLDOWN_MS) break
            kotlinx.coroutines.delay(1000)
        }
    }
    val remaining = ((RESET_COOLDOWN_MS - (now - sentAt)) / 1000).coerceAtLeast(0)
    val cooling = sentAt != 0L && remaining > 0

    TextButton(
        enabled = email.isNotBlank() && !sending && !cooling,
        onClick = {
            sending = true
            scope.launch {
                try {
                    AuthManager.sendPasswordReset(email.trim())
                    sentAt = System.currentTimeMillis()
                    now = sentAt
                    android.widget.Toast.makeText(
                        context, "Reset link sent to ${email.trim()}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } catch (e: Exception) {
                    onError(e.message ?: "Couldn't send the reset email.")
                } finally { sending = false }
            }
        },
        modifier = modifier,
    ) {
        Text(
            when {
                sending -> "Sending…"
                cooling -> "Link sent · ${remaining}s"
                else -> "Forgot password?"
            },
            fontSize = fontSize, fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Long enough that a frustrated double-tap can't become two emails, short enough to retry. */
private const val RESET_COOLDOWN_MS = 60_000L
