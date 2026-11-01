package com.voiceswipe.assistant.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import com.voiceswipe.assistant.service.QuickTileSupport;
import com.voiceswipe.assistant.service.VoiceCommandService;

/** Invisible foreground trampoline for starting a microphone FGS from a Quick Settings tile. */
public final class QuickLaunchActivity extends Activity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (started) {
            return;
        }
        started = true;
        mainHandler.postDelayed(this::startVoiceAndFinish, 250L);
    }

    private void startVoiceAndFinish() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先授予麦克风权限", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        boolean accessibilityEnabled = QuickTileSupport.isAccessibilityEnabled(this);
        boolean startedService = VoiceCommandService.start(this);
        mainHandler.postDelayed(() -> {
            boolean running = VoiceCommandService.isRunning();
            if (!startedService || !running) {
                Toast.makeText(this, "请打开应用后重试启动", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, MainActivity.class));
            } else if (!accessibilityEnabled) {
                Toast.makeText(this, "请开启无障碍服务以完成一键启动", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } else {
                Toast.makeText(this, "语音与无障碍已就绪", Toast.LENGTH_SHORT).show();
            }
            finish();
        }, 700L);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
