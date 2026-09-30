package com.subplayer.app;

import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
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
    private TextView icFullscreen;
    private TextView labelFullscreen;
    private boolean isFullscreen = false;

    private final float[] speeds = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f};
    private final String[] speedLabels = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x", "3.0x"};

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
        View btnFullscreen = findViewById(R.id.btn_fullscreen);
        btnExitFullscreen = findViewById(R.id.btn_exit_fullscreen);

        initPlayer();
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility ->
                btnExitFullscreen.setVisibility(isFullscreen && visibility == View.VISIBLE
                        ? View.VISIBLE : View.GONE));
        btnExitFullscreen.setOnClickListener(v -> exitFullscreen());

        btnOpen.setOnClickListener(v ->
                openDocument.launch(new String[]{"video/*", "*/*"}));
        btnSpeed.setOnClickListener(v -> showSpeedDialog());
        btnSubtitle.setOnClickListener(v -> showSubtitleDialog());
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
        if (hintCard != null) hintCard.setVisibility(View.GONE);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.play();
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
        btnExitFullscreen.setVisibility(View.GONE);
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
    protected void onStop() {
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