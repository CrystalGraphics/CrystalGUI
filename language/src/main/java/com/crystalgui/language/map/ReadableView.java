package com.crystalgui.language.map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The "in" direction: a type as the <b>author</b> should see it, derived from what will actually run.
 *
 * <h3>Why the view is generated from bytes rather than declared</h3>
 *
 * <p>On a Minecraft host the disk view is a lie. Production 1.7.10 ships Notch-obfuscated jars remapped
 * as they load, so SRG members exist only in memory; every platform runs transformers and mixins that
 * add members no class file on disk has. So the readable view has to be built from the bytecode that
 * will execute — remapped runtime→readable — rather than from a jar somebody points at. Then what the
 * compiler resolves against is exactly what will run, mixin-added members included, and the
 * transformed-class and obfuscation-linkage problems close with one mechanism.</p>
 *
 * <h3>What this does NOT do, and it is the honest half of §15.5</h3>
 *
 * <p>This writes remapped classes to a directory and hands the path to the compiler, because
 * {@code ASTParser.setEnvironment} takes file paths. That works anywhere bytes are obtainable — a
 * plain JVM, the harness, a test — and it is what the round-trip is proven with.</p>
 *
 * <p>It is <b>not</b> what a live Minecraft host needs. There the bytes come from the launch
 * classloader through the transformer chain, per platform, and feeding them to the compiler means an
 * {@code INameEnvironment} rather than a directory — no writing, no staleness, and it works for a class
 * whose bytes only exist because a mixin produced them. That is the piece still outstanding, and it
 * cannot be written or validated without the platform — <b>it is M12's live name environment</b>, named
 * here because an audit read this paragraph as an unowned improvement and nearly scheduled it twice. The remapping itself — the part with the hard
 * logic — is shared by both routes and is here.</p>
 */
public final class ReadableView {

    /** Where the runtime bytes of a class come from. */
    public interface ByteSource {
        /** Post-transform bytes for an internal name, or null if unavailable. */
        byte[] bytesOf(String internalName) throws IOException;

        /**
         * The ordinary route: read the class file the loader would.
         *
         * <p><b>Correct off a Minecraft host and not on one</b>, because it reads what is on disk and
         * that is precisely the thing that lies there. Named as the default so a platform that needs
         * the transformer chain has something obvious to replace.</p>
         */
        static ByteSource ofClassLoader(ClassLoader loader) {
            return internalName -> {
                try (InputStream stream = loader.getResourceAsStream(internalName + ".class")) {
                    if (stream == null) return null;
                    java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
                    byte[] scratch = new byte[8192];
                    int read;
                    while ((read = stream.read(scratch)) > 0) collected.write(scratch, 0, read);
                    return collected.toByteArray();
                }
            };
        }
    }

    private final MappingSet mappings;
    private final ByteSource source;

    public ReadableView(MappingSet mappings, ByteSource source) {
        this.mappings = mappings;
        this.source = source;
    }

    /**
     * Writes readable views of {@code internalNames} under {@code into}, and returns it.
     *
     * <p>The directory is a classpath entry for the compiler. Only the types asked for are written:
     * remapping a whole modpack eagerly would cost minutes, and the set that matters is what a script
     * actually names — which the caller knows and this does not.</p>
     */
    public Path materialise(Path into, List<String> internalNames) throws IOException {
        Files.createDirectories(into);
        for (String internalName : internalNames) {
            byte[] view = readableBytesOf(internalName);
            if (view == null) continue;

            String readableName = mappings.readableClass(internalName);
            Path target = into.resolve(readableName + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, view);
        }
        return into;
    }

    /**
     * One type's runtime bytes, remapped into the readable namespace — <b>without writing anything</b>.
     *
     * <p>The whole of what {@link #materialise} does per class, minus the file. Extracted because a live
     * host does not want the file: an {@code INameEnvironment} answers the compiler from bytes, so a
     * directory is a staging area that can only go stale, and on a Minecraft host it would go stale
     * against the very thing that makes this necessary — a mixin adding a member between one compile and
     * the next.</p>
     *
     * <p>{@code materialise} is still the right shape where a file path is genuinely required, which is
     * anything driving ECJ's batch front end, and it now shares this implementation rather than carrying
     * a second copy of the remap.</p>
     *
     * @return the readable view, or null when the source has no bytes for that name — an ordinary answer
     *         rather than a failure, since a caller normally has a classpath to fall back to
     */
    public byte[] readableBytesOf(String internalName) throws IOException {
        byte[] bytes = source.bytesOf(internalName);
        if (bytes == null) return null;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassRemapper(writer, toReadable()), 0);
        return writer.toByteArray();
    }

    /** Runtime → readable, for every name a class file carries. */
    private Remapper toReadable() {
        return new Remapper() {
            @Override
            public String map(String internalName) {
                return mappings.readableClass(internalName);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                return mappings.readableMethod(owner, name);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                return mappings.readableField(owner, name);
            }
        };
    }

    /**
     * A convenience for the common shape: materialise into a fresh temp directory.
     *
     * <p>The caller owns it and should delete it with the analysis it belongs to. Not deleted here
     * because the compiler reads it lazily and pulling it out from under an in-flight analysis
     * produces a resolution failure that names a class rather than a missing directory.</p>
     */
    public Path materialiseTemporary(List<String> internalNames) throws IOException {
        return materialise(Files.createTempDirectory("cgui-readable"), internalNames);
    }

    /** Every internal name this mapping set renames — the set worth materialising when it is small. */
    public static List<String> allMappedTypes(MappingSet mappings, List<String> candidates) {
        List<String> mapped = new ArrayList<>();
        for (String candidate : candidates) {
            if (!mappings.readableClass(candidate).equals(candidate)
                    || mappings.mapsAnyMemberOf(mappings.readableClass(candidate))) {
                mapped.add(candidate);
            }
        }
        return mapped;
    }
}
