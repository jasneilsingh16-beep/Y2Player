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
            onFocusSecondary = 0xFFDCE9FB.toInt(),
            track = 0xFFE5E5EA.toInt(),
            scrollThumb = 0xFFC7C7CC.toInt()
        )

        /** Porcelain after dark: warm paper and a muted blue, for bed and Amrit Vela. */
        val BEDTIME = Y2Palette(
            background = 0xFFE9DFCF.toInt(),
            surface = 0xFFE1D5C1.toInt(),
            surfaceRaised = 0xFFDDD0BA.toInt(),
            focusSurface = 0xFFD5CBC0.toInt(),
            activeSurface = 0xFFDFD5C6.toInt(),
            primaryText = 0xFF2A2219.toInt(),
            secondaryText = 0xFF5E5141.toInt(),
            mutedText = 0xFF6A5C4A.toInt(),
            accent = 0xFF3F5F8F.toInt(),
            divider = 0xFFD6CAB6.toInt(),
            warning = 0xFF8F3A12.toInt(),
            success = 0xFF3E6B4C.toInt(),
            focusFill = 0xFF3F5F8F.toInt(),
            onFocus = 0xFFF4EDE2.toInt(),
            onFocusSecondary = 0xFFD9DDE4.toInt(),
            track = 0xFFCFC1AA.toInt(),
            scrollThumb = 0xFFB3A48C.toInt()
        )

        fun of(lightTheme: Boolean, bedtime: Boolean = false): Y2Palette = when {
            bedtime -> BEDTIME
            lightTheme -> LIGHT
            else -> DARK
        }
    }
}
