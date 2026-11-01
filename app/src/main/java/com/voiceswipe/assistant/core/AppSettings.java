package com.voiceswipe.assistant.core;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {
    public static final String DEFAULT_UP = "请往上滑,向上滑,上滑";
    public static final String DEFAULT_DOWN = "请往下滑,向下滑,下滑";
    public static final String DEFAULT_LEFT = "请往左滑,向左滑,左滑";
    public static final String DEFAULT_RIGHT = "请往右滑,向右滑,右滑";
    public static final String DEFAULT_WAKE_WORD = "小助小助";

    private static final String PREFS_NAME = "voice_swipe_settings";
    private static final String KEY_UP = "commands_up";
    private static final String KEY_DOWN = "commands_down";
    private static final String KEY_LEFT = "commands_left";
    private static final String KEY_RIGHT = "commands_right";
    private static final String KEY_WAKE_REQUIRED = "wake_required";
    private static final String KEY_WAKE_WORD = "wake_word";
    private static final String KEY_DISTANCE = "distance_percent";
    private static final String KEY_DURATION = "duration_ms";
    private static final String KEY_OFFLINE_PREFERRED = "offline_preferred";

    private final SharedPreferences preferences;

    public AppSettings(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getUpCommands() {
        return preferences.getString(KEY_UP, DEFAULT_UP);
    }

    public String getDownCommands() {
        return preferences.getString(KEY_DOWN, DEFAULT_DOWN);
    }

    public String getLeftCommands() {
        return preferences.getString(KEY_LEFT, DEFAULT_LEFT);
    }

    public String getRightCommands() {
        return preferences.getString(KEY_RIGHT, DEFAULT_RIGHT);
    }

    public boolean isWakeWordRequired() {
        return preferences.getBoolean(KEY_WAKE_REQUIRED, false);
    }

    public String getWakeWord() {
        return preferences.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD);
    }

    public int getDistancePercent() {
        return preferences.getInt(KEY_DISTANCE, 45);
    }

    public int getDurationMs() {
        return preferences.getInt(KEY_DURATION, 300);
    }

    public boolean isOfflinePreferred() {
        return preferences.getBoolean(KEY_OFFLINE_PREFERRED, true);
    }

    public void setOfflinePreferred(boolean preferred) {
        preferences.edit().putBoolean(KEY_OFFLINE_PREFERRED, preferred).apply();
    }

    public void saveCommands(String up, String down, String left, String right) {
        preferences.edit()
                .putString(KEY_UP, up.trim())
                .putString(KEY_DOWN, down.trim())
                .putString(KEY_LEFT, left.trim())
                .putString(KEY_RIGHT, right.trim())
                .apply();
    }

    public void saveWakeWord(boolean required, String wakeWord) {
        preferences.edit()
                .putBoolean(KEY_WAKE_REQUIRED, required)
                .putString(KEY_WAKE_WORD, wakeWord.trim())
                .apply();
    }

    public void saveGestureParameters(int distancePercent, int durationMs) {
        preferences.edit()
                .putInt(KEY_DISTANCE, Math.max(20, Math.min(80, distancePercent)))
                .putInt(KEY_DURATION, Math.max(100, Math.min(800, durationMs)))
                .apply();
    }

    public CommandParser createParser() {
        return new CommandParser(
                getUpCommands(),
                getDownCommands(),
                getLeftCommands(),
                getRightCommands(),
                isWakeWordRequired(),
                getWakeWord()
        );
    }
}
