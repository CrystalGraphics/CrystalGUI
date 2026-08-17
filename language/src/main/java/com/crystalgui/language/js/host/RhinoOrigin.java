package com.crystalgui.language.js.host;

import com.crystalgui.language.engine.bridge.JsExecutor;
import com.crystalgui.language.run.ScriptRef;

/**
 * Which of the script's lines is printing — Rhino's answer, asked through the bridge.
 *
 * <p>The JavaScript twin of {@link ScriptRef.ClassOrigin}. A Java script is a JVM class and its frames
 * carry the line, so the JVM is asked; a Rhino script has no JVM frames of its own, and the interpreter is
 * asked instead — {@link JsExecutor#currentLine()}, on the calling thread, which is the script's thread
 * because {@code ScriptOutput} asks at the moment a line is emitted. Answers {@code -1} when there is
 * nothing to say, and the row simply has no origin column.</p>
 *
 * <p>Host-side: it holds the bridge, never Rhino. The name records whose answer it carries.</p>
 */
record RhinoOrigin(JsExecutor executor) implements ScriptRef.Origin {

    @Override
    public int currentLine() {
        return executor.currentLine();
    }
}
