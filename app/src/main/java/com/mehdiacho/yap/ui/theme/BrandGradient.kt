package com.mehdiacho.yap.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * The brand gradient: the drifting Google-colours sweep from the sign-in button, promoted to
 * the app's primary accent in place of the flat indigo.
 *
 * ## One clock, not twenty
 *
 * Every animated surface reads its phase from a SINGLE [rememberInfiniteTransition] installed
 * once at the app root. Giving each button its own transition would mean a dozen independent
 * animation clocks waking the compositor on their own schedules — and because they would
 * start whenever their screen composed, the nav bar and the record button would visibly drift
 * out of phase with each other. Shared phase is both cheaper and the only way the app reads
 * as one moving surface rather than several unrelated ones.
 *
 * ## It degrades to a still gradient
 *
 * When the system animator duration scale is 0 — "Remove animations" in accessibility
 * settings, or battery saver on many devices — the phase is pinned and every surface renders
 * a static gradient. Motion is decoration here; nothing depends on it.
 */
object BrandGradient {

    // Google's four, in the order the sign-in button uses them. First == last so the sweep
    // wraps seamlessly under TileMode.Repeated: no bounce, no flat seam.
    private val Blue = Color(0xFF4285F4)
    private val Red = Color(0xFFEA4335)
    private val Yellow = Color(0xFFFBBC05)
    private val Green = Color(0xFF34A853)

    val colors = listOf(Blue, Red, Yellow, Green, Blue)

    /**
     * Colours for surfaces carrying small white text or thin glyphs.
     *
     * Google yellow against white fails contrast badly at label sizes, and a moving gradient
     * means it WILL pass under the text eventually — a control that becomes unreadable once
     * every seven seconds is worse than a flat one. This variant swaps in Google's darker
     * amber and deepens the yellow-adjacent stops, keeping the identity while staying legible
     * through the whole cycle.
     */
    val onDarkTextColors = listOf(Blue, Red, Color(0xFFE8A100), Green, Blue)

    /** One full drift takes this long. Slow enough to read as ambient, not as a spinner. */
    const val PERIOD_MS = 7200

    /** Width in px of one palette repeat. Larger than any control, so each shows a slice. */
    private const val SPAN = 900f
}

/** Current phase of the shared drift, 0f..1f. */
val LocalBrandPhase: ProvidableCompositionLocal<State<Float>> =
    compositionLocalOf { mutableFloatStateOf(0f) }

/**
 * Installs the single shared animation clock. Wrap the app content once, inside [YapTheme].
 */
@Composable
fun ProvideBrandPhase(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Respect "Remove animations". Read once per composition of the root — this does not
    // change while the app is foregrounded in any way worth recomposing for.
    val animationsOn = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }

    val phase = if (animationsOn) {
        val transition = rememberInfiniteTransition(label = "brand")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(BrandGradient.PERIOD_MS, easing = LinearEasing)
            ),
            label = "brandPhase",
        )
    } else {
        remember { mutableFloatStateOf(0.12f) } // a pleasant static slice, not a hard edge
    }

    CompositionLocalProvider(LocalBrandPhase provides phase, content = content)
}

/** Golden-ratio conjugate — successive seeds land far apart instead of clustering. */
private const val PHI = 0.6180339887f

private fun frac(x: Float) = x - kotlin.math.floor(x)

/** Stable per-site phase offset in 0f..1f, well spread for any set of seeds. */
private fun phaseOffsetFor(seed: Int) = frac((seed + 1) * PHI)

/**
 * Stable per-site gradient angle, -38°..+38°.
 *
 * A second low-discrepancy sequence (PHI squared) so angle and phase don't correlate — if
 * they did, every element sharing a colour would also share a direction and the desync would
 * only be half-convincing. The range is deliberately narrow: enough that the sweeps visibly
 * run in different directions, not so much that the app looks like unrelated widgets.
 */
private fun angleFor(seed: Int) = (frac((seed + 1) * PHI * PHI) - 0.5f) * 76f

/**
 * The brand brush at the current shared phase.
 *
 * @param forSmallText use the legibility-tuned palette when white text or thin icons sit on
 *   top; see [BrandGradient.onDarkTextColors].
 * @param seed identifies this call site. One clock keeps the cost down, but every surface
 *   rendering the *same colour at the same instant* looks uncanny — synthetic in a way a
 *   single gradient never does. The seed shifts this site's phase and angle deterministically,
 *   so elements stay individually stable across recomposition while never matching each
 *   other. Give each visually distinct component its own number; reuse one for repeated
 *   instances of the same thing (every category pill can share a seed).
 */
@Composable
fun brandBrush(forSmallText: Boolean = false, seed: Int = 0): Brush {
    val phase by LocalBrandPhase.current
    val span = 900f
    // Offsets, not speeds: the drift must stay seamless at the wrap, which only holds when
    // every surface advances one whole palette period per cycle. Varying the rate instead
    // would put a visible jump in the faster ones each time the shared clock resets.
    val advance = frac(phase + phaseOffsetFor(seed))
    val radians = Math.toRadians(angleFor(seed).toDouble())
    val dx = kotlin.math.cos(radians).toFloat()
    val dy = kotlin.math.sin(radians).toFloat()
    val from = -advance * span
    return Brush.linearGradient(
        colors = if (forSmallText) BrandGradient.onDarkTextColors else BrandGradient.colors,
        start = Offset(from * dx, from * dy),
        end = Offset((from + span) * dx, (from + span) * dy),
        tileMode = TileMode.Repeated,
    )
}

/**
 * Brush for gradient TEXT, sized to the text itself.
 *
 * Text is measured after this brush is built, so a screen-space brush would give a long
 * heading and a short label wildly different slices — one looks blue, the next looks green,
 * and they stop reading as the same accent. Scaling the span down keeps a recognisable sweep
 * inside a run of a few words.
 */
@Composable
fun brandTextBrush(seed: Int = 0): Brush {
    val phase by LocalBrandPhase.current
    // Wide enough that a short label shows a gentle one-or-two colour sweep. At ~260f a
    // three-word phrase fitted more than a full palette and every word came out a different
    // colour — it read as rainbow novelty text rather than as a brand accent.
    val span = 900f
    val from = -frac(phase + phaseOffsetFor(seed)) * span
    return Brush.linearGradient(
        colors = BrandGradient.colors,
        start = Offset(from, 0f),
        end = Offset(from + span, 0f),
        tileMode = TileMode.Repeated,
    )
}

/**
 * Paint an icon (or any single-colour content) with the brand gradient.
 *
 * `Icon`'s tint takes a Color, not a Brush, so the gradient is composited over the drawn
 * glyph with [BlendMode.SrcAtop] — the gradient shows only where the glyph already painted,
 * leaving its alpha and antialiased edges intact. Requires an offscreen layer, hence
 * `graphicsLayer { compositingStrategy = Offscreen }` at the call site via [brandTint].
 */
@Composable
fun Modifier.brandTint(forSmallText: Boolean = true, seed: Int = 0): Modifier {
    val brush = brandBrush(forSmallText, seed)
    return this
        .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            drawRect(brush = brush, size = size, blendMode = BlendMode.SrcAtop)
        }
}
