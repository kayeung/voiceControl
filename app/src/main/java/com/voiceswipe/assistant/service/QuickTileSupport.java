package com.voiceswipe.assistant.service;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.accessibility.AccessibilityManager;

import com.voiceswipe.assistant.ui.MainActivity;
import com.voiceswipe.assistant.ui.QuickLaunchActivity;

import java.util.List;

public final class QuickTileSupport {
    private QuickTileSupport() {
    }

    public static void refreshTiles(Context context) {
        try {
            TileService.requestListeningState(
                    context, new ComponentName(context, VoiceTileService.class));
        } catch (RuntimeException ignored) {
            // Some vendor quick settings implementations do not support refresh requests.
        }
    }

    public static boolean isAccessibilityEnabled(Context context) {
        if (SwipeAccessibilityService.isConnected()) {
            return true;
        }
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null || !manager.isEnabled()) {
            return false;
        }
        String serviceId = context.getPackageName() + "/"
                + SwipeAccessibilityService.class.getName();
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : services) {
            if (serviceId.equals(info.getId())) {
                return true;
            }
        }
        return false;
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    public static void startAndCollapseActivity(TileService tileService, Intent intent, int requestCode) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    tileService,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            tileService.startActivityAndCollapse(pendingIntent);
        } else {
            // Required compatibility path for Android 7-13.
            tileService.startActivityAndCollapse(intent);
        }
    }

    public static void openMainActivity(TileService tileService) {
        startAndCollapseActivity(tileService, new Intent(tileService, MainActivity.class), 30);
    }

    public static void openAccessibilitySettings(TileService tileService) {
        startAndCollapseActivity(
                tileService, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 31);
    }

    public static void openQuickLaunchActivity(TileService tileService) {
        startAndCollapseActivity(
                tileService, new Intent(tileService, QuickLaunchActivity.class), 32);
    }
}
