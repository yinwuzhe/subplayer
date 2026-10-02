package com.subplayer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CastService extends Service {
    static final String ACTION_START = "com.subplayer.app.cast.START";
    static final String ACTION_PLAY = "com.subplayer.app.cast.PLAY";
    static final String ACTION_PAUSE = "com.subplayer.app.cast.PAUSE";
    static final String ACTION_STOP = "com.subplayer.app.cast.STOP";
    static final String ACTION_SEEK = "com.subplayer.app.cast.SEEK";
    static final String ACTION_STATUS = "com.subplayer.app.cast.STATUS";
    static final String EXTRA_URI = "uri";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_URL = "url";
    static final String EXTRA_TYPE = "type";
    static final String EXTRA_SECONDS = "seconds";
    static final String EXTRA_ACTIVE = "active";
    static final String EXTRA_PLAYING = "playing";
    static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL = "cast_playback";
    private static volatile boolean active;
    private static volatile boolean playing;
    private static volatile String deviceName;
    private static volatile Uri castingVideo;

    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private VideoHttpServer server;
    private DlnaController controller;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    static boolean isActive() { return active; }
    static boolean isPlaying() { return playing; }
    static String currentDevice() { return deviceName; }
    static Uri currentVideo() { return castingVideo; }

    static void send(Context context, String action) {
        context.startService(new Intent(context, CastService.class).setAction(action));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            deviceName = intent.getStringExtra(EXTRA_NAME);
            active = true;
            playing = false;
            ensureForeground("正在连接电视…");
            Uri uri = intent.getParcelableExtra(EXTRA_URI);
            castingVideo = uri;
            String url = intent.getStringExtra(EXTRA_URL);
            String type = intent.getStringExtra(EXTRA_TYPE);
            String name = deviceName;
            queue.execute(() -> startCast(uri, url, type, name));
        } else {
            int seconds = intent.getIntExtra(EXTRA_SECONDS, 0);
            queue.execute(() -> control(action, seconds));
        }
        return START_NOT_STICKY;
    }

    private void startCast(Uri uri, String url, String type, String name) {
        try {
            if (controller != null) {
                try { controller.stop(); } catch (Exception ignored) { }
            }
            closeStream();
            if (uri == null || url == null || type == null) throw new Exception("视频或电视地址无效");
            controller = new DlnaController(url, type);
            server = new VideoHttpServer(this, uri);
            URL endpoint = new URL(url);
            String address;
            try (DatagramSocket route = new DatagramSocket()) {
                route.connect(new InetSocketAddress(endpoint.getHost(),
                        endpoint.getPort() > 0 ? endpoint.getPort() : 80));
                address = route.getLocalAddress().getHostAddress();
            }
            if (address == null || address.equals("0.0.0.0") || address.contains(":"))
                throw new Exception("无法确定手机的 Wi-Fi 地址");
            String mediaUrl = "http://" + address + ":" + server.getPort() + server.getPath();
            controller.setVideo(mediaUrl, server.getTitle(), server.getMime());
            controller.play();
            acquireLocks();
            deviceName = name;
            active = true;
            playing = true;
            ensureForeground("正在电视上播放");
            report("已投屏到 " + name);
        } catch (Exception e) {
            android.util.Log.e("CastService", "投屏失败", e);
            if (controller != null) {
                try { controller.stop(); } catch (Exception ignored) { }
            }
            closeStream();
            controller = null;
            active = false;
            playing = false;
            castingVideo = null;
            report("投屏失败：" + e.getMessage());
            stopForegroundCompat();
            stopSelf();
        }
    }

    private void control(String action, int seconds) {
        if (ACTION_STOP.equals(action)) {
            if (controller != null) {
                try { controller.stop(); } catch (Exception ignored) { }
            }
            controller = null;
            closeStream();
            active = false;
            playing = false;
            castingVideo = null;
            report("已结束投屏");
            stopForegroundCompat();
            stopSelf();
            return;
        }
        if (controller == null) return;
        try {
            String message;
            if (ACTION_PLAY.equals(action)) {
                controller.play();
                playing = true;
                message = "电视继续播放";
            } else if (ACTION_PAUSE.equals(action)) {
                controller.pause();
                playing = false;
                message = "电视已暂停";
            } else if (ACTION_SEEK.equals(action)) {
                message = "电视已跳转到 " + controller.seekRelative(seconds);
            } else {
                return;
            }
            ensureForeground(playing ? "正在电视上播放" : "电视已暂停");
            report(message);
        } catch (Exception e) {
            report("电视控制失败：" + e.getMessage());
        }
    }

    private void acquireLocks() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "subplayer:cast-wifi");
            wifiLock.acquire();
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "subplayer:cast-cpu");
            wakeLock.acquire();
        }
    }

    private void closeStream() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (wifiLock != null) {
            if (wifiLock.isHeld()) wifiLock.release();
            wifiLock = null;
        }
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
    }

    private void report(String message) {
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_ACTIVE, active)
                .putExtra(EXTRA_PLAYING, playing)
                .putExtra(EXTRA_NAME, deviceName)
                .putExtra(EXTRA_MESSAGE, message));
    }

    private void ensureForeground(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "电视投屏", NotificationManager.IMPORTANCE_LOW));
        }
        Intent stop = new Intent(this, CastService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent openIntent = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(deviceName == null ? "电视投屏" : "投屏到 " + deviceName)
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(0, "结束投屏", stopIntent)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(101, notification);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @Override
    public void onDestroy() {
        queue.shutdownNow();
        closeStream();
        active = false;
        playing = false;
        castingVideo = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
