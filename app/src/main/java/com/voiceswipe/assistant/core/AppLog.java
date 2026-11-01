package com.voiceswipe.assistant.core;

import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class AppLog {
    public static final String ACTION_LOG_UPDATED = "com.voiceswipe.assistant.LOG_UPDATED";
    private static final int MAX_ENTRIES = 100;
    private static final List<Entry> entries = new ArrayList<>();

    private AppLog() {
    }

    public static synchronized void info(Context context, String message) {
        add(context, "信息", message);
    }

    public static synchronized void warning(Context context, String message) {
        add(context, "注意", message);
    }

    public static synchronized void error(Context context, String message) {
        add(context, "错误", message);
    }

    public static synchronized void clear(Context context) {
        entries.clear();
        notifyChanged(context);
    }

    public static synchronized String getFormattedLogs() {
        if (entries.isEmpty()) {
            return "暂无日志";
        }
        StringBuilder builder = new StringBuilder();
        for (Entry entry : entries) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(entry.time)
                    .append("  [")
                    .append(entry.level)
                    .append("]  ")
                    .append(entry.message);
        }
        return builder.toString();
    }

    private static void add(Context context, String level, String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        entries.add(0, new Entry(time, level, message));
        if (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        notifyChanged(context);
    }

    private static void notifyChanged(Context context) {
        Intent intent = new Intent(ACTION_LOG_UPDATED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    private static final class Entry {
        final String time;
        final String level;
        final String message;

        Entry(String time, String level, String message) {
            this.time = time;
            this.level = level;
            this.message = message;
        }
    }
}
