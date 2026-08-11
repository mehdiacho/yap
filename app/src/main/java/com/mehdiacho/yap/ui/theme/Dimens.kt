package com.mehdiacho.yap.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The geometry scale.
 *
 * Corner radii were being picked per call site — 3, 7, 9, 11, 13 and 26 all appeared once
 * each, next to a 14 and an 18 that meant the same thing. Nobody sees an individual value,
 * but the eye reads the *set*: arbitrary radii make a screen look assembled rather than
 * designed, and picking one value for everything (the other failure) looks templated. A short
 * scale with a role attached to each step is the thing a designer actually keeps in their head.
 *
 * Steps are spaced so adjacent ones are distinguishable at arm's length; anything finer would
 * be a distinction the screen can't render and the user can't see.
 */
object YapShapes {
    /** Checkboxes, progress rails, tiny inline marks. */
    val xs = RoundedCornerShape(4.dp)
    /** Swatches and small square tiles under ~24dp. */
    val sm = RoundedCornerShape(6.dp)
    /** Chips, segmented-control thumbs, icon tiles. */
    val md = RoundedCornerShape(10.dp)
    /** Rows and list items that sit inside a card. */
    val lg = RoundedCornerShape(12.dp)
    /** Cards — the default container radius. */
    val card = RoundedCornerShape(14.dp)
    /** Banners and panels that hold a card's worth of content. */
    val panel = RoundedCornerShape(16.dp)
    /** Full-width feature surfaces and the app mark. */
    val xl = RoundedCornerShape(20.dp)
    /** Dialogs and bottom sheets. */
    val dialog = RoundedCornerShape(28.dp)
}

/** Fixed sizes that more than one component has to agree on. */
object YapDims {
    /**
     * Status/meta pills — the small non-interactive badges inside a card.
     *
     * Fixed, not derived from content: a badge with an icon and one without were coming out
     * different heights side by side, and a card's badge row changed height when its state
     * changed. Height is a property of the *component*, not of what happens to be in it.
     */
    val pillHeight = 24.dp

    /** Tappable chips — filters, category pickers. Meets the 34dp comfortable-touch floor. */
    val chipHeight = 34.dp

    /**
     * Ceiling for a chip's label before it ellipsizes.
     *
     * A user-named category can be any length. Without a cap it either wraps (growing the row
     * and shoving everything below it down) or pushes its neighbours off the edge of a
     * scrolling row where they can't be found.
     */
    val chipMaxWidth = 156.dp
}
