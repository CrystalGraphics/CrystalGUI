package com.crystalgui.syntax.treesitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads a grammar's {@code highlights.scm} from this module's resources.
 *
 * <p>The query files are <b>vendored</b>, because the grammar jars do not carry them: {@code
 * tree-sitter-java-0.23.5.jar} contains the compiled parser and its natives and nothing else. Each one is
 * copied in with its grammar's own licence, and each is the grammar author's file rather than a
 * hand-written approximation — the capture names in it are what a theme is expected to style, so an
 * approximation would produce highlighting that is subtly unlike every other editor's.</p>
 */
final class Queries {

    private Queries() {
    }

    static String load(String resourcePath) {
        try (InputStream in = Queries.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("highlight query not on the classpath: " + resourcePath);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + resourcePath, e);
        }
    }
}
