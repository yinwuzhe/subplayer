package com.subplayer.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AudioDecoderDeviceTest {
    @Test public void nativeLibraryContainsRequiredAudioDecoders() {
        assertTrue("APK must contain a native FFmpeg library for this device", FfmpegLibrary.isAvailable());
        for (String mime : new String[]{MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD,
                MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC,
                MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG}) {
            assertTrue("Missing software decoder: " + mime, FfmpegLibrary.supportsFormat(mime));
        }
    }

    @Test public void softwareAudioRendererPrecedesSystemRenderer() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
                Renderer[] renderers = new CompatibleRenderersFactory(context).createRenderers(
                        new Handler(Looper.getMainLooper()), new VideoRendererEventListener() {},
                        new AudioRendererEventListener() {}, cues -> {}, metadata -> {});
                Renderer firstAudio = null;
                for (Renderer renderer : renderers) {
                    if (renderer.getTrackType() == C.TRACK_TYPE_AUDIO) { firstAudio = renderer; break; }
                }
                assertTrue(firstAudio instanceof FfmpegAudioRenderer);
                for (String mime : new String[]{MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3}) {
                    Format format = new Format.Builder().setSampleMimeType(mime)
                            .setChannelCount(2).setSampleRate(48000).build();
                    assertEquals(mime, C.FORMAT_HANDLED,
                            RendererCapabilities.getFormatSupport(firstAudio.getCapabilities().supportsFormat(format)));
                }
                for (Renderer renderer : renderers) renderer.release();
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
