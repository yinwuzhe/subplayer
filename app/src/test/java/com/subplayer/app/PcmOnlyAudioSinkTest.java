package com.subplayer.app;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.AudioSink;

import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PcmOnlyAudioSinkTest {
    @Test public void compressedFormatsNeverBypassSoftwareDecoder() {
        AtomicInteger delegated = new AtomicInteger();
        AudioSink delegate = (AudioSink) Proxy.newProxyInstance(AudioSink.class.getClassLoader(),
                new Class<?>[]{AudioSink.class}, (proxy, method, args) -> {
                    delegated.incrementAndGet();
                    if (method.getName().equals("supportsFormat")) return true;
                    if (method.getName().equals("getFormatSupport")) return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY;
                    throw new AssertionError("Unexpected call: " + method.getName());
                });
        CompatibleRenderersFactory.PcmOnlyAudioSink sink = new CompatibleRenderersFactory.PcmOnlyAudioSink(delegate);
        for (String mime : new String[]{MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_AC3,
                MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AAC}) {
            Format encoded = new Format.Builder().setSampleMimeType(mime).build();
            assertFalse(sink.supportsFormat(encoded));
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(encoded));
            assertSame(AudioOffloadSupport.DEFAULT_UNSUPPORTED, sink.getFormatOffloadSupport(encoded));
        }
        assertEquals(0, delegated.get());
        Format pcm = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(2).setSampleRate(48000).build();
        assertTrue(sink.supportsFormat(pcm));
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm));
        assertEquals(2, delegated.get());
    }
}
