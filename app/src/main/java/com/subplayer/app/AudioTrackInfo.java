package com.subplayer.app;

import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;

import java.util.Objects;

@OptIn(markerClass = UnstableApi.class)
final class AudioTrackInfo {
    static String label(Format format, int number) {
        String name = format.label != null && !format.label.isEmpty() ? format.label : "音轨 " + number;
        String language = format.language != null && !"und".equals(format.language)
                ? " · " + format.language : "";
        String channels = format.channelCount > 0 ? " · " + format.channelCount + " 声道" : "";
        return name + language + " · " + codec(format.sampleMimeType) + channels;
    }

    static String codec(String mime) {
        if (mime == null) return "未知编码";
        switch (mime) {
            case MimeTypes.AUDIO_DTS: return "DTS";
            case MimeTypes.AUDIO_DTS_HD: return "DTS-HD";
            case MimeTypes.AUDIO_AC3: return "AC3";
            case MimeTypes.AUDIO_E_AC3: return "E-AC3";
            case MimeTypes.AUDIO_E_AC3_JOC: return "E-AC3 JOC";
            case MimeTypes.AUDIO_TRUEHD: return "TrueHD";
            case MimeTypes.AUDIO_AAC: return "AAC";
            case MimeTypes.AUDIO_MPEG: return "MP3";
            case MimeTypes.AUDIO_FLAC: return "FLAC";
            case MimeTypes.AUDIO_OPUS: return "Opus";
            case MimeTypes.AUDIO_VORBIS: return "Vorbis";
            default: return mime;
        }
    }

    static boolean sameTrack(Format first, Format second) {
        return Objects.equals(first.id, second.id)
                && Objects.equals(first.sampleMimeType, second.sampleMimeType)
                && Objects.equals(first.language, second.language)
                && Objects.equals(first.label, second.label)
                && first.channelCount == second.channelCount
                && first.sampleRate == second.sampleRate;
    }

    static boolean isAudioError(int errorCode, String rendererMime, String rendererName) {
        return errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                || errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                || errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED
                || errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED
                || (rendererMime != null && rendererMime.startsWith("audio/"))
                || (rendererName != null && rendererName.contains("AudioRenderer"));
    }
}
