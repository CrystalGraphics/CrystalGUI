package com.crystalgui.language.engine;

import static org.junit.Assert.assertEquals;

import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

import org.junit.Test;

/** The engine band finds this module's own classes by its code source, which every loader spells differently. */
public class EngineHostRootTest {

    private static final String OWN = "com/crystalgui/language/engine/EngineHost.class";

    /** LaunchWrapper: the class entry inside the jar reduces to the jar. */
    @Test
    public void aJarEntryReducesToItsArchive() throws Exception {
        URL entry = new URL("jar:file:/mods/crystalgui-language.jar!/" + OWN);
        assertEquals("file:/mods/crystalgui-language.jar", EngineHost.asClasspathRoot(entry).toString());
    }

    /** A classes directory named down to the class reduces to the directory. */
    @Test
    public void aClassFileReducesToItsRoot() throws Exception {
        URL file = new URL("file:/build/classes/java/main/" + OWN);
        assertEquals("file:/build/classes/java/main/", EngineHost.asClasspathRoot(file).toString());
    }

    /** ModLauncher 8 names the mod root with no slash, which a URLClassLoader would open as an archive. */
    @Test
    public void aModRootInALoadersOwnSchemeIsADirectory() throws Exception {
        URL root = new URL(null, "modjar://crystalgui_language", new NoHandler());
        assertEquals("modjar://crystalgui_language/", EngineHost.asClasspathRoot(root).toString());
    }

    /** A plain jar on the classpath is already a root. */
    @Test
    public void aJarIsLeftAlone() throws Exception {
        URL jar = new URL("file:/libs/language.jar");
        assertEquals("file:/libs/language.jar", EngineHost.asClasspathRoot(jar).toString());
    }

    private static final class NoHandler extends URLStreamHandler {
        @Override
        protected URLConnection openConnection(URL url) {
            throw new UnsupportedOperationException();
        }
    }
}
