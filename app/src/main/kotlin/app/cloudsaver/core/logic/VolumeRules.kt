package app.cloudsaver.core.logic

/**
 * Which volume a release really goes to, decided before any I/O.
 *
 * The user may have chosen an SD card; the probe may say that card cannot
 * take gallery inserts; the card may have been fine yesterday and gone today.
 * The rule is one sentence: releases go to the chosen volume only while the
 * probe passes, and fall back to the primary volume - never fail - otherwise.
 * Files already released stay where they are (BB2.3): moving them would break
 * the very uris the cloud app is watching.
 */
object VolumeRules {

    data class Decision(
        /** MediaStore volume name to insert into. */
        val volumeName: String,
        /** True when the user's choice could not be honoured. */
        val fellBack: Boolean
    )

    const val PRIMARY = "external_primary"

    /**
     * [selectedVolume] is the user's stored choice ("" means primary), and
     * [selectedWritable] is the probe's current answer for that volume.
     */
    fun releaseVolume(selectedVolume: String, selectedWritable: Boolean): Decision {
        if (selectedVolume.isEmpty() || selectedVolume == PRIMARY) {
            return Decision(PRIMARY, fellBack = false)
        }
        return if (selectedWritable) {
            Decision(selectedVolume, fellBack = false)
        } else {
            Decision(PRIMARY, fellBack = true)
        }
    }

    /** Settings offers the SD card for the upload folder only when this is true. */
    fun offerSdForOutput(probePassed: Boolean): Boolean = probePassed

    /**
     * FAT32 tops out just under 4 GB per file (Z3.4). A queued file at or
     * over the limit cannot land on such a card and is copied to internal
     * storage instead, with the reason recorded.
     */
    const val FAT32_MAX_BYTES = 4L * 1024 * 1024 * 1024 - 1

    fun fitsOnFat32(sizeBytes: Long): Boolean = sizeBytes <= FAT32_MAX_BYTES
}
