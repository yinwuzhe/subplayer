package com.subplayer.app;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;

import java.util.ArrayList;

@OptIn(markerClass = UnstableApi.class)
final class CompatibleRenderersFactory extends DefaultRenderersFactory {
    CompatibleRenderersFactory(Context context) {
        super(context);
        setEnableDecoderFallback(true);
    }

    @Override
    protected void buildAudioRenderers(Context context, int extensionRendererMode,
                                      MediaCodecSelector selector, boolean enableDecoderFallback,
                                      AudioSink audioSink, Handler handler,
                                      AudioRendererEventListener listener, ArrayList<Renderer> out) {
        AudioSink pcmSink = new PcmOnlyAudioSink(audioSink);
        if (FfmpegLibrary.isAvailable()) {
            out.add(new FfmpegAudioRenderer(handler, listener, pcmSink));
        }
        super.buildAudioRenderers(context, EXTENSION_RENDERER_MODE_OFF, selector,
                enableDecoderFallback, pcmSink, handler, listener, out);
    }

    static final class PcmOnlyAudioSink extends ForwardingAudioSink {
        PcmOnlyAudioSink(AudioSink sink) {
            super(sink);
        }

        @Override
        public boolean supportsFormat(Format format) {
            return MimeTypes.AUDIO_RAW.equals(format.sampleMimeType) && super.supportsFormat(format);
        }

        @Override
        public int getFormatSupport(Format format) {
            return MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)
                    ? super.getFormatSupport(format) : SINK_FORMAT_UNSUPPORTED;
        }

        @Override
        public AudioOffloadSupport getFormatOffloadSupport(Format format) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED;
        }
    }
}
