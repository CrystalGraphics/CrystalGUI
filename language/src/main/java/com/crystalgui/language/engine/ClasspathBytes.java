package com.crystalgui.language.engine;

import javax.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a class's bytes off a classpath — the decompiler's fallback when no runtime holds them.
 *
 * <h3>Second, never first</h3>
 *
 * <p>{@code TypeBytes.readable} is asked before this, and on a Minecraft host it answers for nearly
 * everything: the runtime's own bytes, post-transformer and post-mixin, already remapped. This is what
 * carries the harness, every test and a plain JVM, where no platform is registered and
 * {@code TypeBytes.NONE} answers null for everything.</p>
 *
 * <h3>Open once, closed by the caller</h3>
 *
 * <p>A decompile asks for the class, then its supertypes, then anything nested — dozens of lookups
 * across the same few jars. Opening a {@link ZipFile} per lookup would reread each archive's central
 * directory every time; this keeps them for the length of one decompile and closes them after, which is
 * why it is {@link Closeable} rather than a static helper.</p>
 */
public final class ClasspathBytes implements Closeable {

    private final List<Path> directories = new ArrayList<>();
    private final List<ZipFile> archives = new ArrayList<>();

    public ClasspathBytes(@Nullable List<String> classpath) {
        if (classpath == null) return;
        for (String entry : classpath) {
            if (entry == null || entry.isEmpty()) continue;
            Path path = Paths.get(entry);
            if (Files.isDirectory(path)) {
                directories.add(path);
                continue;
            }
            if (!Files.isRegularFile(path)) continue;
            try {
                archives.add(new ZipFile(path.toFile()));
            } catch (IOException unreadable) {
                // A classpath entry that will not open is not this class's problem to report: the
                // compiler resolving against the same entry says so far more usefully.
            }
        }
    }

    /**
     * @param internalName the JVM form — {@code java/util/Map$Entry}
     */
    @Nullable
    public byte[] read(String internalName) {
        if (internalName == null || internalName.isEmpty()) return null;
        String relative = internalName + ".class";
        // DIRECTORIES FIRST, which matters for a dev run: a module's own freshly compiled output should
        // win over whatever jar an older copy of it lives in.
        for (Path directory : directories) {
            Path file = directory.resolve(relative);
            if (!Files.isRegularFile(file)) continue;
            try {
                return Files.readAllBytes(file);
            } catch (IOException unreadable) {
                return null;
            }
        }
        for (ZipFile archive : archives) {
            ZipEntry entry = archive.getEntry(relative);
            if (entry == null) continue;
            try (InputStream in = archive.getInputStream(entry)) {
                return drain(in);
            } catch (IOException unreadable) {
                return null;
            }
        }
        return null;
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        for (int read = in.read(buffer); read > 0; read = in.read(buffer)) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    @Override
    public void close() {
        for (ZipFile archive : archives) {
            try {
                archive.close();
            } catch (IOException ignored) {
                // Closing a read-only archive cannot fail in a way anything above could act on.
            }
        }
        archives.clear();
        directories.clear();
    }
}
