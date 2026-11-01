package com.voiceswipe.assistant.service;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import com.voiceswipe.assistant.R;
import com.voiceswipe.assistant.core.AppLog;

public final class VoiceTileService extends TileService {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        AppLog.info(this, "已添加一键启动快捷开关");
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (VoiceCommandService.isRunning()) {
            if (!QuickTileSupport.isAccessibilityEnabled(this)) {
                Toast.makeText(this, "请开启无障碍服务以完成一键启动", Toast.LENGTH_LONG).show();
                QuickTileSupport.openAccessibilitySettings(this);
            } else {
                VoiceCommandService.stop(this);
                Toast.makeText(this, "语音监听已停止", Toast.LENGTH_SHORT).show();
            }
            updateTile();
        } else {
            startVoiceAndAccessibility();
        }
        mainHandler.postDelayed(this::updateTile, 1200L);
    }

    private void startVoiceAndAccessibility() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先在应用中授予麦克风权限", Toast.LENGTH_SHORT).show();
            QuickTileSupport.openMainActivity(this);
            return;
        }
        // Android blocks microphone foreground services launched from background.
        // The transparent trampoline activity establishes a visible app state first.
        QuickTileSupport.openQuickLaunchActivity(this);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean running = VoiceCommandService.isRunning();
        boolean accessibilityEnabled = QuickTileSupport.isAccessibilityEnabled(this);
        boolean fullyReady = running && accessibilityEnabled;

        tile.setState(fullyReady ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_voice_label));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (fullyReady) {
                tile.setSubtitle(getString(R.string.tile_voice_on));
            } else if (running) {
                tile.setSubtitle(getString(R.string.tile_voice_need_accessibility));
            } else {
                tile.setSubtitle(getString(R.string.tile_voice_off));
            }
        }
        tile.updateTile();
    }
}
