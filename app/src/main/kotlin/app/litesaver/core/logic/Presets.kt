package app.litesaver.core.logic

/**
 * Quality presets, Google Photos "Storage saver" equivalent.
 * videoLongSide: output video long side limit (px)
 * photoMaxMp:    output photo pixel budget (megapixels)
 * jpegQuality:   JPEG quality for compressed photos
 */
data class PresetSpec(val videoLongSide: Int, val photoMaxMp: Int, val jpegQuality: Int)

object Presets {

    fun spec(preset: Preset): PresetSpec = when (preset) {
        Preset.STORAGE_SAVER -> PresetSpec(videoLongSide = 1920, photoMaxMp = 16, jpegQuality = 82)
        Preset.BALANCED -> PresetSpec(videoLongSide = 2560, photoMaxMp = 24, jpegQuality = 85)
        Preset.MAX_SAVER -> PresetSpec(videoLongSide = 1280, photoMaxMp = 8, jpegQuality = 80)
    }

    fun photoMaxPixels(preset: Preset): Long = spec(preset).photoMaxMp * 1_000_000L
}
