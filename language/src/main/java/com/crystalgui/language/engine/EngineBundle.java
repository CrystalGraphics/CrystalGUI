package com.crystalgui.language.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a band bundled inside a jar contains — read from an index the build writes.
 *
 * <h3>Why an index rather than walking the jar</h3>
 *
 * <p>A {@link ClassLoader} has no way to list what is under a resource prefix. Every route to it is a
 * special case of where the resource physically is: a {@code jar:} URL wants a {@code JarURLConnection}
 * and a {@code JarFile} walk, a {@code file:} URL wants a directory listing, and a loader that composes
 * several — which LaunchWrapper's does — can answer with either. Implementing all of them is a
 * meaningful amount of code whose failure mode is "the band is silently empty on one deployment shape".</p>
 *
 * <p>An index is one line per jar, written by whichever build bundled them, and reads identically
 * however the resource is stored. It is also the thing that makes the absence unambiguous: no index
 * means no band was bundled, which is a supported deployment and not an error.</p>
 */
final class EngineBundle {

    /** The file a build writes beside a band's jars. */
    static final String INDEX = "index.txt";

    private EngineBundle() {
    }

    /**
     * The jar file names under {@code prefix}, in the order the index lists them.
     *
     * <p>Order is preserved because a classpath is ordered: two jars can declare the same package, and
     * which one wins is decided by position. Sorting here would make that decision differently from the
     * build that chose it.</p>
     *
     * <p>Blank lines and {@code #} comments are skipped, and any entry containing a path separator is
     * refused — an index is a list of names in one directory, and a name that could climb out of it is
     * either a mistake or an attempt to write somewhere else.</p>
     */
    static List<String> listing(ClassLoader loader, String prefix) throws IOException {
        InputStream index = loader.getResourceAsStream(prefix + INDEX);
        if (index == null) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        try (BufferedReader lines =
                     new BufferedReader(new InputStreamReader(index, StandardCharsets.UTF_8))) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) continue;
                names.add(name);
            }
        }
        return names;
    }
}
