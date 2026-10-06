package com.subplayer.app;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class AudioTrackInfoTest {
    @Test public void labelsCommonSurroundCodecs() {
        assertEquals("DTS", AudioTrackInfo.codec(MimeTypes.AUDIO_DTS));
        assertEquals("DTS-HD", AudioTrackInfo.codec(MimeTypes.AUDIO_DTS_HD));
        assertEquals("AC3", AudioTrackInfo.codec(MimeTypes.AUDIO_AC3));
        assertEquals("E-AC3", AudioTrackInfo.codec(MimeTypes.AUDIO_E_AC3));
        assertEquals("TrueHD", AudioTrackInfo.codec(MimeTypes.AUDIO_TRUEHD));
        assertEquals("未知编码", AudioTrackInfo.codec(null));
    }

    @Test public void displaysLanguageAndChannelCount() {
        Format format = new Format.Builder().setId("1").setSampleMimeType(MimeTypes.AUDIO_DTS)
                .setLabel("原声").setLanguage("kor").setChannelCount(6).build();
        String label = AudioTrackInfo.label(format, 1);
        assertEquals("ko", format.language);
        assertEquals("原声 · ko · DTS · 6 声道", label);
    }

    @Test public void preservesSelectionAcrossSubtitleRebuilds() {
        Format first = new Format.Builder().setId("2").setSampleMimeType(MimeTypes.AUDIO_AC3)
                .setLanguage("en").setChannelCount(6).setSampleRate(48000).build();
        assertTrue(AudioTrackInfo.sameTrack(first, first.buildUpon().build()));
        assertFalse(AudioTrackInfo.sameTrack(first, first.buildUpon().setId("3").build()));
        assertFalse(AudioTrackInfo.sameTrack(first, first.buildUpon().setLanguage("zh").build()));
        assertFalse(AudioTrackInfo.sameTrack(first, first.buildUpon().setChannelCount(2).build()));
    }

    @Test public void audioErrorsAreNotSubtitleErrors() {
        assertTrue(AudioTrackInfo.isAudioError(PlaybackException.ERROR_CODE_DECODING_FAILED,
                MimeTypes.AUDIO_DTS, "FfmpegAudioRenderer"));
        assertTrue(AudioTrackInfo.isAudioError(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, null, null));
        assertTrue(AudioTrackInfo.isAudioError(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, null, null));
        assertTrue(AudioTrackInfo.isAudioError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                null, "MediaCodecAudioRenderer"));
        assertFalse(AudioTrackInfo.isAudioError(PlaybackException.ERROR_CODE_DECODING_FAILED,
                MimeTypes.TEXT_SSA, "TextRenderer"));
        assertFalse(AudioTrackInfo.isAudioError(PlaybackException.ERROR_CODE_DECODING_FAILED,
                MimeTypes.VIDEO_H264, "MediaCodecVideoRenderer"));
        assertFalse(AudioTrackInfo.isAudioError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, null, null));
    }
}
