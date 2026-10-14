package io.virbius.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StreamReviewWindowTest {

    @Test
    void noWindowUntilFull() {
        StreamReviewWindow w = new StreamReviewWindow(10, 3);
        assertNull(w.due("hello", false));
        assertEquals(5, w.pendingLength());
    }

    @Test
    void windowDueWhenFull() {
        StreamReviewWindow w = new StreamReviewWindow(10, 3);
        String due = w.due("0123456789", false);
        assertEquals("0123456789", due);
    }

    @Test
    void doneFlushesRemainder() {
        StreamReviewWindow w = new StreamReviewWindow(100, 5);
        assertNull(w.due("abc", false));
        String due = w.due("", true);
        assertEquals("abc", due);
    }

    @Test
    void acceptCarriesContextOverlapIntoNextWindow() {
        StreamReviewWindow w = new StreamReviewWindow(10, 4);
        w.due("0123456789", false);
        assertEquals("0123456789", w.accept());

        // Next window includes the last 4 chars of the previous one as context.
        String due = w.due("abcdefghik", false);
        assertEquals("6789" + "abcdefghik", due);
        // Forward never re-sends the overlap.
        assertEquals("abcdefghik", w.accept());
    }

    @Test
    void acceptOnShortContentKeepsAllAsContext() {
        StreamReviewWindow w = new StreamReviewWindow(5, 50);
        w.due("hello", false);
        assertEquals("hello", w.accept());
        String due = w.due("world", false);
        assertEquals("helloworld", due);
    }

    @Test
    void rejectDropsPending() {
        StreamReviewWindow w = new StreamReviewWindow(5, 3);
        w.due("bad!!", false);
        w.reject();
        assertEquals(0, w.pendingLength());
    }

    @Test
    void multiWindowSessionForwardsExactlyOnce() {
        StreamReviewWindow w = new StreamReviewWindow(4, 2);
        StringBuilder forwarded = new StringBuilder();
        String input = "abcdefghijkl"; // 12 chars → 3 windows of 4
        for (int i = 0; i < input.length(); i++) {
            String due = w.due(String.valueOf(input.charAt(i)), i == input.length() - 1);
            if (due != null) {
                forwarded.append(w.accept());
            }
        }
        assertEquals(input, forwarded.toString());
        assertTrue(w.pendingLength() == 0);
    }
}
