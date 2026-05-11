package com.pixelhero.app

/**
 * Curated palette presets for common retro consoles + general-purpose palettes.
 * Color values are ARGB ints (alpha always 0xFF).
 */
object PaletteLibrary {

    data class Preset(val name: String, val colors: List<Int>)

    val DEFAULT = Preset("Par défaut", Project.DEFAULT_PALETTE)

    val PICO_8 = Preset("PICO-8", listOf(
        0xFF000000, 0xFF1D2B53, 0xFF7E2553, 0xFF008751,
        0xFFAB5236, 0xFF5F574F, 0xFFC2C3C7, 0xFFFFF1E8,
        0xFFFF004D, 0xFFFFA300, 0xFFFFEC27, 0xFF00E436,
        0xFF29ADFF, 0xFF83769C, 0xFFFF77A8, 0xFFFFCCAA
    ).map { it.toInt() })

    val GAMEBOY = Preset("Game Boy DMG", listOf(
        0xFF0F380F, 0xFF306230, 0xFF8BAC0F, 0xFF9BBC0F
    ).map { it.toInt() })

    val GAMEBOY_POCKET = Preset("Game Boy Pocket", listOf(
        0xFF2D1B00, 0xFF52502F, 0xFF9C9268, 0xFFC5CAA0
    ).map { it.toInt() })

    val NES = Preset("NES (sélection)", listOf(
        0xFF7C7C7C, 0xFF0000FC, 0xFF0000BC, 0xFF4428BC,
        0xFF940084, 0xFFA80020, 0xFFA81000, 0xFF881400,
        0xFF503000, 0xFF007800, 0xFF006800, 0xFF005800,
        0xFF004058, 0xFF000000, 0xFFBCBCBC, 0xFF0078F8,
        0xFF0058F8, 0xFF6844FC, 0xFFD800CC, 0xFFE40058,
        0xFFF83800, 0xFFE45C10, 0xFFAC7C00, 0xFF00B800,
        0xFF00A800, 0xFF00A844, 0xFF008888, 0xFFF8F8F8,
        0xFF3CBCFC, 0xFF6888FC, 0xFF9878F8, 0xFFF878F8
    ).map { it.toInt() })

    val SWEETIE_16 = Preset("Sweetie 16", listOf(
        0xFF1A1C2C, 0xFF5D275D, 0xFFB13E53, 0xFFEF7D57,
        0xFFFFCD75, 0xFFA7F070, 0xFF38B764, 0xFF257179,
        0xFF29366F, 0xFF3B5DC9, 0xFF41A6F6, 0xFF73EFF7,
        0xFFF4F4F4, 0xFF94B0C2, 0xFF566C86, 0xFF333C57
    ).map { it.toInt() })

    val ENDESGA_32 = Preset("Endesga 32", listOf(
        0xFFBE4A2F, 0xFFD77643, 0xFFEAD4AA, 0xFFE4A672,
        0xFFB86F50, 0xFF733E39, 0xFF3E2731, 0xFFA22633,
        0xFFE43B44, 0xFFF77622, 0xFFFEAE34, 0xFFFEE761,
        0xFF63C74D, 0xFF3E8948, 0xFF265C42, 0xFF193C3E,
        0xFF124E89, 0xFF0099DB, 0xFF2CE8F5, 0xFFFFFFFF,
        0xFFC0CBDC, 0xFF8B9BB4, 0xFF5A6988, 0xFF3A4466,
        0xFF262B44, 0xFF181425, 0xFFFF0044, 0xFF68386C,
        0xFFB55088, 0xFFF6757A, 0xFFE8B796, 0xFFC28569
    ).map { it.toInt() })

    val SKIN_TONES = Preset("Tons chair", listOf(
        0xFF2D1B14, 0xFF4A2C20, 0xFF6B3F2E, 0xFF8E5A3F,
        0xFFB57D5C, 0xFFD49C7B, 0xFFE6BFA0, 0xFFF4DAC0,
        0xFFFBEAD3, 0xFFFFF5E1
    ).map { it.toInt() })

    val ALL = listOf(DEFAULT, PICO_8, GAMEBOY, GAMEBOY_POCKET, NES, SWEETIE_16, ENDESGA_32, SKIN_TONES)
}
