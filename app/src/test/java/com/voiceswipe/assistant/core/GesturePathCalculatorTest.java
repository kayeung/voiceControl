package com.voiceswipe.assistant.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GesturePathCalculatorTest {
    @Test
    public void portraitVerticalGesturesUseHeightAndCurrentCoordinates() {
        GesturePathCalculator.PathSpec up = GesturePathCalculator.calculate(
                1080, 1920, SwipeDirection.UP, 50, 300);
        assertEquals(540f, up.getStartX(), 0.01f);
        assertEquals(1440f, up.getStartY(), 0.01f);
        assertEquals(480f, up.getEndY(), 0.01f);
        assertEquals(GesturePathCalculator.ORIENTATION_PORTRAIT, up.getOrientation());

        GesturePathCalculator.PathSpec down = GesturePathCalculator.calculate(
                1080, 1920, SwipeDirection.DOWN, 50, 300);
        assertEquals(480f, down.getStartY(), 0.01f);
        assertEquals(1440f, down.getEndY(), 0.01f);
    }

    @Test
    public void portraitHorizontalGesturesUseWidth() {
        GesturePathCalculator.PathSpec left = GesturePathCalculator.calculate(
                1080, 1920, SwipeDirection.LEFT, 50, 300);
        assertEquals(810f, left.getStartX(), 0.01f);
        assertEquals(270f, left.getEndX(), 0.01f);
        assertEquals(960f, left.getStartY(), 0.01f);

        GesturePathCalculator.PathSpec right = GesturePathCalculator.calculate(
                1080, 1920, SwipeDirection.RIGHT, 50, 300);
        assertEquals(270f, right.getStartX(), 0.01f);
        assertEquals(810f, right.getEndX(), 0.01f);
    }

    @Test
    public void landscapeGesturesAreRecalculatedFromRotatedBounds() {
        GesturePathCalculator.PathSpec right = GesturePathCalculator.calculate(
                1920, 1080, SwipeDirection.RIGHT, 25, 250);
        assertEquals(720f, right.getStartX(), 0.01f);
        assertEquals(1200f, right.getEndX(), 0.01f);
        assertEquals(GesturePathCalculator.ORIENTATION_LANDSCAPE, right.getOrientation());

        GesturePathCalculator.PathSpec up = GesturePathCalculator.calculate(
                1920, 1080, SwipeDirection.UP, 50, 250);
        assertEquals(810f, up.getStartY(), 0.01f);
        assertEquals(270f, up.getEndY(), 0.01f);
    }

    @Test
    public void invalidParametersAreClampedToSafeRanges() {
        GesturePathCalculator.PathSpec spec = GesturePathCalculator.calculate(
                1080, 1920, SwipeDirection.UP, 5, 5000);
        assertEquals(20, Math.round((spec.getStartY() - spec.getEndY()) * 100 / 1920));
        assertEquals(800, spec.getDurationMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidDisplaySize() {
        GesturePathCalculator.calculate(0, 1920, SwipeDirection.UP, 45, 300);
    }
}
