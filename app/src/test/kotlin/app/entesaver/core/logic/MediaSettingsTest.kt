package app.entesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Photos and videos each have their own setting now. An upgrade must land on
 * the same encode the single preset made, and every choice must mean what
 * the screen says it does.
 */
class MediaSettingsTest {

    @Test
    fun `the old single preset lands on the same encode`() {
        val (photo, video) = MediaSettings.fromLegacy("STORAGE_SAVER", "H264")
        assertEquals(PhotoPreset.BALANCED, photo.preset)
        assertEquals(16, photo.spec().maxMp)
        assertEquals(82, photo.spec().quality)
        assertEquals(1920, video.spec().longSide)
        assertEquals(VideoQuality.STANDARD, video.spec().quality)

        val (best, bestVideo) = MediaSettings.fromLegacy("BALANCED", null)
        assertEquals(PhotoPreset.BEST, best.preset)
        assertEquals(24, best.spec().maxMp)
        assertEquals(2560, bestVideo.spec().longSide)

        val (small, smallVideo) = MediaSettings.fromLegacy("MAX_SAVER", "H264")
        assertEquals(PhotoPreset.SMALLEST, small.preset)
        assertEquals(1280, smallVideo.spec().longSide)
    }

    @Test
    fun `a new install starts on Balanced for both`() {
        val (photo, video) = MediaSettings.fromLegacy(null, null)
        assertEquals(PhotoSettings(), photo)
        assertEquals(VideoSettings(), video)
    }

    @Test
    fun `an explicit HEVC choice is kept, as a Custom video setting`() {
        val (_, video) = MediaSettings.fromLegacy("BALANCED", "HEVC")
        assertEquals(VideoPreset.CUSTOM, video.preset)
        assertEquals(VideoCodecChoice.HEVC, video.spec().codec)
        // Everything else is what Best stands for.
        assertEquals(2560, video.spec().longSide)
        assertEquals(VideoQuality.HIGH, video.spec().quality)
    }

    @Test
    fun `custom values only count under Custom`() {
        val photo = PhotoSettings(PhotoPreset.BALANCED, PhotoFormat.WEBP, 8, 75)
        assertEquals(PhotoFormat.AUTO, photo.spec().format)
        assertEquals(PhotoFormat.WEBP, photo.copy(preset = PhotoPreset.CUSTOM).spec().format)
        val video = VideoSettings(VideoPreset.SMALLEST, longSide = 3840, audioKbps = 64)
        assertEquals(1280, video.spec().longSide)
        assertEquals(96, video.spec().audioKbps)
        assertEquals(64, video.copy(preset = VideoPreset.CUSTOM).spec().audioKbps)
    }

    @Test
    fun `full size and same resolution keep everything`() {
        assertEquals(Long.MAX_VALUE, PhotoSpec(PhotoFormat.JPEG, 0, 90).maxPixels)
        assertEquals(Int.MAX_VALUE, VideoSettings(VideoPreset.CUSTOM, longSide = 0).spec().longSideLimit)
    }

    @Test
    fun `an ordinary high frame rate is capped, slow motion never is`() {
        assertEquals(30f, MediaSettings.outputFps(60f, 30))
        assertEquals(30f, MediaSettings.outputFps(59.94f, 30))
        assertNull("already at the cap", MediaSettings.outputFps(30f, 30))
        assertNull("a hair over the cap is the same rate", MediaSettings.outputFps(30.2f, 30))
        assertNull("slow motion keeps every frame", MediaSettings.outputFps(120f, 30))
        assertNull("slow motion keeps every frame", MediaSettings.outputFps(240f, 30))
        assertNull("no cap asked for", MediaSettings.outputFps(60f, 0))
        assertNull(MediaSettings.outputFps(Float.NaN, 30))
    }

    @Test
    fun `keys group results by the encode and read back for the details`() {
        val photoKey = MediaSettings.photoKey(PhotoFormat.HEIC, PhotoSettings().spec())
        assertEquals("heic-16-82", photoKey)
        assertEquals(MediaSettings.PhotoKey(PhotoFormat.HEIC, 16, 82), MediaSettings.parsePhotoKey(photoKey))
        val videoKey = MediaSettings.videoKey(VideoCodec.HEVC, VideoSettings().spec())
        assertEquals("hevc-1920-std", videoKey)
        assertEquals(
            MediaSettings.VideoKey(VideoCodec.HEVC, 1920, VideoQuality.STANDARD),
            MediaSettings.parseVideoKey(videoKey)
        )
        // Keys the version-8 migration writes for files made before 11.
        assertEquals(MediaSettings.PhotoKey(PhotoFormat.JPEG, 16, 82), MediaSettings.parsePhotoKey("jpeg-16-82"))
        assertEquals(
            MediaSettings.VideoKey(VideoCodec.H264, 2560, VideoQuality.STANDARD),
            MediaSettings.parseVideoKey("h264-2560-std")
        )
        assertNull(MediaSettings.parsePhotoKey("STORAGE_SAVER"))
        assertNull(MediaSettings.parseVideoKey("garbage"))
    }

    @Test
    fun `the screen's choices are the ones a backup may restore`() {
        assertTrue(PhotoSettings().maxMp in MediaSettings.PHOTO_MP_CHOICES)
        assertTrue(PhotoSettings().quality in MediaSettings.PHOTO_QUALITY_CHOICES)
        assertTrue(VideoSettings().longSide in MediaSettings.VIDEO_LONG_SIDE_CHOICES)
        assertTrue(VideoSettings().fpsCap in MediaSettings.VIDEO_FPS_CHOICES)
        assertTrue(VideoSettings().audioKbps in MediaSettings.AUDIO_KBPS_CHOICES)
        for (preset in VideoPreset.entries) {
            val spec = VideoSettings(preset).spec()
            assertTrue(spec.longSide in MediaSettings.VIDEO_LONG_SIDE_CHOICES)
            assertTrue(spec.audioKbps in MediaSettings.AUDIO_KBPS_CHOICES)
        }
    }

    @Test
    fun `quality steps cost bits in the right order`() {
        for (codec in VideoCodec.entries) {
            assertTrue(BitrateCalc.bppFor(codec, VideoQuality.HIGH) > BitrateCalc.bppFor(codec, VideoQuality.STANDARD))
            assertTrue(BitrateCalc.bppFor(codec, VideoQuality.STANDARD) > BitrateCalc.bppFor(codec, VideoQuality.SMALL))
        }
        // HEVC needs fewer bits for the same look at every step.
        for (q in VideoQuality.entries) {
            assertTrue(BitrateCalc.bppFor(VideoCodec.HEVC, q) < BitrateCalc.bppFor(VideoCodec.H264, q))
        }
        // Standard is what every version before 11 used.
        assertEquals(BitrateCalc.BPP_H264, BitrateCalc.bppFor(VideoCodec.H264), 0.0)
    }
}
