package com.subplayer.app;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;

final class DlnaController {
    private final String url;
    private final String serviceType;

    DlnaController(String url, String serviceType) {
        this.url = url;
        this.serviceType = serviceType;
    }

    void setVideo(String mediaUrl, String title, String mime) throws Exception {
        String protocol = "http-get:*:" + mime
                + ":DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";
        String metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + xml(title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"" + xml(protocol) + "\">" + xml(mediaUrl)
                + "</res></item></DIDL-Lite>";
        String currentUri = "<CurrentURI>" + xml(mediaUrl) + "</CurrentURI>";
        try {
            call("SetAVTransportURI", currentUri + "<CurrentURIMetaData>"
                    + xml(metadata) + "</CurrentURIMetaData>");
        } catch (Exception e) {
            // 部分电视只接受空元数据。
            call("SetAVTransportURI", currentUri + "<CurrentURIMetaData></CurrentURIMetaData>");
        }
    }

    void play() throws Exception { call("Play", "<Speed>1</Speed>"); }
    void pause() throws Exception { call("Pause", ""); }
    void stop() throws Exception { call("Stop", ""); }

    String seekRelative(int seconds) throws Exception {
        Document position = call("GetPositionInfo", "");
        String current = text(position, "RelTime");
        if (current == null || current.equals("NOT_IMPLEMENTED"))
            throw new Exception("电视不支持读取播放进度");
        long target = Math.max(0, parseTime(current) + seconds);
        String duration = text(position, "TrackDuration");
        if (duration != null && !duration.equals("NOT_IMPLEMENTED")) {
            try { target = Math.min(target, parseTime(duration)); }
            catch (NumberFormatException ignored) { }
        }
        String value = formatTime(target);
        call("Seek", "<Unit>REL_TIME</Unit><Target>" + value + "</Target>");
        return value;
    }

    private Document call(String action, String arguments) throws Exception {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + xml(serviceType) + "\">"
                + "<InstanceID>0</InstanceID>" + arguments + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(5000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        connection.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            try (OutputStream request = connection.getOutputStream()) {
                request.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (stream != null) try (InputStream input = stream) {
                byte[] buffer = new byte[4096];
                while (output.size() < 256 * 1024) {
                    int read = input.read(buffer);
                    if (read < 0) break;
                    output.write(buffer, 0, read);
                }
            }
            if (status >= 400) throw new Exception("电视返回错误 " + status + "（" + action + "）");
            String response = output.toString("UTF-8");
            if (response.indexOf('\0') >= 0 || response.contains("<!DOCTYPE")
                    || response.contains("<!ENTITY")) throw new Exception("电视返回了不安全的 XML");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(output.toByteArray()));
        } finally {
            connection.disconnect();
        }
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String text(Document document, String name) {
        NodeList nodes = document.getElementsByTagNameNS("*", name);
        Node node = nodes.item(0);
        return node == null ? null : node.getTextContent();
    }

    private static long parseTime(String time) {
        String[] fields = time.split(":");
        if (fields.length != 3) throw new NumberFormatException(time);
        return Long.parseLong(fields[0]) * 3600 + Long.parseLong(fields[1]) * 60
                + (long) Double.parseDouble(fields[2]);
    }

    private static String formatTime(long time) {
        return String.format(java.util.Locale.US, "%02d:%02d:%02d",
                time / 3600, (time / 60) % 60, time % 60);
    }
}
