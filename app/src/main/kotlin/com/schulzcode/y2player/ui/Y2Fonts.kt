package com.schulzcode.y2player.ui

import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * Inter, the face the Porcelain boards were drawn in. KitKat's Roboto has only
 * regular and bold. Each weight has one job: regular for second lines and values,
 * medium for row titles, semibold for screen titles and labels, bold for the Now
 * Playing title alone. Letters Inter lacks, such as Gurmukhi, fall back to the
 * system font.
 */
class Y2Fonts(assets: AssetManager?) {
    val regular: Typeface = load(assets, "inter_regular.ttf", Typeface.NORMAL)
    val medium: Typeface = load(assets, "inter_medium.ttf", Typeface.NORMAL)
    val semibold: Typeface = load(assets, "inter_semibold.ttf", Typeface.BOLD)
    val bold: Typeface = load(assets, "inter_bold.ttf", Typeface.BOLD)

    private fun load(assets: AssetManager?, name: String, fallbackStyle: Int): Typeface =
        assets?.let { runCatching { Typeface.createFromAsset(it, "fonts/$name") }.getOrNull() }
            ?: Typeface.create("sans", fallbackStyle)
}
