package com.crystalgui.language.js;

import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.run.RunConsole;
import com.crystalgui.language.run.RunLevel;
import com.crystalgui.language.run.ScriptOutput;
import com.crystalgui.language.run.ScriptRuntime;
import com.crystalgui.text.TextBuffer;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.PrintStream;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code ScriptConsoleTest}'s twin — a real compiled script's {@code console.log} reaching a real console,
 * attributed and located.
 *
 * <p>Java's line arrives through {@code System.out} and a routed stream; JavaScript's arrives through
 * {@code ScriptOutput.write} with its level in hand. Both depend on the same two things this proves: the
 * marker was set around the run, and the origin knows which line was printing — which for JavaScript
 * means the interpreter answered, on the script's thread, at the moment of the call.</p>
 */
public class JsConsoleTest {

    private JsHost host;
    private PrintStream realOut;
    private PrintStream realErr;
    private RunConsole console;

    @BeforeClass
    public static void openTheEngine() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    @Before
    public void openHost() {
        host = new JsHost(JsLanguage.executor());
        realOut = System.out;
        realErr = System.err;
        console = new RunConsole().attach(new TextBuffer());
        // THE APPLICATION'S OWN INSTALL, because `ScriptOutput.write` -- the entry point a language's
        // logging binding uses -- targets the console `install` was given. It wraps the ORIGINAL streams
        // and remembers them, so restoring the two fields below undoes it.
        ScriptOutput.install(console);
    }

    @After
    public void closeHost() {
        System.setOut(realOut);
        System.setErr(realErr);
        if (host != null) host.close();
    }

    private ScriptRuntime.Compiled compiled(String source) {
        ScriptRuntime.Compiled compiled = host.compileScript("Script.js", source, Map.of());
        assertTrue(String.valueOf(compiled.messages()), compiled.successful());
        return compiled.withSource(Resource.of(Resource.SCHEME_PROJECT, "src/Script.js"));
    }

    @Test
    public void aScriptsConsoleLogReachesTheConsoleWithItsLine() throws Throwable {
        host.run(compiled("var greeting = 'from the script';\nconsole.log(greeting);\n"), Map.of());

        console.drain();
        assertEquals("exactly the script's line, and nothing else", 1, console.lineCount());
        assertEquals("from the script", console.lineAt(0).text());
        assertEquals("Script.js", console.lineAt(0).script());
        assertEquals(RunLevel.OUT, console.lineAt(0).level());
        // AND IT KNOWS WHICH LINE SAID IT -- the interpreter's frame, read on the printing thread.
        assertNotNull("the line that printed it was not recorded", console.lineAt(0).origin());
        assertEquals("Script.js:2", console.lineAt(0).origin());
    }

    @Test
    public void aLineFromInsideAFunctionIsAttributedToTheFunctionsLine() throws Throwable {
        host.run(compiled("function say(what) {\n  console.log(what);\n}\nsay('deep');\n"), Map.of());
        console.drain();
        assertEquals("deep", console.lineAt(0).text());
        // The innermost script frame -- the console.log inside say -- not the call to say.
        assertEquals("Script.js:2", console.lineAt(0).origin());
    }

    @Test
    public void consoleErrorArrivesAtErrorLevel() throws Throwable {
        host.run(compiled("console.error('broken');\nconsole.warn('careful');\n"), Map.of());
        console.drain();
        assertEquals(RunLevel.ERROR, console.lineAt(0).level());
        assertEquals("broken", console.lineAt(0).text());
        assertEquals(RunLevel.ERROR, console.lineAt(1).level());
    }

    @Test
    public void aMultiLineValueIsAsManyRows() throws Throwable {
        host.run(compiled("console.log('one\\ntwo');\n"), Map.of());
        console.drain();
        assertEquals(2, console.lineCount());
        assertEquals("one", console.lineAt(0).text());
        assertEquals("two", console.lineAt(1).text());
    }

    /**
     * {@code System.out} inside a Java call from the script lands in the same console.
     *
     * <p>The marker routes it, exactly as it routes a Java script's — so a Java library the script uses
     * prints where the script does. And the origin is still the script's line, because
     * {@code RhinoOrigin} asks the interpreter, whose innermost frame is the statement making the call.</p>
     */
    @Test
    public void javaOutputFromWithinTheScriptIsRoutedToo() throws Throwable {
        host.run(compiled("var out = java.lang.System.out;\nout.println('via java');\n"), Map.of());
        console.drain();
        assertEquals(1, console.lineCount());
        assertEquals("via java", console.lineAt(0).text());
        assertEquals("Script.js:2", console.lineAt(0).origin());
    }
}
