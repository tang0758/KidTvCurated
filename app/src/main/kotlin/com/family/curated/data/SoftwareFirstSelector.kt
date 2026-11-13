package com.family.curated.data

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * OPT-21: 零体积软解回退 (SoftwareFirstSelector)。
 * 当硬件解码器在特定机型（如 4xxx 错误）崩溃或不支持时，
 * 优先选用 Android 系统内置的软件解码器（c2.android.* / OMX.google.* / sw），
 * 无需引入额外体积包即可救活视频。
 */
class SoftwareFirstSelector : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        val list = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        return list.sortedByDescending { isSoftware(it.name) }
    }

    companion object {
        fun isSoftware(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("c2.android.") ||
                lower.startsWith("omx.google.") ||
                lower.contains(".sw.") ||
                lower.contains("software")
        }
    }
}
