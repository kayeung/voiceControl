package com.voiceswipe.assistant.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;

import com.voiceswipe.assistant.core.AppLog;
import com.voiceswipe.assistant.core.AppSettings;
import com.voiceswipe.assistant.core.GesturePathCalculator;
import com.voiceswipe.assistant.core.SwipeDirection;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SwipeAccessibilityService extends AccessibilityService {
    public static final String ACTION_STATUS_CHANGED =
            "com.voiceswipe.assistant.ACCESSIBILITY_STATUS_CHANGED";
    private static final String TAG = "SwipeAccessibility";

    private static volatile SwipeAccessibilityService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean gestureInFlight = new AtomicBoolean(false);

    public static boolean isConnected() {
        return instance != null;
    }

    public static boolean requestSwipe(SwipeDirection direction, AppSettings settings) {
        SwipeAccessibilityService service = instance;
        if (service == null) {
            return false;
        }
        int distancePercent = settings.getDistancePercent();
        int durationMs = settings.getDurationMs();
        service.mainHandler.post(() -> service.performSwipe(direction, distancePercent, durationMs));
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Accessibility service created");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "Accessibility service connected");
        notifyStatusChanged();
        AppLog.info(this, "无障碍服务已连接");
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        instance = this;
        Log.i(TAG, "Accessibility service rebound");
        notifyStatusChanged();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "Accessibility service unbound");
        clearInstance();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        clearInstance();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No screen content or accessibility events are collected.
    }

    @Override
    public void onInterrupt() {
        gestureInFlight.set(false);
    }

    private void performSwipe(SwipeDirection direction, int distancePercent, int durationMs) {
        if (!gestureInFlight.compareAndSet(false, true)) {
            AppLog.warning(this, "上一手势尚未完成，已忽略重复操作");
            return;
        }

        DisplayInfo display = getDisplayInfo();
        if (display.width <= 0 || display.height <= 0) {
            gestureInFlight.set(false);
            AppLog.error(this, "无法获取当前屏幕尺寸");
            return;
        }

        GesturePathCalculator.PathSpec spec = GesturePathCalculator.calculate(
                display.width,
                display.height,
                direction,
                distancePercent,
                durationMs
        );
        Path path = new Path();
        path.moveTo(spec.getStartX(), spec.getStartY());
        path.lineTo(spec.getEndX(), spec.getEndY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, spec.getDurationMs()))
                .build();
        String orientation = spec.getOrientation() == GesturePathCalculator.ORIENTATION_LANDSCAPE
                ? "横屏" : "竖屏";

        boolean dispatched;
        try {
            dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    gestureInFlight.set(false);
                    AppLog.info(SwipeAccessibilityService.this,
                            direction.getLabel() + "滑完成（" + orientation + "，" + spec.getDurationMs() + "ms）");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    gestureInFlight.set(false);
                    AppLog.warning(SwipeAccessibilityService.this,
                            direction.getLabel() + "滑被系统取消，请保持目标应用在前台");
                }
            }, mainHandler);
        } catch (RuntimeException exception) {
            gestureInFlight.set(false);
            AppLog.error(this, "无障碍连接已断开，手势未执行");
            return;
        }

        if (!dispatched) {
            gestureInFlight.set(false);
            AppLog.error(this, "系统拒绝手势，请重新启用无障碍服务");
        }
    }

    private DisplayInfo getDisplayInfo() {
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            return new DisplayInfo(0, 0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
            return new DisplayInfo(metrics.getBounds().width(), metrics.getBounds().height());
        }
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new DisplayInfo(metrics.widthPixels, metrics.heightPixels);
    }

    private void clearInstance() {
        if (instance == this) {
            instance = null;
            AppLog.info(this, "无障碍服务已断开");
            notifyStatusChanged();
        }
    }

    private void notifyStatusChanged() {
        Intent intent = new Intent(ACTION_STATUS_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        QuickTileSupport.refreshTiles(this);
    }

    private static final class DisplayInfo {
        final int width;
        final int height;

        DisplayInfo(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
}
