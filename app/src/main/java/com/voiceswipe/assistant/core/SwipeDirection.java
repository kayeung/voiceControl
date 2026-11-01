package com.voiceswipe.assistant.core;

public enum SwipeDirection {
    UP("上"),
    DOWN("下"),
    LEFT("左"),
    RIGHT("右");

    private final String label;

    SwipeDirection(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
