package com.voiceswipe.assistant.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CommandParserTest {
    private CommandParser parser(boolean wakeRequired) {
        return new CommandParser(
                "请往上滑,向上滑,上滑",
                "请往下滑,向下滑,下滑",
                "请往左滑,向左滑,左滑",
                "请往右滑,向右滑,右滑",
                wakeRequired,
                "小助小助"
        );
    }

    @Test
    public void parsesAllDefaultDirections() {
        CommandParser parser = parser(false);
        assertEquals(SwipeDirection.UP, parser.parse("请往上滑").getDirection());
        assertEquals(SwipeDirection.DOWN, parser.parse("请往下滑").getDirection());
        assertEquals(SwipeDirection.LEFT, parser.parse("请往左滑").getDirection());
        assertEquals(SwipeDirection.RIGHT, parser.parse("请往右滑").getDirection());
    }

    @Test
    public void ignoresWhitespaceAndUnicodePunctuation() {
        CommandParser.Result result = parser(false).parse(" 请 往 上，滑！ ");
        assertEquals(SwipeDirection.UP, result.getDirection());
        assertEquals("请往上滑", result.getNormalizedText());
    }

    @Test
    public void supportsAliasesInsideLongerSentence() {
        CommandParser.Result result = parser(false).parse("现在帮我向右滑一下");
        assertEquals(SwipeDirection.RIGHT, result.getDirection());
        assertEquals("向右滑", result.getMatchedPhrase());
    }

    @Test
    public void enforcesWakeWordWhenEnabled() {
        CommandParser parser = parser(true);
        assertNull(parser.parse("请往上滑"));
        assertEquals(SwipeDirection.UP, parser.parse("小助小助，请往上滑").getDirection());
    }

    @Test
    public void rejectsUnrelatedSpeech() {
        assertNull(parser(false).parse("今天天气怎么样"));
    }

    @Test
    public void customCommandsTakeEffect() {
        CommandParser parser = new CommandParser(
                "返回顶部", "去下面", "前一页", "后一页", false, "");
        assertEquals(SwipeDirection.UP, parser.parse("我想返回顶部").getDirection());
        assertEquals(SwipeDirection.RIGHT, parser.parse("后一页").getDirection());
    }
}
