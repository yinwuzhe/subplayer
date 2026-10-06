package com.subplayer.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

final class SubtitleFile {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Pattern TIMING = Pattern.compile(
            "(?m)^\\s*(?:\\d+:)?\\d{2}:\\d{2}[,.]\\d{3}\\s+-->\\s+(?:\\d+:)?\\d{2}:\\d{2}[,.]\\d{3}");
    private static final Pattern ASS_DIALOGUE = Pattern.compile("(?im)^\\s*Dialogue\\s*:");

    final String name;
    final String mime;
    final String extension;
    final byte[] utf8;

    private SubtitleFile(String name, String mime, String extension, byte[] utf8) {
        this.name = name;
        this.mime = mime;
        this.extension = extension;
        this.utf8 = utf8;
    }

    static SubtitleFile read(InputStream input, String name) throws IOException {
        if (input == null) throw new IOException("无法读取字幕文件");
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime;
        switch (extension) {
            case "srt": mime = "application/x-subrip"; break;
            case "ass":
            case "ssa": mime = "text/x-ssa"; break;
            case "vtt": mime = "text/vtt"; break;
            default: throw new IOException("请选择 SRT、ASS、SSA 或 VTT 字幕文件");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_BYTES) throw new IOException("字幕文件不能超过 8 MB");
            output.write(buffer, 0, count);
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length == 0) throw new IOException("字幕文件为空");
        String text;
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) {
            text = decode(bytes, StandardCharsets.UTF_16);
        } else {
            try { text = decode(bytes, StandardCharsets.UTF_8); }
            catch (CharacterCodingException e) { text = decode(bytes, Charset.forName("GB18030")); }
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        boolean valid = text.indexOf('\0') < 0;
        if (extension.equals("ass") || extension.equals("ssa")) {
            String lower = text.toLowerCase(Locale.ROOT);
            valid &= lower.contains("[script info]") && lower.contains("[events]")
                    && ASS_DIALOGUE.matcher(text).find();
        } else {
            valid &= TIMING.matcher(text).find();
            if (extension.equals("vtt")) valid &= text.startsWith("WEBVTT");
        }
        if (!valid) throw new IOException("字幕格式无效或没有可显示的字幕，请检查文件内容");
        return new SubtitleFile(name, mime, extension, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
