package com.schulzcode.y2player.ui

data class Y2Palette(
    val background: Int,
    val surface: Int,
    val surfaceRaised: Int,
    val focusSurface: Int,
    val activeSurface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val mutedText: Int,
    val accent: Int,
    val divider: Int,
    val warning: Int,
    val success: Int,
    /** The focused row: a solid fill, with text drawn in [onFocus]. */
    val focusFill: Int,
    val onFocus: Int,
    val onFocusSecondary: Int,
    /** Unfilled part of progress bars, rings and the scroll track. */
    val track: Int,
    val scrollThumb: Int
) {
    companion object {
        val DARK = Y2Palette(
            background = 0xFF0A0D12.toInt(),
            surface = 0xFF131821.toInt(),
            surfaceRaised = 0xFF1A202B.toInt(),
            focusSurface = 0xFF2B3545.toInt(),
            activeSurface = 0xFF1F2832.toInt(),
            primaryText = 0xFFF4F1EA.toInt(),
            secondaryText = 0xFFB9BFC6.toInt(),
            mutedText = 0xFF8E949F.toInt(),
            accent = 0xFFD6AC53.toInt(),
            divider = 0xFF262D37.toInt(),
            warning = 0xFFE1836A.toInt(),
            success = 0xFF77B597.toInt(),
            focusFill = 0xFFD6AC53.toInt(),
            onFocus = 0xFF0A0D12.toInt(),
            onFocusSecondary = 0xFF3A2E10.toInt(),
            track = 0xFF2B3240.toInt(),
            scrollThumb = 0xFF59606C.toInt()
        )

        /** Porcelain: white, one blue, near-black type. */
        val LIGHT = Y2Palette(
            background = 0xFFFFFFFF.toInt(),
            surface = 0xFFF5F5F7.toInt(),
            surfaceRaised = 0xFFF2F2F5.toInt(),
            focusSurface = 0xFFE6EEFB.toInt(),
            activeSurface = 0xFFEEF3FB.toInt(),
            primaryText = 0xFF111114.toInt(),
            secondaryText = 0xFF5A5A63.toInt(),
            mutedText = 0xFF6E6E78.toInt(),
            accent = 0xFF0A64D6.toInt(),
            divider = 0xFFE5E5EA.toInt(),
            warning = 0xFFB4410A.toInt(),
            success = 0xFF1F7A4D.toInt(),
            focusFill = 0xFF0A64D6.toInt(),
            onFocus = 0xFFFFFFFF.toInt(),
            onFocusSecondary = 0xFFEAF2FE.toInt(),
            track = 0xFFE5E5EA.toInt(),
            scrollThumb = 0xFFAEAEB2.toInt()
        )

        /**
         * Bedtime: dark umber with amber, so the screen gives off as little light as it
         * can and nothing on it is blue. Brightness is capped while it is on.
         */
        val BEDTIME = Y2Palette(
            background = 0xFF14100C.toInt(),
            surface = 0xFF1E1812.toInt(),
            surfaceRaised = 0xFF261E16.toInt(),
            focusSurface = 0xFF3A2A1A.toInt(),
            activeSurface = 0xFF2A2017.toInt(),
            primaryText = 0xFFE9D9BF.toInt(),
            secondaryText = 0xFFB39D7E.toInt(),
            mutedText = 0xFF8C7A62.toInt(),
            accent = 0xFFD9A35F.toInt(),
            divider = 0xFF2A2219.toInt(),
            warning = 0xFFE08A5A.toInt(),
            success = 0xFF9DB07A.toInt(),
            focusFill = 0xFF8C5622.toInt(),
            onFocus = 0xFFF6E7CF.toInt(),
            onFocusSecondary = 0xFFE2C9A6.toInt(),
            track = 0xFF3A2E22.toInt(),
            scrollThumb = 0xFF6E5C46.toInt()
        )

        fun of(lightTheme: Boolean, bedtime: Boolean = false): Y2Palette = when {
            bedtime -> BEDTIME
            lightTheme -> LIGHT
            else -> DARK
        }
    }
}
