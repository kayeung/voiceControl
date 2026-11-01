package com.voiceswipe.assistant.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import com.voiceswipe.assistant.R;
import com.voiceswipe.assistant.core.AppLog;
import com.voiceswipe.assistant.core.AppSettings;
import com.voiceswipe.assistant.core.CommandParser;
import com.voiceswipe.assistant.core.SwipeDirection;
import com.voiceswipe.assistant.ui.MainActivity;

import java.util.ArrayList;
import java.util.Locale;

public final class VoiceCommandService extends Service implements RecognitionListener {
    public static final String ACTION_START = "com.voiceswipe.assistant.action.START";
    public static final String ACTION_STOP = "com.voiceswipe.assistant.action.STOP";
    public static final String ACTION_STATUS_CHANGED = "com.voiceswipe.assistant.VOICE_STATUS_CHANGED";

    private static final String TAG = "VoiceCommandService";
    private static final String CHANNEL_ID = "voice_listening";
    private static final int NOTIFICATION_ID = 1001;
    private static final long COMMAND_COOLDOWN_MS = 2000L;
    private static final long WATCHDOG_TIMEOUT_MS = 15000L;

    private static volatile boolean running = false;
    private static volatile String currentStatus = "未启动";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable restartRunnable = this::startListening;
    private final Runnable sessionWatchdog = this::checkSessionHealth;

    private SpeechRecognizer speechRecognizer;
    private OfflineSpeechEngine offlineEngine;
    private AppSettings settings;
    private boolean listening;
    private boolean commandHandled;
    private boolean offlineMode;
    private final Runnable offlineRestartRunnable = () -> {
        if (running && offlineMode) {
            startOfflineRecognition("正在重启离线语音识别");
        }
    };
    private boolean useOnDeviceRecognizer;
    private boolean onDeviceRecognizerAvailable;
    private int consecutiveErrors;
    private int watchdogFailures;
    private int offlineFailures;
    private long lastCommandAt;
    private long lastCallbackAt;
    private SwipeDirection lastCommandDirection;

    public static boolean isRunning() {
        return running;
    }

    public static String getCurrentStatus() {
        return currentStatus;
    }

    public static boolean start(Context context) {
        Intent intent = new Intent(context, VoiceCommandService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (RuntimeException exception) {
            AppLog.error(context, "无法启动语音服务，请从应用界面重试");
            return false;
        }
    }

    public static void stop(Context context) {
        try {
            boolean stopped = context.stopService(new Intent(context, VoiceCommandService.class));
            if (!stopped) {
                AppLog.warning(context, "语音服务已停止");
            }
        } catch (RuntimeException exception) {
            AppLog.error(context, "无法停止语音服务，请在通知栏重试");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            onDeviceRecognizerAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
        }
        Log.i(TAG, "Service created, onDeviceAvailable=" + onDeviceRecognizerAvailable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown("监听已停止");
            return START_NOT_STICKY;
        }
        if (running) {
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.error(this, "缺少麦克风权限，语音监听未启动");
            updateStatus("缺少麦克风权限");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!settings.isOfflinePreferred() && !SpeechRecognizer.isRecognitionAvailable(this)) {
            AppLog.error(this, "设备未提供可用的系统语音识别服务");
            updateStatus("系统语音识别不可用");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startForegroundWithNotification();
        } catch (RuntimeException exception) {
            AppLog.error(this, "前台语音服务启动失败，请检查麦克风与通知权限");
            updateStatus("启动失败");
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        consecutiveErrors = 0;
        Log.i(TAG, "Voice service started");
        AppLog.info(this, "语音监听已启动");
        if (settings.isOfflinePreferred()) {
            startOfflineRecognition("使用离线语音识别");
        } else {
            startListening();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        boolean wasRunning = running;
        running = false;
        listening = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Failed to release SpeechRecognizer on destroy", exception);
            }
            speechRecognizer = null;
        }
        if (offlineEngine != null) {
            offlineEngine.stop();
            offlineEngine = null;
        }
        Log.i(TAG, "Voice service destroyed, wasRunning=" + wasRunning);
        if (wasRunning) {
            updateStatus("监听已停止");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startListening() {
        if (!running || offlineMode) {
            return;
        }
        mainHandler.removeCallbacks(restartRunnable);
        mainHandler.removeCallbacks(sessionWatchdog);
        try {
            if (speechRecognizer == null) {
                boolean onDevice = useOnDeviceRecognizer && onDeviceRecognizerAvailable
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
                speechRecognizer = onDevice
                        ? SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                        : SpeechRecognizer.createSpeechRecognizer(this);
                speechRecognizer.setRecognitionListener(this);
                Log.i(TAG, "SpeechRecognizer created, onDevice=" + onDevice);
            }
            commandHandled = false;
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag());
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            lastCallbackAt = SystemClock.elapsedRealtime();
            listening = true;
            speechRecognizer.startListening(intent);
            updateStatus(useOnDeviceRecognizer ? "等待语音指令（设备端）" : "等待语音指令");
            mainHandler.postDelayed(sessionWatchdog, WATCHDOG_TIMEOUT_MS);
            Log.i(TAG, "startListening requested, onDevice=" + useOnDeviceRecognizer);
        } catch (RuntimeException exception) {
            listening = false;
            Log.e(TAG, "startListening failed", exception);
            AppLog.error(this, "识别器启动异常，正在重建");
            recycleRecognizer("识别器启动异常，正在重建", 1200L);
        }
    }

    private void scheduleRestart(long delayMs) {
        if (!running) {
            return;
        }
        listening = false;
        mainHandler.removeCallbacks(sessionWatchdog);
        mainHandler.removeCallbacks(restartRunnable);
        mainHandler.postDelayed(restartRunnable, delayMs);
    }

    private void markCallback() {
        lastCallbackAt = SystemClock.elapsedRealtime();
    }

    private void checkSessionHealth() {
        if (!running || offlineMode || !listening) {
            return;
        }
        long idleMs = SystemClock.elapsedRealtime() - lastCallbackAt;
        if (idleMs < WATCHDOG_TIMEOUT_MS) {
            mainHandler.postDelayed(sessionWatchdog, Math.max(1000L, WATCHDOG_TIMEOUT_MS - idleMs));
            return;
        }

        watchdogFailures++;
        Log.w(TAG, "Recognition session produced no callback for " + idleMs + "ms");
        if (onDeviceRecognizerAvailable && !useOnDeviceRecognizer) {
            useOnDeviceRecognizer = true;
            AppLog.warning(this, "系统语音识别无响应，尝试设备端识别");
            recycleRecognizer("正在尝试设备端识别", 600L);
            return;
        }
        AppLog.warning(this, "系统语音识别连续无响应，自动启用离线识别");
        startOfflineRecognition("已切换离线语音识别");
    }

    private void recycleRecognizer(String status, long restartDelayMs) {
        mainHandler.removeCallbacks(restartRunnable);
        mainHandler.removeCallbacks(sessionWatchdog);
        listening = false;
        SpeechRecognizer oldRecognizer = speechRecognizer;
        speechRecognizer = null;
        if (oldRecognizer != null) {
            try {
                oldRecognizer.cancel();
                oldRecognizer.destroy();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Failed to release SpeechRecognizer", exception);
            }
        }
        updateStatus(status);
        scheduleRestart(restartDelayMs);
    }

    private void startOfflineRecognition(String reason) {
        if (!running) {
            return;
        }
        offlineMode = true;
        listening = false;
        mainHandler.removeCallbacks(restartRunnable);
        mainHandler.removeCallbacks(sessionWatchdog);
        mainHandler.removeCallbacks(offlineRestartRunnable);
        SpeechRecognizer oldRecognizer = speechRecognizer;
        speechRecognizer = null;
        if (oldRecognizer != null) {
            try {
                oldRecognizer.cancel();
                oldRecognizer.destroy();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Failed to release system recognizer", exception);
            }
        }
        if (offlineEngine != null) {
            offlineEngine.stop();
            offlineEngine = null;
        }
        AppLog.warning(this, reason);
        updateStatus("正在准备离线语音模型");
        String grammar = null;
        if (settings.isNoisyModeEnabled()) {
            grammar = OfflineSpeechEngine.buildNoisyGrammar(
                    settings.getWakeWord(),
                    settings.getUpCommands(),
                    settings.getDownCommands(),
                    settings.getLeftCommands(),
                    settings.getRightCommands()
            );
        }
        offlineEngine = new OfflineSpeechEngine(this, offlineCallback, grammar);
        offlineEngine.start();
        Log.i(TAG, "Offline recognition requested, noisyMode="
                + settings.isNoisyModeEnabled() + ", grammar=" + (grammar != null));
    }

    private void handleOfflineError(String message) {
        if (!running) {
            return;
        }
        AppLog.error(this, message);
        updateStatus(message);
        offlineFailures++;
        if (offlineEngine != null) {
            offlineEngine.stop();
            offlineEngine = null;
        }
        long delayMs = Math.min(30000L, 1500L * (1L << Math.min(offlineFailures, 4)));
        mainHandler.removeCallbacks(offlineRestartRunnable);
        mainHandler.postDelayed(offlineRestartRunnable, delayMs);
    }

    private void handleOfflineText(String text, boolean partial) {
        if (!running || !offlineMode) {
            return;
        }
        CommandParser parser = settings.createParser();
        CommandParser.Result command = parser.parse(text);
        if (command != null) {
            executeCommand(command, text);
            return;
        }
        if (!partial) {
            String heardText = text.trim();
            if (!heardText.isEmpty()) {
                AppLog.info(this, "离线识别“" + heardText + "”，未匹配滑动指令");
            }
            updateStatus("离线识别未匹配到指令");
        }
    }

    private void executeCommand(CommandParser.Result command, String text) {
        long now = System.currentTimeMillis();
        SwipeDirection direction = command.getDirection();
        if (direction == lastCommandDirection && now - lastCommandAt < COMMAND_COOLDOWN_MS) {
            updateStatus("已忽略过近的重复指令");
            return;
        }
        lastCommandAt = now;
        lastCommandDirection = direction;
        AppLog.info(this, "识别“" + text + "”，匹配“" + command.getMatchedPhrase() + "”");
        if (!SwipeAccessibilityService.isConnected()) {
            AppLog.error(this, "无障碍服务未启用，无法执行" + direction.getLabel() + "滑");
            updateStatus("请启用无障碍服务");
            return;
        }
        if (SwipeAccessibilityService.requestSwipe(direction, settings)) {
            updateStatus("已下发" + direction.getLabel() + "滑手势");
        } else {
            AppLog.error(this, "无障碍服务连接中断，手势未下发");
            updateStatus("无障碍连接中断");
        }
    }

    private void handleResults(Bundle results, boolean partial) {
        if (!running || offlineMode || commandHandled || results == null) {
            return;
        }
        ArrayList<String> alternatives = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (alternatives == null || alternatives.isEmpty()) {
            return;
        }

        CommandParser parser = settings.createParser();
        for (String text : alternatives) {
            CommandParser.Result command = parser.parse(text);
            if (command == null) {
                continue;
            }
            commandHandled = true;
            executeCommand(command, text);
            return;
        }

        if (!partial) {
            String heardText = alternatives.get(0).trim();
            if (!heardText.isEmpty()) {
                AppLog.info(this, "识别“" + heardText + "”，未匹配滑动指令");
            }
            updateStatus("未匹配到滑动指令");
        }
    }

    private final OfflineSpeechEngine.Callback offlineCallback = new OfflineSpeechEngine.Callback() {
        @Override
        public void onStatus(String status) {
            updateStatus(status);
        }

        @Override
        public void onReady() {
            if (!running || !offlineMode) {
                return;
            }
            consecutiveErrors = 0;
            offlineFailures = 0;
            if (!settings.isOfflinePreferred()) {
                settings.setOfflinePreferred(true);
            }
            boolean noisyMode = settings.isNoisyModeEnabled();
            boolean wakeRequired = settings.isWakeWordRequired() || noisyMode;
            if (noisyMode) {
                AppLog.info(VoiceCommandService.this, "嘈杂模式已就绪，请先说唤醒词");
                updateStatus("嘈杂识别已就绪（需唤醒词）");
            } else {
                AppLog.info(VoiceCommandService.this, "离线语音识别已就绪");
                updateStatus(wakeRequired ? "离线识别已就绪（需唤醒词）" : "离线识别已就绪");
            }
        }

        @Override
        public void onPartialResult(String text) {
            // Final sentence results are used to avoid duplicate gesture dispatch.
        }

        @Override
        public void onResult(String text) {
            handleOfflineText(text, false);
        }

        @Override
        public void onError(String message) {
            handleOfflineError(message);
        }
    };

    private long getErrorDelay(int error) {
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            return 250L;
        }
        consecutiveErrors++;
        return Math.min(5000L, 400L * (1L << Math.min(consecutiveErrors, 4)));
    }

    private static String getErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "音频采集失败";
            case SpeechRecognizer.ERROR_CLIENT:
                return "识别会话中断";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "麦克风权限不足";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "未匹配到语音内容";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "等待语音超时";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "识别服务网络异常";
            case SpeechRecognizer.ERROR_SERVER:
                return "识别服务暂不可用";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "识别器忙碌";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                return "识别请求过于频繁";
            default:
                return "语音识别错误 " + error;
        }
    }

    private void shutdown(String status) {
        running = false;
        listening = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Failed to release SpeechRecognizer on shutdown", exception);
            }
            speechRecognizer = null;
        }
        if (offlineEngine != null) {
            offlineEngine.stop();
            offlineEngine = null;
        }
        updateStatus(status);
        stopForegroundCompat();
        stopSelf();
    }

    private void startForegroundWithNotification() {
        createNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, VoiceCommandService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("声控滑动助手正在监听")
                .setContentText("说出“请往上滑”等指令，点按可管理")
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "停止监听", stopPendingIntent).build())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "语音监听", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示持续语音监听状态");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void updateStatus(String status) {
        currentStatus = status;
        Intent intent = new Intent(ACTION_STATUS_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        QuickTileSupport.refreshTiles(this);
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        if (offlineMode) {
            return;
        }
        markCallback();
        watchdogFailures = 0;
        Log.i(TAG, "onReadyForSpeech");
        updateStatus("正在聆听");
    }

    @Override
    public void onBeginningOfSpeech() {
        if (offlineMode) {
            return;
        }
        markCallback();
        Log.i(TAG, "onBeginningOfSpeech");
        updateStatus("正在识别语音");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        if (!offlineMode) {
            markCallback();
        }
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        if (!offlineMode) {
            markCallback();
        }
    }

    @Override
    public void onEndOfSpeech() {
        if (offlineMode) {
            return;
        }
        markCallback();
        Log.i(TAG, "onEndOfSpeech");
        updateStatus("正在处理识别结果");
    }

    @Override
    public void onError(int error) {
        if (offlineMode) {
            return;
        }
        markCallback();
        listening = false;
        if (!running) {
            return;
        }
        Log.w(TAG, "onError: " + error + " (" + getErrorMessage(error) + ")");
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            AppLog.error(this, getErrorMessage(error));
            shutdown("麦克风权限不足");
            return;
        }
        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            AppLog.warning(this, getErrorMessage(error) + "，正在重建识别器");
            recycleRecognizer("识别器异常，正在重建", 800L);
            return;
        }
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            AppLog.warning(this, getErrorMessage(error) + "，将自动重试");
        }
        updateStatus(getErrorMessage(error) + "，准备重试");
        scheduleRestart(getErrorDelay(error));
    }

    @Override
    public void onResults(Bundle results) {
        if (offlineMode) {
            return;
        }
        markCallback();
        listening = false;
        consecutiveErrors = 0;
        watchdogFailures = 0;
        ArrayList<String> alternatives = results == null ? null
                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        Log.i(TAG, "onResults count=" + (alternatives == null ? 0 : alternatives.size()));
        handleResults(results, false);
        scheduleRestart(250L);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        if (offlineMode) {
            return;
        }
        markCallback();
        ArrayList<String> alternatives = partialResults == null ? null
                : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        Log.d(TAG, "onPartialResults count=" + (alternatives == null ? 0 : alternatives.size()));
        handleResults(partialResults, true);
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        if (offlineMode) {
            return;
        }
        markCallback();
        Log.d(TAG, "onEvent type=" + eventType);
    }
}
