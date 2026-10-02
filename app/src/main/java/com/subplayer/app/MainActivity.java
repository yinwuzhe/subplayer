package com.subplayer.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends AppCompatActivity {

    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout topBar;
    private View hintCard;
    private View btnOpen;
    private View btnSubtitle;
    private View btnSpeed;
    private View btnExitFullscreen;
    private TextView castStatus;
    private Uri currentVideo;
    private DlnaDiscovery discovery;
    private boolean wasPlayingBeforeCast;
    private boolean isFullscreen = false;

    private final BroadcastReceiver castReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean active = intent.getBooleanExtra(CastService.EXTRA_ACTIVE, false);
            String message = intent.getStringExtra(CastService.EXTRA_MESSAGE);
            updateCastUi(active, message);
            if (!active && message != null && message.startsWith("投屏失败")
                    && wasPlayingBeforeCast && player != null) player.play();
            if (!active) wasPlayingBeforeCast = false;
            if (message != null) Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

    private final float[] speeds = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f};
    private final String[] speedLabels = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x", "3.0x"};

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });

    private final ActivityResultLauncher<String[]> openDocument =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {
                    }
                    playUri(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.player_view);
        topBar = findViewById(R.id.top_bar);
        hintCard = findViewById(R.id.hint_card);
        btnOpen = findViewById(R.id.btn_open);
        btnSpeed = findViewById(R.id.btn_speed);
        btnSubtitle = findViewById(R.id.btn_subtitle);
        castStatus = findViewById(R.id.cast_status);
        View btnCast = findViewById(R.id.btn_cast);
        View btnFullscreen = findViewById(R.id.btn_fullscreen);
        btnExitFullscreen = findViewById(R.id.btn_exit_fullscreen);

        initPlayer();
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility ->
                btnExitFullscreen.setVisibility(isFullscreen
                        && (CastService.isActive() || visibility == View.VISIBLE)
                        ? View.VISIBLE : View.GONE));
        btnExitFullscreen.setOnClickListener(v -> exitFullscreen());

        btnOpen.setOnClickListener(v ->
                openDocument.launch(new String[]{"video/*", "*/*"}));
        btnSpeed.setOnClickListener(v -> showSpeedDialog());
        btnSubtitle.setOnClickListener(v -> showSubtitleDialog());
        btnCast.setOnClickListener(v -> {
            if (CastService.isActive()) showCastControls();
            else showDiscoveryDialog();
        });
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());

        // 支持从文件管理器"用其他应用打开"
        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data != null) {
            playUri(data);
        }
    }

    private void initPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onEvents(Player currentPlayer, Player.Events events) {
                // 播放和缓冲时常亮，暂停、结束或出错时恢复系统息屏策略。
                int state = currentPlayer.getPlaybackState();
                boolean keepAwake = currentPlayer.getPlayWhenReady()
                        && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
                        && currentPlayer.getPlayerError() == null;
                playerView.setKeepScreenOn(keepAwake);
            }
        });
        player.setPlayWhenReady(true);
    }

    private void playUri(Uri uri) {
        if (CastService.isActive()) CastService.send(this, CastService.ACTION_STOP);
        currentVideo = uri;
        if (hintCard != null) hintCard.setVisibility(View.GONE);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.play();
    }

    private void showDiscoveryDialog() {
        if (currentVideo == null) {
            Toast.makeText(this, "请先打开要投屏的视频", Toast.LENGTH_SHORT).show();
            return;
        }
        List<DlnaDiscovery.Device> devices = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择电视（DLNA）")
                .setAdapter(adapter, (whichDialog, which) -> {
                    if (which >= devices.size()) return;
                    DlnaDiscovery.Device device = devices.get(which);
                    wasPlayingBeforeCast = player.isPlaying();
                    player.pause();
                    updateCastUi(true, "正在连接 " + device.name + "…");
                    Intent intent = new Intent(this, CastService.class)
                            .setAction(CastService.ACTION_START)
                            .putExtra(CastService.EXTRA_URI, currentVideo)
                            .putExtra(CastService.EXTRA_NAME, device.name)
                            .putExtra(CastService.EXTRA_URL, device.controlUrl)
                            .putExtra(CastService.EXTRA_TYPE, device.serviceType);
                    try {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
                        else startService(intent);
                        if (Build.VERSION.SDK_INT >= 33
                                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                        }
                    } catch (RuntimeException e) {
                        updateCastUi(false, null);
                        if (wasPlayingBeforeCast) player.play();
                        Toast.makeText(this, "无法启动投屏服务：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setPositiveButton("重新搜索", null)
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            if (discovery != null) {
                discovery.close();
                discovery = null;
            }
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                searchDevices(adapter, devices));
        searchDevices(adapter, devices);
    }

    private void searchDevices(ArrayAdapter<String> adapter, List<DlnaDiscovery.Device> devices) {
        if (discovery != null) discovery.close();
        devices.clear();
        adapter.clear();
        adapter.add("正在搜索同一 Wi-Fi 下的电视…");
        discovery = new DlnaDiscovery(this);
        discovery.start(new DlnaDiscovery.Listener() {
            @Override
            public void onDevice(DlnaDiscovery.Device device) {
                for (DlnaDiscovery.Device existing : devices) {
                    if (existing.controlUrl.equals(device.controlUrl)) return;
                }
                if (devices.isEmpty()) adapter.clear();
                devices.add(device);
                adapter.add(device.name);
            }

            @Override
            public void onComplete(String error) {
                if (devices.isEmpty()) {
                    adapter.clear();
                    adapter.add(error == null ? "未找到 DLNA 电视，请检查同一 Wi-Fi 及电视投屏设置"
                            : "搜索失败：" + error);
                }
            }
        });
    }

    private void showCastControls() {
        String[] actions = {CastService.isPlaying() ? "暂停电视播放" : "继续电视播放",
                "快退 30 秒", "快进 30 秒", "更换电视", "结束投屏"};
        new AlertDialog.Builder(this)
                .setTitle("正在投屏到 " + CastService.currentDevice())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) CastService.send(this,
                            CastService.isPlaying() ? CastService.ACTION_PAUSE : CastService.ACTION_PLAY);
                    else if (which == 1 || which == 2) startService(
                            new Intent(this, CastService.class).setAction(CastService.ACTION_SEEK)
                                    .putExtra(CastService.EXTRA_SECONDS, which == 1 ? -30 : 30));
                    else if (which == 3) showDiscoveryDialog();
                    else CastService.send(this, CastService.ACTION_STOP);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void updateCastUi(boolean active, String message) {
        castStatus.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) castStatus.setText(message == null
                ? "正在投屏到 " + CastService.currentDevice() : message);
        playerView.setUseController(!active);
        if (isFullscreen) btnExitFullscreen.setVisibility(active ? View.VISIBLE : View.GONE);
        btnSpeed.setEnabled(!active);
        btnSpeed.setAlpha(active ? 0.4f : 1f);
        btnSubtitle.setEnabled(!active);
        btnSubtitle.setAlpha(active ? 0.4f : 1f);
    }

    private void showSpeedDialog() {
        if (player == null) return;
        new AlertDialog.Builder(this)
                .setTitle("播放速度")
                .setItems(speedLabels, (dialog, which) -> {
                    player.setPlaybackParameters(new PlaybackParameters(speeds[which]));
                    Toast.makeText(this, "速度: " + speedLabels[which], Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showSubtitleDialog() {
        if (player == null) return;
        Tracks tracks = player.getCurrentTracks();

        List<String> labels = new ArrayList<>();
        List<TrackGroup> groups = new ArrayList<>();
        List<Integer> trackIndices = new ArrayList<>();

        labels.add("关闭字幕");
        groups.add(null);
        trackIndices.add(-1);

        int subCount = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            TrackGroup mediaGroup = group.getMediaTrackGroup();
            for (int i = 0; i < mediaGroup.length; i++) {
                subCount++;
                String lang = mediaGroup.getFormat(i).language;
                String label = mediaGroup.getFormat(i).label;
                String name = label != null ? label
                        : (lang != null ? lang : "字幕轨道 " + subCount);
                labels.add(name);
                groups.add(mediaGroup);
                trackIndices.add(i);
            }
        }

        if (subCount == 0) {
            Toast.makeText(this, "当前视频没有检测到内嵌字幕", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] arr = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("选择字幕轨道")
                .setItems(arr, (dialog, which) -> {
                    if (which == 0) {
                        player.setTrackSelectionParameters(
                                player.getTrackSelectionParameters().buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                        .build());
                        Toast.makeText(this, "已关闭字幕", Toast.LENGTH_SHORT).show();
                    } else {
                        TrackGroup g = groups.get(which);
                        int idx = trackIndices.get(which);
                        player.setTrackSelectionParameters(
                                player.getTrackSelectionParameters().buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(new TrackSelectionOverride(g, idx))
                                        .build());
                        Toast.makeText(this, "字幕: " + arr[which], Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen();
        } else {
            enterFullscreen();
        }
    }

    private void enterFullscreen() {
        isFullscreen = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        topBar.setVisibility(View.GONE);
        btnExitFullscreen.setVisibility(CastService.isActive() ? View.VISIBLE : View.GONE);
        playerView.hideController();
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void exitFullscreen() {
        isFullscreen = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        btnExitFullscreen.setVisibility(View.GONE);
        topBar.setVisibility(View.VISIBLE);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.show(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onBackPressed() {
        if (isFullscreen) {
            exitFullscreen();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getData() != null) playUri(intent.getData());
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(CastService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(castReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(castReceiver, filter);
        if (CastService.isActive()) currentVideo = CastService.currentVideo();
        updateCastUi(CastService.isActive(), null);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(castReceiver);
        if (discovery != null) {
            discovery.close();
            discovery = null;
        }
        super.onStop();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}