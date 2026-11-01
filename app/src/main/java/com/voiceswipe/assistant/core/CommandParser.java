package com.voiceswipe.assistant.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Matches normalized speech against user-configurable phrases. */
public final class CommandParser {
    private final List<Entry> entries = new ArrayList<>();
    private final boolean wakeWordRequired;
    private final String wakeWord;

    public CommandParser(
            String upCommands,
            String downCommands,
            String leftCommands,
            String rightCommands,
            boolean wakeWordRequired,
            String wakeWord
    ) {
        addCommands(SwipeDirection.UP, upCommands);
        addCommands(SwipeDirection.DOWN, downCommands);
        addCommands(SwipeDirection.LEFT, leftCommands);
        addCommands(SwipeDirection.RIGHT, rightCommands);
        entries.sort((first, second) -> Integer.compare(second.phrase.length(), first.phrase.length()));
        this.wakeWordRequired = wakeWordRequired;
        this.wakeWord = normalize(wakeWord);
    }

    public Result parse(String spokenText) {
        String normalizedText = normalize(spokenText);
        if (normalizedText.isEmpty()) {
            return null;
        }
        if (wakeWordRequired && (wakeWord.isEmpty() || !normalizedText.contains(wakeWord))) {
            return null;
        }
        for (Entry entry : entries) {
            if (normalizedText.contains(entry.phrase)) {
                return new Result(entry.direction, entry.phrase, normalizedText);
            }
        }
        return null;
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]+", "");
    }

    public static boolean hasCommand(String commands) {
        if (commands == null) {
            return false;
        }
        for (String item : commands.split("[,，;；\\r\\n]+")) {
            if (!normalize(item).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void addCommands(SwipeDirection direction, String commands) {
        if (commands == null) {
            return;
        }
        for (String item : commands.split("[,，;；\\r\\n]+")) {
            String phrase = normalize(item);
            if (!phrase.isEmpty()) {
                entries.add(new Entry(direction, phrase));
            }
        }
    }

    private static final class Entry {
        final SwipeDirection direction;
        final String phrase;

        Entry(SwipeDirection direction, String phrase) {
            this.direction = direction;
            this.phrase = phrase;
        }
    }

    public static final class Result {
        private final SwipeDirection direction;
        private final String matchedPhrase;
        private final String normalizedText;

        Result(SwipeDirection direction, String matchedPhrase, String normalizedText) {
            this.direction = direction;
            this.matchedPhrase = matchedPhrase;
            this.normalizedText = normalizedText;
        }

        public SwipeDirection getDirection() {
            return direction;
        }

        public String getMatchedPhrase() {
            return matchedPhrase;
        }

        public String getNormalizedText() {
            return normalizedText;
        }
    }
}
