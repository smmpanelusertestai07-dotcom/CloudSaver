package app.entesaver.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.entesaver.R
import app.entesaver.core.logic.HdrPolicy
import app.entesaver.core.logic.MediaSettings
import app.entesaver.core.logic.PhotoFormat
import app.entesaver.core.logic.PhotoPreset
import app.entesaver.core.logic.PhotoSettings
import app.entesaver.core.logic.PhotoSpec
import app.entesaver.core.logic.VideoCodec
import app.entesaver.core.logic.VideoCodecChoice
import app.entesaver.core.logic.VideoPreset
import app.entesaver.core.logic.VideoQuality
import app.entesaver.core.logic.VideoSettings
import app.entesaver.core.logic.VideoSpec
import app.entesaver.media.HeicSupport
import app.entesaver.ui.AppViewModel
import app.entesaver.ui.components.SegmentedChoice
import app.entesaver.util.DeviceTier

/**
 * The Photos card: a preset, what it means on this phone in one line, and -
 * under Custom - every knob. Unsupported choices stay offered with the reason
 * beside them rather than vanishing, so a setting never changes behind the
 * person's back.
 */
@Composable
fun PhotoSettingsCard(
    photo: PhotoSettings,
    plan: AppViewModel.EncodePlan,
    icon: ImageVector,
    onChange: (PhotoSettings) -> Unit,
    onInfo: () -> Unit
) {
    val spec = photo.spec()
    OptionCard(
        stringResource(R.string.opt_photos),
        stringResource(R.string.opt_photos_hint),
        icon = icon,
        value = photoPresetLabel(photo.preset),
        onInfo = onInfo
    ) {
        SegmentedChoice(
            PhotoPreset.entries.map { it.name to photoPresetLabel(it) },
            photo.preset.name
        ) { onChange(photo.copy(preset = PhotoPreset.valueOf(it))) }
        if (photo.preset != PhotoPreset.CUSTOM) {
            ChoiceNote(
                stringResource(
                    when (photo.preset) {
                        PhotoPreset.BEST -> R.string.preset_best_photo
                        PhotoPreset.SMALLEST -> R.string.preset_smallest_photo
                        else -> R.string.preset_balanced_photo
                    }
                )
            )
        }
        // What this phone will actually do: the line a person can hold the
        // app to, with the phone's own memory ceiling applied.
        val shown = DeviceTier.capped(spec, plan.photoCeilingMp)
        ChoiceNote(
            stringResource(
                R.string.photo_plan,
                if (shown.maxMp != spec.maxMp && plan.photoCeilingIsPhones) {
                    stringResource(R.string.photo_size_mp_phone, shown.maxMp)
                } else {
                    photoLimitPhrase(shown)
                },
                formatLabel(plan.photoFormat),
                spec.quality
            )
        )
        AnimatedVisibility(visible = photo.preset == PhotoPreset.CUSTOM) {
            Column {
                KnobTitle(stringResource(R.string.custom_format))
                SegmentedChoice(
                    PhotoFormat.entries.map { it.name to formatLabel(it) },
                    photo.format.name
                ) { onChange(photo.copy(format = PhotoFormat.valueOf(it))) }
                KnobTitle(stringResource(R.string.custom_size))
                SegmentedChoice(
                    MediaSettings.PHOTO_MP_CHOICES.map { mp ->
                        mp.toString() to if (mp <= 0) {
                            stringResource(R.string.size_full_short)
                        } else {
                            stringResource(R.string.size_mp_short, mp)
                        }
                    },
                    photo.maxMp.toString()
                ) { onChange(photo.copy(maxMp = it.toInt())) }
                KnobTitle(stringResource(R.string.custom_quality))
                SegmentedChoice(
                    MediaSettings.PHOTO_QUALITY_CHOICES.map { it.toString() to it.toString() },
                    photo.quality.toString()
                ) { onChange(photo.copy(quality = it.toInt())) }
            }
        }
        if (spec.format == PhotoFormat.AUTO || spec.format == PhotoFormat.HEIC) {
            ChoiceNote(
                stringResource(
                    when (plan.heic) {
                        HeicSupport.State.OK -> R.string.photo_heic_ok
                        HeicSupport.State.FAIL -> R.string.photo_heic_fail
                        HeicSupport.State.UNKNOWN -> R.string.photo_heic_unknown
                    }
                )
            )
        }
        if (spec.format == PhotoFormat.WEBP) ChoiceNote(stringResource(R.string.photo_webp_note))
        ChoiceNote(stringResource(R.string.photo_ultra_hdr_note))
        ChoiceNote(stringResource(R.string.applies_to_new_only))
    }
}

/** The Videos card, the same shape as the Photos one. */
@Composable
fun VideoSettingsCard(
    video: VideoSettings,
    plan: AppViewModel.EncodePlan,
    icon: ImageVector,
    onChange: (VideoSettings) -> Unit,
    onInfo: () -> Unit
) {
    val spec = video.spec()
    OptionCard(
        stringResource(R.string.opt_videos),
        stringResource(R.string.opt_videos_hint),
        icon = icon,
        value = videoPresetLabel(video.preset),
        onInfo = onInfo
    ) {
        SegmentedChoice(
            VideoPreset.entries.map { it.name to videoPresetLabel(it) },
            video.preset.name
        ) { onChange(video.copy(preset = VideoPreset.valueOf(it))) }
        if (video.preset != VideoPreset.CUSTOM) {
            ChoiceNote(
                stringResource(
                    when (video.preset) {
                        VideoPreset.BEST -> R.string.preset_best_video
                        VideoPreset.SMALLEST -> R.string.preset_smallest_video
                        else -> R.string.preset_balanced_video
                    }
                )
            )
        }
        // The phone's own limit: the smallest phones, and video chips that
        // stop below 2160p.
        ChoiceNote(
            stringResource(
                R.string.video_plan,
                when {
                    plan.videoHeldTo > 0 -> stringResource(R.string.res_phone_cap, MediaSettings.pLabel(plan.videoHeldTo))
                    spec.longSide <= 0 -> stringResource(R.string.res_keep)
                    else -> MediaSettings.pLabel(spec.longSide)
                },
                codecLabel(plan.videoCodec),
                if (spec.fpsCap <= 0) {
                    stringResource(R.string.fps_keep)
                } else {
                    stringResource(R.string.fps_cap, spec.fpsCap)
                },
                videoQualityLabel(spec.quality).lowercase(),
                spec.audioKbps
            )
        )
        AnimatedVisibility(visible = video.preset == VideoPreset.CUSTOM) {
            Column {
                KnobTitle(stringResource(R.string.custom_codec))
                SegmentedChoice(
                    listOf(
                        VideoCodecChoice.AUTO.name to stringResource(R.string.codec_auto),
                        VideoCodecChoice.H264.name to stringResource(R.string.codec_h264),
                        VideoCodecChoice.HEVC.name to stringResource(R.string.codec_hevc)
                    ),
                    video.codec.name
                ) { onChange(video.copy(codec = VideoCodecChoice.valueOf(it))) }
                KnobTitle(stringResource(R.string.custom_resolution))
                SegmentedChoice(
                    MediaSettings.VIDEO_LONG_SIDE_CHOICES.map { side ->
                        side.toString() to if (side <= 0) {
                            stringResource(R.string.res_keep_short)
                        } else {
                            MediaSettings.pLabel(side)
                        }
                    },
                    video.longSide.toString()
                ) { onChange(video.copy(longSide = it.toInt())) }
                KnobTitle(stringResource(R.string.custom_fps))
                SegmentedChoice(
                    MediaSettings.VIDEO_FPS_CHOICES.map { fps ->
                        fps.toString() to if (fps <= 0) {
                            stringResource(R.string.fps_keep_short)
                        } else {
                            stringResource(R.string.fps_cap_short, fps)
                        }
                    },
                    video.fpsCap.toString()
                ) { onChange(video.copy(fpsCap = it.toInt())) }
                KnobTitle(stringResource(R.string.custom_quality))
                SegmentedChoice(
                    VideoQuality.entries.map { it.name to videoQualityLabel(it) },
                    video.quality.name
                ) { onChange(video.copy(quality = VideoQuality.valueOf(it))) }
                KnobTitle(stringResource(R.string.custom_sound))
                SegmentedChoice(
                    MediaSettings.AUDIO_KBPS_CHOICES.map { it.toString() to it.toString() },
                    video.audioKbps.toString()
                ) { onChange(video.copy(audioKbps = it.toInt())) }
                KnobTitle(stringResource(R.string.custom_hdr))
                SegmentedChoice(
                    listOf(
                        HdrPolicy.KEEP_WHEN_POSSIBLE.name to stringResource(R.string.hdr_keep),
                        HdrPolicy.TO_SDR.name to stringResource(R.string.hdr_sdr)
                    ),
                    video.hdr.name
                ) { onChange(video.copy(hdr = HdrPolicy.valueOf(it))) }
                ChoiceNote(
                    stringResource(
                        if (video.hdr == HdrPolicy.TO_SDR) R.string.hdr_sdr_note else R.string.hdr_keep_note
                    )
                )
            }
        }
        if (spec.codec != VideoCodecChoice.H264) {
            ChoiceNote(stringResource(if (plan.hevcHardware) R.string.video_hevc_ok else R.string.video_hevc_none))
        }
        if (spec.fpsCap > 0) ChoiceNote(stringResource(R.string.video_fps_note))
        ChoiceNote(stringResource(R.string.applies_to_new_only))
    }
}

@Composable
private fun KnobTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 12.dp)
    )
}

@Composable
fun photoPresetLabel(preset: PhotoPreset): String = stringResource(
    when (preset) {
        PhotoPreset.BEST -> R.string.preset_best
        PhotoPreset.BALANCED -> R.string.preset_balanced
        PhotoPreset.SMALLEST -> R.string.preset_smallest
        PhotoPreset.CUSTOM -> R.string.preset_custom
    }
)

@Composable
fun videoPresetLabel(preset: VideoPreset): String = stringResource(
    when (preset) {
        VideoPreset.BEST -> R.string.preset_best
        VideoPreset.BALANCED -> R.string.preset_balanced
        VideoPreset.SMALLEST -> R.string.preset_smallest
        VideoPreset.CUSTOM -> R.string.preset_custom
    }
)

@Composable
fun formatLabel(format: PhotoFormat): String = stringResource(
    when (format) {
        PhotoFormat.AUTO -> R.string.format_auto
        PhotoFormat.HEIC -> R.string.format_heic
        PhotoFormat.JPEG -> R.string.format_jpeg
        PhotoFormat.WEBP -> R.string.format_webp
    }
)

@Composable
fun codecLabel(codec: VideoCodec): String = stringResource(
    if (codec == VideoCodec.HEVC) R.string.codec_hevc else R.string.codec_h264
)

@Composable
fun videoQualityLabel(quality: VideoQuality): String = stringResource(
    when (quality) {
        VideoQuality.HIGH -> R.string.video_quality_high
        VideoQuality.STANDARD -> R.string.video_quality_standard
        VideoQuality.SMALL -> R.string.video_quality_small
    }
)

/** "up to 16 MP" or "full size", for sentences about the current setting. */
@Composable
fun photoLimitPhrase(spec: PhotoSpec): String =
    if (spec.maxMp <= 0) {
        stringResource(R.string.photo_size_full)
    } else {
        stringResource(R.string.photo_size_mp, spec.maxMp)
    }

/** "up to 1080p" or "as filmed". */
@Composable
fun videoLimitPhrase(spec: VideoSpec): String =
    if (spec.longSide <= 0) {
        stringResource(R.string.res_keep)
    } else {
        stringResource(R.string.video_size_p, MediaSettings.pLabel(spec.longSide))
    }
