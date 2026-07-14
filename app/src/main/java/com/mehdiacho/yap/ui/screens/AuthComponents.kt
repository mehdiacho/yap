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
 * Peek-a-boo password toggle: the see-no-evil monkey uncovers its eyes (with a springy
 * spin) when the password is shown. Pass as trailingIcon of the password field.
 */
@Composable
fun PeekabooToggle(visible: Boolean, onToggle: () -> Unit) {
    val spin by animateFloatAsState(
        if (visible) 360f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "peekSpin",
    )
    Text(
        if (visible) "🐵" else "🙈",
        fontSize = 20.sp,
        modifier = Modifier
            .graphicsLayer { rotationZ = spin }
            .clip(CircleShape)
            .clickable { onToggle() }
            .padding(8.dp),
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
