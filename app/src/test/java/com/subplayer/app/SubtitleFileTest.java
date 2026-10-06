package com.subplayer.app;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class SubtitleFileTest {
    private static final String SRT = "1\r\n00:00:00,000 --> 00:00:02,000\r\n中文字幕\r\n";

    @Test public void readsAllFormats() throws Exception {
        String[] names = {"中文 sample.srt", "sample.ass", "sample.ssa", "sample.vtt"};
        String[] mimes = {"application/x-subrip", "text/x-ssa", "text/x-ssa", "text/vtt"};
        for (int i = 0; i < names.length; i++) {
            try (InputStream input = getClass().getResourceAsStream("/subtitles/" + names[i])) {
                assertNotNull(input);
                SubtitleFile file = SubtitleFile.read(input, names[i]);
                assertEquals(mimes[i], file.mime);
                assertTrue(new String(file.utf8, StandardCharsets.UTF_8).contains("外挂字幕"));
            }
        }
    }

    @Test public void normalizesBomAndLineEndings() throws Exception {
        SubtitleFile file = read(("\uFEFF" + SRT).getBytes(StandardCharsets.UTF_8), "字幕.SRT");
        String text = new String(file.utf8, StandardCharsets.UTF_8);
        assertFalse(text.startsWith("\uFEFF"));
        assertFalse(text.contains("\r"));
        assertTrue(text.contains("中文字幕"));
    }

    @Test public void convertsChineseAndUtf16Encodings() throws Exception {
        for (Charset charset : new Charset[]{StandardCharsets.UTF_16, Charset.forName("GB18030")}) {
            SubtitleFile file = read(SRT.getBytes(charset), "中文.srt");
            assertTrue(new String(file.utf8, StandardCharsets.UTF_8).contains("中文字幕"));
        }
    }

    @Test public void rejectsEmptyUnsupportedAndMalformedFiles() {
        assertThrows(IOException.class, () -> read(new byte[0], "empty.srt"));
        assertThrows(IOException.class, () -> read(SRT.getBytes(StandardCharsets.UTF_8), "video.mkv"));
        assertThrows(IOException.class, () -> read("not a subtitle".getBytes(StandardCharsets.UTF_8), "bad.srt"));
        assertThrows(IOException.class, () -> read(SRT.getBytes(StandardCharsets.UTF_8), "bad.vtt"));
        assertThrows(IOException.class, () -> read(SRT.getBytes(StandardCharsets.UTF_8), "bad.ass"));
    }

    @Test public void rejectsOversizedAndBinaryInput() {
        assertThrows(IOException.class, () -> read(new byte[SubtitleFile.MAX_BYTES + 1], "huge.srt"));
        assertThrows(IOException.class, () -> read((SRT + '\0').getBytes(StandardCharsets.UTF_8), "binary.srt"));
    }

    private static SubtitleFile read(byte[] bytes, String name) throws IOException {
        return SubtitleFile.read(new ByteArrayInputStream(bytes), name);
    }
}
