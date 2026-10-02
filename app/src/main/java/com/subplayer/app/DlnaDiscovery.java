package com.subplayer.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.parsers.DocumentBuilderFactory;

final class DlnaDiscovery {
    static final class Device {
        final String name;
        final String controlUrl;
        final String serviceType;

        Device(String name, String controlUrl, String serviceType) {
            this.name = name;
            this.controlUrl = controlUrl;
            this.serviceType = serviceType;
        }
    }

    interface Listener {
        void onDevice(Device device);
        void onComplete(String error);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile DatagramSocket socket;

    DlnaDiscovery(Context context) {
        this.context = context.getApplicationContext();
    }

    void start(Listener listener) {
        executor.execute(() -> search(listener));
    }

    void close() {
        closed = true;
        DatagramSocket current = socket;
        if (current != null) current.close();
        executor.shutdownNow();
    }

    private void search(Listener listener) {
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wifi != null
                ? wifi.createMulticastLock("subplayer-dlna-discovery") : null;
        String error = null;
        Set<String> locations = new HashSet<>();
        try (DatagramSocket udp = new DatagramSocket()) {
            if (lock != null) {
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            socket = udp;
            udp.setSoTimeout(750);
            String query = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                    + "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";
            byte[] request = query.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(request, request.length,
                    InetAddress.getByName("239.255.255.250"), 1900);
            udp.send(packet);
            udp.send(packet);
            long deadline = System.currentTimeMillis() + 5500;
            while (!closed && System.currentTimeMillis() < deadline) {
                try {
                    byte[] buffer = new byte[8192];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    udp.receive(response);
                    String headers = new String(response.getData(), 0, response.getLength(),
                            StandardCharsets.UTF_8);
                    String location = header(headers, "location");
                    if (location == null || !locations.add(location)) continue;
                    URL url = new URL(location);
                    if (!"http".equalsIgnoreCase(url.getProtocol())) continue;
                    try {
                        Device device = describe(url);
                        if (device != null && !closed) main.post(() -> {
                            if (!closed) listener.onDevice(device);
                        });
                    } catch (Exception ignored) {
                        // 其他设备可能响应搜索，但不提供 AVTransport。
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // 继续等待电视响应。
                }
            }
        } catch (Exception e) {
            if (!closed) error = e.getMessage();
        } finally {
            socket = null;
            if (lock != null && lock.isHeld()) lock.release();
            String result = error;
            if (!closed) main.post(() -> {
                if (!closed) listener.onComplete(result);
            });
        }
    }

    private static String header(String response, String name) {
        for (String line : response.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && name.equals(line.substring(0, colon).trim().toLowerCase(Locale.US))) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static Device describe(URL location) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) location.openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) return null;
            byte[] bytes = new byte[256 * 1024];
            int length = 0;
            try (java.io.InputStream input = connection.getInputStream()) {
                while (length < bytes.length) {
                    int count = input.read(bytes, length, bytes.length - length);
                    if (count < 0) break;
                    length += count;
                }
                if (input.read() != -1) return null;
            }
            String descriptor = new String(bytes, 0, length, StandardCharsets.UTF_8);
            if (descriptor.indexOf('\0') >= 0 || descriptor.contains("<!DOCTYPE")
                    || descriptor.contains("<!ENTITY")) return null;
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(bytes, 0, length));
            String name = text(doc.getDocumentElement(), "friendlyName");
            NodeList services = doc.getElementsByTagNameNS("*", "service");
            for (int i = 0; i < services.getLength(); i++) {
                Element service = (Element) services.item(i);
                String type = text(service, "serviceType");
                String path = text(service, "controlURL");
                if (type != null && type.startsWith("urn:schemas-upnp-org:service:AVTransport:")
                        && path != null && !path.isEmpty()) {
                    String base = text(doc.getDocumentElement(), "URLBase");
                    URL control = new URL(base != null && !base.isEmpty() ? new URL(base) : location,
                            path);
                    if (!"http".equalsIgnoreCase(control.getProtocol())) continue;
                    return new Device(name == null || name.isEmpty() ? location.getHost() : name,
                            control.toString(), type);
                }
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }

    private static String text(Element element, String tag) {
        NodeList children = element.getElementsByTagNameNS("*", tag);
        Node node = children.item(0);
        return node == null ? null : node.getTextContent().trim();
    }
}
