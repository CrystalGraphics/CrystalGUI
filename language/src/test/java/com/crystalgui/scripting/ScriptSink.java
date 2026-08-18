package com.crystalgui.scripting;

import java.util.ArrayList;
import java.util.List;

/**
 * A script-callable side effect, deliberately <b>outside</b> {@code com.crystalgui.language}.
 *
 * <h3>Why the package matters</h3>
 *
 * <p>{@link com.crystalgui.language.run.ScriptPolicy#ALWAYS_REFUSED} is a floor no policy can permit, and
 * it covers the whole language stack — because that is where the classes that could switch a policy off
 * live. Every other test in this module declares its sink as a nested class of the test, which puts it
 * in {@code com.crystalgui.language.*} and makes it unreachable from a script the moment a policy is
 * configured.</p>
 *
 * <p>That is not a gap to work around: those tests run under {@code allowAll()}, where the floor is not
 * consulted, and a sink living in our own package is only ever a stand-in for the host's API anyway.
 * A test that <em>does</em> configure a policy needs a sink where a host's API would actually be, which
 * is what this is. Standing here rather than in a nested class is the point of it.</p>
 */
public final class ScriptSink {

    public static final List<String> WRITTEN = new ArrayList<>();

    public static void write(String value) {
        WRITTEN.add(value);
    }

    public static void clear() {
        WRITTEN.clear();
    }

    private ScriptSink() {
    }
}
