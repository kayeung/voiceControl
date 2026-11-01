package com.voiceswipe.assistant.ui;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.voiceswipe.assistant.R;
import com.voiceswipe.assistant.core.AppLog;
import com.voiceswipe.assistant.core.AppSettings;
import com.voiceswipe.assistant.core.CommandParser;
import com.voiceswipe.assistant.core.SwipeDirection;
import com.voiceswipe.assistant.service.SwipeAccessibilityService;
import com.voiceswipe.assistant.service.VoiceCommandService;
import com.voiceswipe.assistant.service.VoiceTileService;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 40;

    private AppSettings settings;
    private TextView voiceStatus;
    private TextView accessibilityStatus;
    private TextView orientationStatus;
    private TextView logsText;
    private TextView distanceValue;
    private TextView durationValue;
    private Button toggleListeningButton;
    private Button openAccessibilityButton;
    private Switch offlineEngineSwitch;
    private Switch wakeWordSwitch;
    private EditText wakeWordInput;
    private EditText upCommandsInput;
    private EditText downCommandsInput;
    private EditText leftCommandsInput;
    private EditText rightCommandsInput;
    private SeekBar distanceSeekBar;
    private SeekBar durationSeekBar;
    private boolean receiverRegistered;
    private boolean startAfterPermissions;

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStatus();
            refreshLogs();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        configureWindowInsets();
        settings = new AppSettings(this);
        bindViews();
        configureInputs();
        configureButtons();
        loadSettings();
        refreshStatus();
        refreshLogs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AppLog.ACTION_LOG_UPDATED);
        filter.addAction(VoiceCommandService.ACTION_STATUS_CHANGED);
        filter.addAction(SwipeAccessibilityService.ACTION_STATUS_CHANGED);
        ContextCompat.registerReceiver(
                this, updateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        offlineEngineSwitch.setChecked(settings.isOfflinePreferred());
        refreshStatus();
        refreshLogs();
    }

    @Override
    protected void onPause() {
        if (receiverRegistered) {
            unregisterReceiver(updateReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST || !startAfterPermissions) {
            return;
        }
        startAfterPermissions = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要麦克风权限才能识别语音指令", Toast.LENGTH_LONG).show();
            return;
        }
        startListeningIfReady();
    }

    private void configureWindowInsets() {
        View root = findViewById(R.id.rootScroll);
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    left + insets.getSystemWindowInsetLeft(),
                    top + insets.getSystemWindowInsetTop(),
                    right + insets.getSystemWindowInsetRight(),
                    bottom + insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
    }

    private void bindViews() {
        voiceStatus = findViewById(R.id.voiceStatus);
        accessibilityStatus = findViewById(R.id.accessibilityStatus);
        orientationStatus = findViewById(R.id.orientationStatus);
        logsText = findViewById(R.id.logsText);
        distanceValue = findViewById(R.id.distanceValue);
        durationValue = findViewById(R.id.durationValue);
        toggleListeningButton = findViewById(R.id.toggleListeningButton);
        openAccessibilityButton = findViewById(R.id.openAccessibilityButton);
        offlineEngineSwitch = findViewById(R.id.offlineEngineSwitch);
        wakeWordSwitch = findViewById(R.id.wakeWordSwitch);
        wakeWordInput = findViewById(R.id.wakeWordInput);
        upCommandsInput = findViewById(R.id.upCommandsInput);
        downCommandsInput = findViewById(R.id.downCommandsInput);
        leftCommandsInput = findViewById(R.id.leftCommandsInput);
        rightCommandsInput = findViewById(R.id.rightCommandsInput);
        distanceSeekBar = findViewById(R.id.distanceSeekBar);
        durationSeekBar = findViewById(R.id.durationSeekBar);
    }

    private void configureInputs() {
        distanceSeekBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                distanceValue.setText((progress + 20) + "%");
            }
        });
        durationSeekBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                durationValue.setText((100 + progress * 50) + "ms");
            }
        });
        wakeWordSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                wakeWordInput.setEnabled(isChecked));
    }

    private void configureButtons() {
        toggleListeningButton.setOnClickListener(view -> {
            if (VoiceCommandService.isRunning()) {
                VoiceCommandService.stop(this);
            } else {
                startListeningIfReady();
            }
            refreshStatus();
        });
        openAccessibilityButton.setOnClickListener(view -> openAccessibilitySettings());
        findViewById(R.id.addVoiceTileButton).setOnClickListener(view ->
                requestQuickTile(VoiceTileService.class, R.string.tile_voice_label,
                        R.drawable.ic_tile_voice));
        findViewById(R.id.saveSettingsButton).setOnClickListener(view -> saveSettings());
        findViewById(R.id.resetSettingsButton).setOnClickListener(view -> resetSettings());
        findViewById(R.id.clearLogsButton).setOnClickListener(view -> AppLog.clear(this));
        findViewById(R.id.testUpButton).setOnClickListener(view -> testSwipe(SwipeDirection.UP));
        findViewById(R.id.testDownButton).setOnClickListener(view -> testSwipe(SwipeDirection.DOWN));
        findViewById(R.id.testLeftButton).setOnClickListener(view -> testSwipe(SwipeDirection.LEFT));
        findViewById(R.id.testRightButton).setOnClickListener(view -> testSwipe(SwipeDirection.RIGHT));
    }

    private void loadSettings() {
        offlineEngineSwitch.setChecked(settings.isOfflinePreferred());
        wakeWordSwitch.setChecked(settings.isWakeWordRequired());
        wakeWordInput.setText(settings.getWakeWord());
        wakeWordInput.setEnabled(settings.isWakeWordRequired());
        upCommandsInput.setText(settings.getUpCommands());
        downCommandsInput.setText(settings.getDownCommands());
        leftCommandsInput.setText(settings.getLeftCommands());
        rightCommandsInput.setText(settings.getRightCommands());
        distanceSeekBar.setProgress(settings.getDistancePercent() - 20);
        durationSeekBar.setProgress((settings.getDurationMs() - 100) / 50);
        distanceValue.setText(settings.getDistancePercent() + "%");
        durationValue.setText(settings.getDurationMs() + "ms");
    }

    private boolean saveSettings() {
        String up = upCommandsInput.getText().toString();
        String down = downCommandsInput.getText().toString();
        String left = leftCommandsInput.getText().toString();
        String right = rightCommandsInput.getText().toString();
        if (!CommandParser.hasCommand(up) || !CommandParser.hasCommand(down)
                || !CommandParser.hasCommand(left) || !CommandParser.hasCommand(right)) {
            Toast.makeText(this, "每个方向至少需要保留一条指令", Toast.LENGTH_LONG).show();
            return false;
        }
        String wakeWord = wakeWordInput.getText().toString();
        if (wakeWordSwitch.isChecked() && CommandParser.normalize(wakeWord).isEmpty()) {
            Toast.makeText(this, "启用唤醒词时，唤醒词不能为空", Toast.LENGTH_LONG).show();
            return false;
        }

        settings.saveCommands(up, down, left, right);
        settings.saveWakeWord(wakeWordSwitch.isChecked(), wakeWord);
        settings.setOfflinePreferred(offlineEngineSwitch.isChecked());
        settings.saveGestureParameters(distanceSeekBar.getProgress() + 20,
                100 + durationSeekBar.getProgress() * 50);
        AppLog.info(this, "语音指令、识别引擎与手势参数已保存");
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void resetSettings() {
        settings.saveCommands(AppSettings.DEFAULT_UP, AppSettings.DEFAULT_DOWN,
                AppSettings.DEFAULT_LEFT, AppSettings.DEFAULT_RIGHT);
        settings.saveWakeWord(false, AppSettings.DEFAULT_WAKE_WORD);
        settings.setOfflinePreferred(false);
        settings.saveGestureParameters(45, 300);
        loadSettings();
        AppLog.info(this, "已恢复默认设置");
        Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show();
    }

    private void startListeningIfReady() {
        if (!SwipeAccessibilityService.isConnected()) {
            boolean enabledInSettings = isAccessibilityEnabledInSettings();
            Toast.makeText(this, enabledInSettings
                            ? "无障碍已启用但尚未连接，请在系统设置中关闭再重新开启本服务"
                            : "请先启用声控滑动助手的无障碍服务",
                    Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }
        List<String> requiredPermissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!requiredPermissions.isEmpty()) {
            startAfterPermissions = true;
            requestPermissions(requiredPermissions.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        if (!saveSettings()) {
            return;
        }
        VoiceCommandService.start(this);
    }

    private boolean isAccessibilityEnabledInSettings() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null || !manager.isEnabled()) {
            return false;
        }
        String serviceId = getPackageName() + "/" + SwipeAccessibilityService.class.getName();
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (serviceId.equals(info.getId())) {
                return true;
            }
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException exception) {
            Toast.makeText(this, "请在系统设置中手动启用无障碍服务", Toast.LENGTH_LONG).show();
        }
    }

    private void requestQuickTile(Class<?> tileServiceClass, int labelRes, int iconRes) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            showManualTileHint();
            return;
        }
        StatusBarManager statusBarManager = getSystemService(StatusBarManager.class);
        if (statusBarManager == null) {
            showManualTileHint();
            return;
        }
        try {
            statusBarManager.requestAddTileService(
                    new ComponentName(this, tileServiceClass),
                    getString(labelRes),
                    Icon.createWithResource(this, iconRes),
                    getMainExecutor(),
                    result -> {
                        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                            Toast.makeText(this, "已添加下拉快捷开关", Toast.LENGTH_SHORT).show();
                        } else if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) {
                            Toast.makeText(this, "已取消添加", Toast.LENGTH_SHORT).show();
                        } else {
                            showManualTileHint();
                        }
                    });
        } catch (RuntimeException exception) {
            showManualTileHint();
        }
    }

    private void showManualTileHint() {
        Toast.makeText(this,
                "请下拉打开控制中心，进入编辑模式，添加“声控滑动”或“声控无障碍”",
                Toast.LENGTH_LONG).show();
    }

    private void testSwipe(SwipeDirection direction) {
        if (!SwipeAccessibilityService.isConnected()) {
            Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!saveSettings()) {
            return;
        }
        SwipeAccessibilityService.requestSwipe(direction, settings);
    }

    private void refreshStatus() {
        boolean running = VoiceCommandService.isRunning();
        voiceStatus.setText("语音：" + VoiceCommandService.getCurrentStatus());
        voiceStatus.setTextColor(Color.parseColor(running ? "#15803D" : "#667085"));
        toggleListeningButton.setText(running ? "停止语音监听" : "开始语音监听");

        boolean accessibilityConnected = SwipeAccessibilityService.isConnected();
        boolean accessibilityEnabled = accessibilityConnected || isAccessibilityEnabledInSettings();
        if (accessibilityConnected) {
            accessibilityStatus.setText("无障碍：已连接，可执行手势");
            accessibilityStatus.setTextColor(Color.parseColor("#15803D"));
            openAccessibilityButton.setText("管理无障碍服务");
        } else if (accessibilityEnabled) {
            accessibilityStatus.setText("无障碍：系统已启用，应用尚未连接");
            accessibilityStatus.setTextColor(Color.parseColor("#B45309"));
            openAccessibilityButton.setText("重新启用无障碍服务");
        } else {
            accessibilityStatus.setText("无障碍：未启用");
            accessibilityStatus.setTextColor(Color.parseColor("#B45309"));
            openAccessibilityButton.setText("启用无障碍服务");
        }

        String orientation = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE ? "横屏" : "竖屏";
        orientationStatus.setText("屏幕：" + orientation + "，手势坐标自动适配");
    }

    private void refreshLogs() {
        logsText.setText(AppLog.getFormattedLogs());
    }

    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
