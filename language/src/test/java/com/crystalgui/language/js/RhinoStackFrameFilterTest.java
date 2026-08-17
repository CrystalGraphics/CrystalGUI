package com.crystalgui.language.js;

import com.crystalgui.language.run.ConsoleFilter;
import com.crystalgui.language.run.JavaStackFrameFilter;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Rhino's two shapes link; the JVM's does not — that one is the other filter's, and links must not double. */
public class RhinoStackFrameFilterTest {

    private final RhinoStackFrameFilter filter = new RhinoStackFrameFilter();

    @Test
    public void aScriptFrameLinksTheFileAndLine() {
        String text = "\tat Main.js:12 (summarise)";
        List<ConsoleFilter.Link> links = filter.apply(text);
        assertEquals(1, links.size());
        assertEquals("Main.js", links.get(0).fileName());
        assertEquals(12, links.get(0).line());
        assertEquals("Main.js:12", text.substring(links.get(0).start(), links.get(0).end()));
    }

    @Test
    public void aTopLevelFrameWithNoFunctionLinksToo() {
        assertEquals(1, filter.apply("\tat Main.js:3").size());
    }

    @Test
    public void theMessageSuffixLinks() {
        String text = "org.mozilla.javascript.JavaScriptException: Error: boom (Main.js#7)";
        List<ConsoleFilter.Link> links = filter.apply(text);
        assertEquals(1, links.size());
        assertEquals("Main.js#7", text.substring(links.get(0).start(), links.get(0).end()));
        assertEquals(7, links.get(0).line());
    }

    @Test
    public void aJvmFrameIsLeftToTheJvmFilter() {
        String jvm = "\tat com.example.Type.method(Type.java:12)";
        assertTrue(filter.apply(jvm).isEmpty());
        assertEquals("the JVM filter still owns it", 1, new JavaStackFrameFilter().apply(jvm).size());
    }

    @Test
    public void ordinaryOutputIsNotAFrame() {
        assertTrue(filter.apply("total: 9 over 3 items").isEmpty());
        assertTrue(filter.apply("see notes.js for details").isEmpty());
    }
}
