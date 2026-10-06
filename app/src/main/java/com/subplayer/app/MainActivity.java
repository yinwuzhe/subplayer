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

import android.database.Cursor;
import android.provider.OpenableColumns;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.analytics.AnalyticsListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends AppCompatActivity {

    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout topBar;
    private View hintCard;
    private View btnOpen;
    private View btnSubtitle;
    private View btnSpeed;
    private View btnAudio;
    private TextView audioStatus;
    private String audioDecoderName;
    private String audioWarning;
    private String lastAudioNotice;
    private Format selectedAudioFormat;
    private boolean restoringAudioTrack;
    private View btnExitFullscreen;
    private TextView castStatus;
    private Uri currentVideo;
    private DlnaDiscovery discovery;
    private boolean wasPlayingBeforeCast;
    private boolean isFullscreen = false;
    private final ExecutorService subtitleWorker = Executors.newSingleThreadExecutor();
    private File subtitleCache;
    private String pendingSubtitleLabel;
    private int mediaGeneration;
    private int subtitlePickerGeneration;
    private boolean subtitleImporting;
    private boolean resumeAfterSubtitlePicker;
    private boolean started;

    private final ActivityResultLauncher<String[]> openSubtitle =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (subtitlePickerGeneration != mediaGeneration || player == null || CastService.isActive()) {
                    resumeAfterSubtitlePicker = false;
                    return;
                }
                if (uri == null) resumeAfterSubtitleSelection();
                else importSubtitle(uri);
            });

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
        btnAudio = findViewById(R.id.btn_audio);
        audioStatus = findViewById(R.id.audio_status);
        btnAudio.setOnClickListener(v -> showAudioDialog());
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
        player = new ExoPlayer.Builder(this, new CompatibleRenderersFactory(this)).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        playerView.setPlayer(player);
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onAudioDecoderInitialized(EventTime eventTime, String decoderName,
                                                  long initializedTimestampMs, long initializationDurationMs) {
                audioDecoderName = decoderName;
                audioWarning = null;
                updateAudioStatus(player.getCurrentTracks());
            }

            @Override
            public void onAudioDecoderReleased(EventTime eventTime, String decoderName) {
                if (decoderName.equals(audioDecoderName)) audioDecoderName = null;
            }
        });
        if (!FfmpegLibrary.isAvailable()) {
            Toast.makeText(this, "软件音频解码库加载失败，部分音轨可能无声，请安装完整 APK", Toast.LENGTH_LONG).show();
        }
        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                restoreAudioSelection(tracks);
                updateAudioStatus(tracks);
                if (pendingSubtitleLabel == null) return;
                for (Tracks.Group group : tracks.getGroups()) {
                    if (group.getType() != C.TRACK_TYPE_TEXT) continue;
                    for (int i = 0; i < group.length; i++) {
                        if (!pendingSubtitleLabel.equals(group.getTrackFormat(i).label)) continue;
                        pendingSubtitleLabel = null;
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), i))
                                .build());
                        return;
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                ExoPlaybackException failure = error instanceof ExoPlaybackException
                        ? (ExoPlaybackException) error : null;
                String mime = failure != null && failure.rendererFormat != null
                        ? failure.rendererFormat.sampleMimeType : null;
                if (AudioTrackInfo.isAudioError(error.errorCode, mime,
                        failure == null ? null : failure.rendererName)) {
                    audioWarning = "音频解码或输出失败（" + error.getErrorCodeName()
                            + "），请点「音轨」切换或重试，并检查媒体音量/蓝牙输出";
                    updateAudioStatus(player.getCurrentTracks());
                    return;
                }
                boolean subtitleError = mime != null && MimeTypes.isText(mime);
                if (!subtitleError || subtitleCache == null || player.getCurrentMediaItem() == null) {
                    Toast.makeText(MainActivity.this, "播放失败：" + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
                    return;
                }
                MediaItem item = player.getCurrentMediaItem().buildUpon()
                        .setSubtitleConfigurations(Collections.emptyList()).build();
                File failed = subtitleCache;
                subtitleCache = null;
                pendingSubtitleLabel = null;
                long position = Math.max(0, player.getCurrentPosition());
                boolean play = player.getPlayWhenReady();
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT).build());
                player.setMediaItem(item, position);
                player.prepare();
                player.setPlayWhenReady(play);
                failed.delete();
                Toast.makeText(MainActivity.this, "加载外挂字幕后播放出错，已移除外挂字幕，请检查字幕文件", Toast.LENGTH_LONG).show();
            }

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
        mediaGeneration++;
        resumeAfterSubtitlePicker = false;
        subtitleImporting = false;
        pendingSubtitleLabel = null;
        File previous = subtitleCache;
        subtitleCache = null;
        currentVideo = uri;
        selectedAudioFormat = null;
        restoringAudioTrack = false;
        audioDecoderName = null;
        audioWarning = null;
        lastAudioNotice = null;
        audioStatus.setVisibility(View.GONE);
        if (hintCard != null) hintCard.setVisibility(View.GONE);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).build());
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.play();
        if (previous != null) previous.delete();
    }

    private void chooseSubtitle() {
        if (player == null || player.getCurrentMediaItem() == null) {
            Toast.makeText(this, "请先打开视频", Toast.LENGTH_SHORT).show();
            return;
        }
        if (CastService.isActive() || subtitleImporting) return;
        subtitlePickerGeneration = mediaGeneration;
        resumeAfterSubtitlePicker = player.getPlayWhenReady();
        openSubtitle.launch(new String[]{"*/*"});
    }

    private void resumeAfterSubtitleSelection() {
        if (started && resumeAfterSubtitlePicker && !subtitleImporting && !CastService.isActive()) {
            resumeAfterSubtitlePicker = false;
            player.play();
        }
    }

    private void importSubtitle(Uri uri) {
        subtitleImporting = true;
        int generation = mediaGeneration;
        Toast.makeText(this, "正在读取字幕…", Toast.LENGTH_SHORT).show();
        subtitleWorker.execute(() -> {
            File cache = null;
            try {
                String name = uri.getLastPathSegment();
                try (Cursor cursor = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                }
                if (name == null) throw new java.io.IOException("无法识别字幕文件名");
                SubtitleFile subtitle;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    subtitle = SubtitleFile.read(input, name);
                }
                cache = File.createTempFile("external-subtitle-", "." + subtitle.extension, getCacheDir());
                try (FileOutputStream output = new FileOutputStream(cache)) { output.write(subtitle.utf8); }
                File ready = cache;
                runOnUiThread(() -> {
                    if (player == null || isDestroyed() || generation != mediaGeneration || CastService.isActive()) {
                        ready.delete();
                        if (generation == mediaGeneration) {
                            subtitleImporting = false;
                            resumeAfterSubtitlePicker = false;
                        }
                        return;
                    }
                    MediaItem current = player.getCurrentMediaItem();
                    if (current == null) { ready.delete(); subtitleImporting = false; return; }
                    long position = Math.max(0, player.getCurrentPosition());
                    boolean play = resumeAfterSubtitlePicker || player.getPlayWhenReady();
                    PlaybackParameters speed = player.getPlaybackParameters();
                    File previous = subtitleCache;
                    subtitleCache = ready;
                    pendingSubtitleLabel = "外挂 · " + subtitle.name;
                    MediaItem.SubtitleConfiguration configuration = new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(ready))
                            .setId("external-subtitle").setMimeType(subtitle.mime)
                            .setLabel(pendingSubtitleLabel).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build());
                    player.setMediaItem(current.buildUpon().setSubtitleConfigurations(Collections.singletonList(configuration)).build(), position);
                    player.prepare();
                    player.setPlaybackParameters(speed);
                    player.setPlayWhenReady(play && started);
                    resumeAfterSubtitlePicker = play && !started;
                    subtitleImporting = false;
                    if (previous != null) previous.delete();
                    Toast.makeText(this, "已加载字幕：" + subtitle.name, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                if (cache != null) cache.delete();
                runOnUiThread(() -> {
                    if (player == null || isDestroyed() || generation != mediaGeneration) return;
                    subtitleImporting = false;
                    Toast.makeText(this, "字幕加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    resumeAfterSubtitleSelection();
                });
            }
        });
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
        btnAudio.setEnabled(!active);
        btnAudio.setAlpha(active ? 0.4f : 1f);
        if (active) audioStatus.setVisibility(View.GONE);
        else updateAudioStatus(player.getCurrentTracks());
    }

    private void restoreAudioSelection(Tracks tracks) {
        if (selectedAudioFormat == null || restoringAudioTrack) return;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                if (!AudioTrackInfo.sameTrack(selectedAudioFormat, group.getTrackFormat(i))
                        || !group.isTrackSupported(i)) continue;
                if (!group.isTrackSelected(i)) {
                    restoringAudioTrack = true;
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), i)).build());
                    restoringAudioTrack = false;
                }
                return;
            }
        }
    }

    private void updateAudioStatus(Tracks tracks) {
        if (audioStatus == null || CastService.isActive()) return;
        int count = 0;
        boolean supported = false;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            count += group.length;
            for (int i = 0; i < group.length; i++) supported |= group.isTrackSupported(i);
        }
        String message = audioWarning;
        if (message == null && count > 0 && !supported) {
            message = "当前音轨暂不支持解码，请点「音轨」查看编码或切换；投屏仍由电视解码";
        }
        if (message == null && !FfmpegLibrary.isAvailable() && currentVideo != null) {
            message = "软件音频解码库未加载，部分视频可能无声，请重新安装完整 APK";
        }
        audioStatus.setVisibility(message == null ? View.GONE : View.VISIBLE);
        if (message != null) {
            audioStatus.setText(message);
            if (!message.equals(lastAudioNotice)) {
                lastAudioNotice = message;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showAudioDialog() {
        if (player == null || CastService.isActive()) return;
        List<String> labels = new ArrayList<>();
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        labels.add("自动选择兼容音轨");
        groups.add(null);
        indices.add(-1);
        int selected = selectedAudioFormat == null ? 0 : -1;
        int count = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                String label = AudioTrackInfo.label(format, ++count);
                if (group.isTrackSelected(i)) {
                    label += " · 当前";
                    if (selectedAudioFormat != null) selected = labels.size();
                }
                if (!group.isTrackSupported(i)) label += " · 不支持";
                labels.add(label);
                groups.add(group);
                indices.add(i);
            }
        }
        String engine = audioDecoderName == null ? "尚未启动音频解码器"
                : audioDecoderName.startsWith("ffmpeg") ? "FFmpeg 软件解码（PCM 输出）"
                : "系统解码：" + audioDecoderName;
        if (count == 0) labels.add("没有检测到音轨，请先打开视频并等待加载");
        new AlertDialog.Builder(this)
                .setTitle("本机音轨 · " + engine)
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
                    if (which >= groups.size()) return;
                    if (which == 0) {
                        selectedAudioFormat = null;
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build());
                    } else {
                        Tracks.Group group = groups.get(which);
                        int index = indices.get(which);
                        if (!group.isTrackSupported(index)) {
                            Toast.makeText(this, "此音轨不受支持，请选择其他音轨", Toast.LENGTH_LONG).show();
                            return;
                        }
                        selectedAudioFormat = group.getTrackFormat(index);
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), index)).build());
                    }
                    audioWarning = null;
                    lastAudioNotice = null;
                    if (player.getPlayerError() != null) {
                        player.prepare();
                        player.play();
                    }
                    updateAudioStatus(player.getCurrentTracks());
                    dialog.dismiss();
                })
                .setPositiveButton("重试音频", (dialog, which) -> {
                    if (player.getCurrentMediaItem() == null) return;
                    audioWarning = null;
                    lastAudioNotice = null;
                    player.prepare();
                    player.play();
                    updateAudioStatus(player.getCurrentTracks());
                })
                .setNeutralButton("解码信息", (dialog, which) -> showAudioInformation())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showAudioInformation() {
        String details = "软件解码库：" + (FfmpegLibrary.isAvailable() ? FfmpegLibrary.getVersion() : "加载失败")
                + "\n当前解码器：" + (audioDecoderName == null ? "未启动" : audioDecoderName)
                + "\n本机音频解码为 PCM 后播放，不依赖电视的解码能力。"
                + "\n若仍无声，请检查媒体音量、蓝牙耳机及文件是否有有效音轨。"
                + "\n音轨选择不影响电视，电视播放时需在电视端切换音轨。";
        new AlertDialog.Builder(this).setTitle("音频兼容信息").setMessage(details)
                .setPositiveButton("确定", null)
                .setNeutralButton("开源许可", (dialog, which) -> {
                    try (InputStream input = getAssets().open("licenses/audio-decoder-notice.txt")) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int size;
                        while ((size = input.read(buffer)) != -1) out.write(buffer, 0, size);
                        new AlertDialog.Builder(this).setTitle("音频解码开源许可")
                                .setMessage(out.toString("UTF-8")).setPositiveButton("确定", null).show();
                    } catch (java.io.IOException e) {
                        Toast.makeText(this, "无法读取许可信息", Toast.LENGTH_SHORT).show();
                    }
                }).show();
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

        String[] arr = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("字幕（本地播放）")
                .setPositiveButton("加载外部字幕…", (dialog, which) -> chooseSubtitle())
                .setNegativeButton("取消", null)
                .setItems(arr, (dialog, which) -> {
                    pendingSubtitleLabel = null;
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
        started = true;
        resumeAfterSubtitleSelection();
        IntentFilter filter = new IntentFilter(CastService.ACTION_STATUS);
        androidx.core.content.ContextCompat.registerReceiver(this, castReceiver, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        if (CastService.isActive()) currentVideo = CastService.currentVideo();
        updateCastUi(CastService.isActive(), null);
    }

    @Override
    protected void onStop() {
        started = false;
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
        mediaGeneration++;
        subtitleWorker.shutdownNow();
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
        if (subtitleCache != null) subtitleCache.delete();
    }
}