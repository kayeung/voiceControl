package com.voiceswipe.assistant.core;

/** Calculates gestures in the current display coordinate system after every rotation. */
public final class GesturePathCalculator {
    public static final int ORIENTATION_PORTRAIT = 1;
    public static final int ORIENTATION_LANDSCAPE = 2;

    private GesturePathCalculator() {
    }

    public static PathSpec calculate(
            int width,
            int height,
            SwipeDirection direction,
            int distancePercent,
            int durationMs
    ) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Display dimensions must be positive");
        }
        if (direction == null) {
            throw new IllegalArgumentException("Direction is required");
        }

        int safeDistancePercent = clamp(distancePercent, 20, 80);
        int safeDurationMs = clamp(durationMs, 100, 800);
        boolean vertical = direction == SwipeDirection.UP || direction == SwipeDirection.DOWN;
        float axisLength = vertical ? height : width;
        float halfDistance = axisLength * safeDistancePercent / 200f;
        float centerX = width / 2f;
        float centerY = height / 2f;

        float startX = centerX;
        float startY = centerY;
        float endX = centerX;
        float endY = centerY;
        switch (direction) {
            case UP:
                startY = centerY + halfDistance;
                endY = centerY - halfDistance;
                break;
            case DOWN:
                startY = centerY - halfDistance;
                endY = centerY + halfDistance;
                break;
            case LEFT:
                startX = centerX + halfDistance;
                endX = centerX - halfDistance;
                break;
            case RIGHT:
                startX = centerX - halfDistance;
                endX = centerX + halfDistance;
                break;
        }

        int orientation = width > height ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;
        return new PathSpec(
                clamp(startX, 1, width - 1),
                clamp(startY, 1, height - 1),
                clamp(endX, 1, width - 1),
                clamp(endY, 1, height - 1),
                safeDurationMs,
                orientation
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class PathSpec {
        private final float startX;
        private final float startY;
        private final float endX;
        private final float endY;
        private final int durationMs;
        private final int orientation;

        PathSpec(float startX, float startY, float endX, float endY, int durationMs, int orientation) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.durationMs = durationMs;
            this.orientation = orientation;
        }

        public float getStartX() {
            return startX;
        }

        public float getStartY() {
            return startY;
        }

        public float getEndX() {
            return endX;
        }

        public float getEndY() {
            return endY;
        }

        public int getDurationMs() {
            return durationMs;
        }

        public int getOrientation() {
            return orientation;
        }
    }
}
