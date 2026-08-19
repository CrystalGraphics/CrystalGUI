package com.crystalgui.language.run.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Compiled scripts, kept so a launch does not recompile everything it ran last time.
 *
 * <p>Two implementations, and they answer different needs rather than one being the real one:
 * {@link #inMemory()} for a session, and {@link #directory} for a world that is launched again.</p>
 */
public interface ScriptCache {

    /** The compiled classes for this key, or null. */
    Map<String, byte[]> get(ScriptCacheKey key);

    /** Stores a compilation. Overwrites, though by construction a key maps to one result. */
    void put(ScriptCacheKey key, Map<String, byte[]> classes);

    /** Caches nothing — for a caller that wants the compile every time, and for tests. */
    ScriptCache NONE = new ScriptCache() {
        @Override
        public Map<String, byte[]> get(ScriptCacheKey key) {
            return null;
        }

        @Override
        public void put(ScriptCacheKey key, Map<String, byte[]> classes) {
            // Deliberately.
        }
    };

    /** An unbounded map. Fine for a session — a script's classes are kilobytes and there are dozens. */
    public static ScriptCache inMemory() {
        final Map<ScriptCacheKey, Map<String, byte[]>> entries = new LinkedHashMap<>();
        return new ScriptCache() {
            @Override
            public Map<String, byte[]> get(ScriptCacheKey key) {
                Map<String, byte[]> found = entries.get(key);
                // A COPY OUT. The caller goes on to remap and instrument these bytes, and every
                // implementation of that takes a map and hands one back -- but nothing stops a future
                // one mutating in place, and a cache that hands out its own storage would then serve
                // an instrumented class as if it were the compile output, forever.
                return found == null ? null : new LinkedHashMap<>(found);
            }

            @Override
            public void put(ScriptCacheKey key, Map<String, byte[]> classes) {
                entries.put(key, new LinkedHashMap<>(classes));
            }
        };
    }

    /**
     * A directory, one subdirectory per key.
     *
     * <p>This is the one §15.5 D.3 is actually about: fifty scripts in a world must not mean fifty
     * compiles every launch. Nothing here expires or revalidates — the key already encodes everything
     * that could make an entry wrong, so a stale entry is not a stale entry, it is an entry nobody will
     * ever ask for again. What it does mean is that the directory grows, and pruning it is a
     * housekeeping job for whoever owns the world folder rather than a correctness one.</p>
     */
    public static ScriptCache directory(Path root) {
        return new ScriptCache() {
            @Override
            public Map<String, byte[]> get(ScriptCacheKey key) {
                Path entry = root.resolve(key.fileName());
                if (!Files.isDirectory(entry)) return null;
                // SORTED, so the classpath order a caller derives from this is the same on every
                // machine -- Files.list gives filesystem order, which differs between ext4 and NTFS.
                Map<String, byte[]> classes = new TreeMap<>();
                try (Stream<Path> files = Files.list(entry)) {
                    for (Path file : files.sorted().toArray(Path[]::new)) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".class")) continue;
                        classes.put(decode(name.substring(0, name.length() - 6)),
                                Files.readAllBytes(file));
                    }
                } catch (IOException unreadable) {
                    // A half-written or unreadable entry is a miss, not a failure: recompiling is
                    // always correct, and refusing to run because a cache is damaged is not.
                    return null;
                }
                return classes.isEmpty() ? null : classes;
            }

            @Override
            public void put(ScriptCacheKey key, Map<String, byte[]> classes) {
                Path entry = root.resolve(key.fileName());
                try {
                    Files.createDirectories(entry);
                    for (Map.Entry<String, byte[]> compiled : classes.entrySet()) {
                        Files.write(entry.resolve(encode(compiled.getKey()) + ".class"),
                                compiled.getValue());
                    }
                } catch (IOException unwritable) {
                    // A cache that cannot be written is a cache that misses. Nothing above this needs
                    // to know, and failing the run over it would trade a fast path for a broken one.
                }
            }

            /** A binary name as one filename — {@code $} is legal on every filesystem, {@code /} is not. */
            private String encode(String binaryName) {
                return binaryName.replace('.', '#');
            }

            private String decode(String fileName) {
                return fileName.replace('#', '.');
            }
        };
    }
}
