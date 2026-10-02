package com.subplayer.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class VideoHttpServer implements AutoCloseable {
    private final ContentResolver resolver;
    private final Uri uri;
    private final long size;
    private final String mime;
    private final String title;
    private final String path = "/video/" + UUID.randomUUID();
    private final ServerSocket server;
    private final ExecutorService workers = Executors.newFixedThreadPool(5);
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean closed;

    VideoHttpServer(Context context, Uri uri) throws IOException {
        resolver = context.getContentResolver();
        this.uri = uri;
        long length = -1;
        String displayName = null;
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0) displayName = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) length = cursor.getLong(sizeColumn);
            }
        } catch (RuntimeException ignored) {
            // file:// URI 等来源可能没有可查询的元数据。
        }
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r")) {
            if (fd == null) throw new IOException("无法读取选中的视频");
            if (fd.getStatSize() > 0) length = fd.getStatSize();
        }
        if (length <= 0) throw new IOException("无法获取视频大小，电视无法读取该文件");
        size = length;
        String filename = displayName != null ? displayName : uri.getLastPathSegment();
        title = filename == null || filename.isEmpty() ? "SubPlayer 视频" : filename;
        String type = resolver.getType(uri);
        if (type == null || !type.startsWith("video/")) {
            int dot = title.lastIndexOf('.');
            type = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    title.substring(dot + 1).toLowerCase(Locale.US));
        }
        mime = type != null && type.startsWith("video/") ? type : "video/mp4";
        server = new ServerSocket(0);
        workers.execute(this::acceptLoop);
    }

    String getPath() { return path; }
    String getMime() { return mime; }
    String getTitle() { return title; }
    int getPort() { return server.getLocalPort(); }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = server.accept();
                clients.add(client);
                workers.execute(() -> handle(client));
            } catch (IOException | java.util.concurrent.RejectedExecutionException e) {
                if (!closed) android.util.Log.w("VideoHttpServer", "客户端连接失败", e);
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(60000);
            InputStream input = new BufferedInputStream(client.getInputStream());
            OutputStream output = new BufferedOutputStream(client.getOutputStream());
            String request = readLine(input);
            if (request == null) return;
            String[] parts = request.split(" ");
            String range = null;
            for (int i = 0; i < 64; i++) {
                String line = readLine(input);
                if (line == null || line.isEmpty()) break;
                if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
            }
            if (parts.length < 2 || !path.equals(parts[1])
                    || (!"GET".equals(parts[0]) && !"HEAD".equals(parts[0]))) {
                respond(output, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            long start = 0, end = size - 1;
            if (range != null) {
                try {
                    if (!range.startsWith("bytes=") || range.contains(","))
                        throw new NumberFormatException();
                    String value = range.substring(6);
                    int dash = value.indexOf('-');
                    if (dash < 0) throw new NumberFormatException();
                    if (dash == 0) {
                        long suffix = Long.parseLong(value.substring(1));
                        if (suffix <= 0) throw new NumberFormatException();
                        start = Math.max(0, size - suffix);
                    } else {
                        start = Long.parseLong(value.substring(0, dash));
                        if (dash + 1 < value.length()) end = Math.min(size - 1,
                                Long.parseLong(value.substring(dash + 1)));
                    }
                    if (start < 0 || start >= size || end < start) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    respond(output, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"
                            + size + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                    return;
                }
            }
            long remaining = end - start + 1;
            String headers = (range == null ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 206 Partial Content\r\n")
                    + "Content-Type: " + mime + "\r\nContent-Length: " + remaining
                    + "\r\nAccept-Ranges: bytes\r\ntransferMode.dlna.org: Streaming\r\n"
                    + "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;"
                    + "DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n"
                    + "Connection: close\r\n"
                    + (range == null ? "" : "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n")
                    + "\r\n";
            if ("HEAD".equals(parts[0])) {
                respond(output, headers);
                return;
            }
            try (ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r")) {
                if (fd == null) throw new IOException("文件已不可访问");
                try (FileInputStream file = new FileInputStream(fd.getFileDescriptor())) {
                    skipFully(file, start);
                    respond(output, headers);
                    byte[] buffer = new byte[64 * 1024];
                    while (remaining > 0 && !closed) {
                        int count = file.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (count < 0) break;
                        output.write(buffer, 0, count);
                        remaining -= count;
                    }
                    output.flush();
                }
            }
        } catch (IOException e) {
            if (!closed) android.util.Log.w("VideoHttpServer", "传输中断", e);
        } finally {
            clients.remove(socket);
        }
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        while (count > 0) {
            long skipped = input.skip(count);
            if (skipped <= 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, count));
                if (read < 0) throw new IOException("视频无法跳转到指定位置");
                skipped = read;
            }
            count -= skipped;
        }
    }

    private static String readLine(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        int c;
        while ((c = input.read()) != -1 && c != '\n') {
            if (line.size() >= 8192) throw new IOException("HTTP 请求过长");
            if (c != '\r') line.write(c);
        }
        if (c == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }

    private static void respond(OutputStream output, String response) throws IOException {
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    @Override
    public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        synchronized (clients) {
            for (Socket client : clients) {
                try { client.close(); } catch (IOException ignored) { }
            }
            clients.clear();
        }
        workers.shutdownNow();
    }
}
