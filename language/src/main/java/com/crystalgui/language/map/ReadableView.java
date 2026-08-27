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
        java.util.Set<String> nested = new java.util.LinkedHashSet<>();
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassRemapper(
                new NestingRestored(writer, nested, this::accessOf), toReadable(nested)), 0);
        return writer.toByteArray();
    }

    /**
     * What a class's own header says it is, or {@code ACC_PUBLIC} when it cannot be read.
     *
     * <p>Asked so a class REFERENCING a nested type can declare it with the modifiers it actually has —
     * an enum as an enum. The name is a READABLE one and the source speaks runtime names, so it is
     * translated back before the lookup.</p>
     *
     * <p>Falls back rather than failing: a referenced type whose bytes are not available is still better
     * declared a member than left looking top-level, and the member's own file carries the truthful entry
     * regardless.</p>
     */
    private int accessOf(String readableInternalName) {
        try {
            byte[] bytes = source.bytesOf(mappings.runtimeClass(readableInternalName));
            if (bytes == null) bytes = source.bytesOf(readableInternalName);
            return bytes == null ? org.objectweb.asm.Opcodes.ACC_PUBLIC : new ClassReader(bytes).getAccess();
        } catch (Exception unreadable) {
            return org.objectweb.asm.Opcodes.ACC_PUBLIC;
        }
    }

    /**
     * The readable view <b>plus every member under the name the runtime knows it by</b>.
     *
     * <h3>A script written against SRG names is a viable script</h3>
     *
     * <p>{@link #readableBytesOf} renames each member and the old name is gone, so a {@code .java} script
     * that spells {@code Minecraft.func_71410_x()} does not fail to be understood — it fails to
     * <b>compile</b>: <i>"The method func_71410_x() is undefined for the type Minecraft"</i>. The
     * JavaScript side has no such problem, because Rhino looks a member up on the live object and the
     * runtime does have it under that name. Legacy scripts are written against those names, and the ask is
     * that they read and build like any other.</p>
     *
     * <p>So the compile view declares both. The alias carries the same access flags, the same descriptor
     * and the same signature, and no body — nothing loads these bytes into a JVM, and the compiler reads a
     * binary type by its declarations rather than its code.</p>
     *
     * <h3>Collected from the ORIGINAL, never derived in reverse</h3>
     *
     * <p>The obvious implementation asks {@link MappingSet#runtimeMethodOfOwner} for the name each member
     * was renamed from, and it is wrong for OVERLOADS: MCP maps many SRG methods onto one readable name,
     * so the reverse index holds one entry per {@code (owner, name)} and every overload would be aliased
     * to whichever one was stored last. One SRG name would go missing and another would appear against a
     * descriptor it never had — which compiles and then fails at run time with {@code NoSuchMethodError},
     * the exact failure this exists to remove.</p>
     *
     * <p>Reading the original class file has no such ambiguity: each member is sitting there under its
     * runtime name, beside its own descriptor. Only the descriptor needs mapping, since the TYPES in it
     * are renamed even when the member is not.</p>
     *
     * <p><b>Not what the decompiler sees.</b> {@code JavaEngine.decompile} reads {@link #readableBytesOf},
     * and it must keep doing so — aliases there would show every mapped member twice in a library viewer,
     * which is a worse answer than the one being fixed.</p>
     */
    public byte[] compilableBytesOf(String internalName) throws IOException {
        byte[] bytes = source.bytesOf(internalName);
        if (bytes == null) return null;
        Remapper readable = toReadable();
        RuntimeAliases aliases = new RuntimeAliases(mappings, readable);
        // SKIP_CODE: this pass only wants declarations, and a class file's bodies are most of its bytes.
        new ClassReader(bytes).accept(aliases, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        if (aliases.isEmpty()) return readableBytesOf(internalName);

        java.util.Set<String> nested = new java.util.LinkedHashSet<>();
        Remapper recording = toReadable(nested);
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassRemapper(
                aliases.appending(new NestingRestored(writer, nested, this::accessOf)), recording), 0);
        return writer.toByteArray();
    }

    /**
     * Collects each member's runtime name from the original class, then writes them back as aliases.
     *
     * <p>Two roles in one object because they are two halves of one fact: what it collected on the way in
     * is exactly what it emits on the way out. @see #compilableBytesOf
     */
    private static final class RuntimeAliases extends org.objectweb.asm.ClassVisitor {

        /** access, name, descriptor, signature, exceptions-or-null, constant-or-null. */
        private final List<Object[]> members = new ArrayList<>();
        private final MappingSet mappings;
        private final Remapper readable;
        private String owner;

        RuntimeAliases(MappingSet mappings, Remapper readable) {
            super(org.objectweb.asm.Opcodes.ASM9);
            this.mappings = mappings;
            this.readable = readable;
        }

        boolean isEmpty() {
            return members.isEmpty();
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                          String[] interfaces) {
            this.owner = name;
        }

        @Override
        public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
            if (!mappings.readableMethod(owner, name).equals(name)) {
                members.add(new Object[]{access, name, readable.mapMethodDesc(descriptor),
                        signature == null ? null : readable.mapSignature(signature, false),
                        exceptions, null, Boolean.TRUE});
            }
            return null;
        }

        @Override
        public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                         String signature, Object value) {
            if (!mappings.readableField(owner, name).equals(name)) {
                members.add(new Object[]{access, name, readable.mapDesc(descriptor),
                        signature == null ? null : readable.mapSignature(signature, true),
                        null, value, Boolean.FALSE});
            }
            return null;
        }

        /** The remapped class, with the collected aliases appended once it has been written out. */
        org.objectweb.asm.ClassVisitor appending(org.objectweb.asm.ClassVisitor next) {
            return new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, next) {
                @Override
                public void visitEnd() {
                    // AT THE END, never beside the member being visited: a ClassWriter is fed one member
                    // at a time, and opening a second visitor while the first is still being written
                    // interleaves two of them into one stream.
                    for (Object[] member : members) {
                        int access = (Integer) member[0];
                        String name = (String) member[1];
                        String descriptor = (String) member[2];
                        String signature = (String) member[3];
                        if (Boolean.TRUE.equals(member[6])) {
                            org.objectweb.asm.MethodVisitor alias =
                                    cv.visitMethod(access, name, descriptor, signature,
                                            (String[]) member[4]);
                            if (alias != null) alias.visitEnd();
                        } else {
                            org.objectweb.asm.FieldVisitor alias =
                                    cv.visitField(access, name, descriptor, signature, member[5]);
                            if (alias != null) alias.visitEnd();
                        }
                    }
                    super.visitEnd();
                }
            };
        }
    }

    /**
     * Declares a {@code $}-named class to be the MEMBER it is, when nothing else does.
     *
     * <h3>Obfuscation flattens nesting, and renaming does not put it back</h3>
     *
     * <p>In an obfuscated 1.7.10 jar an inner class has a <b>top-level</b> Notch name — {@code avf.class},
     * with no dollar in it anywhere — and no {@code InnerClasses} attribute tying it to anything. The
     * deobfuscating transformer renames it to {@code net/minecraft/world/WorldSettings$GameType}, which
     * restores the NAME and not the RELATIONSHIP. To anything reading the class file it is then a
     * top-level class whose simple name merely contains a dollar.</p>
     *
     * <p>Every consumer believed it, and each failure looked like its own bug. The decompiler wrote
     * {@code import net.minecraft.world.WorldSettings$GameType;} and a field typed
     * {@code WorldSettings$GameType} — text that is not valid Java and cannot be navigated. The compiler
     * refused {@code import net.minecraft.world.WorldSettings.GameType} because no member type of that
     * name exists. Go To File had nothing to offer. Meanwhile the class loads and RUNS, because a JVM
     * asks only for the binary name and never reads this attribute at all — which is what made the
     * symptom read as "the editor cannot see a class it is currently executing".</p>
     *
     * <p>The entry is written only when the class does not already declare itself a member, so an
     * ordinary jar — where javac emitted the attribute — passes through untouched.</p>
     */
    private static final class NestingRestored extends org.objectweb.asm.ClassVisitor {

        /**
         * The flags an {@code InnerClasses} entry may carry.
         *
         * <p>{@code ACC_SUPER} is deliberately absent — it is meaningless on a member entry and set on
         * nearly every class file, so passing it through would put a bit in a field that does not define
         * one. {@code ACC_ENUM} is the one this exists for: a compiler reads a member type's modifiers
         * from HERE rather than from the class's own header, so dropping it makes an enum resolve as an
         * ordinary class extending {@code Enum}.</p>
         */
        private static final int ENTRY_FLAGS = org.objectweb.asm.Opcodes.ACC_PUBLIC
                | org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_PROTECTED
                | org.objectweb.asm.Opcodes.ACC_STATIC | org.objectweb.asm.Opcodes.ACC_FINAL
                | org.objectweb.asm.Opcodes.ACC_INTERFACE | org.objectweb.asm.Opcodes.ACC_ABSTRACT
                | org.objectweb.asm.Opcodes.ACC_SYNTHETIC | org.objectweb.asm.Opcodes.ACC_ANNOTATION
                | org.objectweb.asm.Opcodes.ACC_ENUM;

        /** Every {@code $} name this class file mentions, filled by the remapper as it rewrites. */
        private final java.util.Set<String> referenced;
        private final java.util.Set<String> declared = new java.util.HashSet<>();
        /** What a name's own class file says it is, so a referencing class can declare it truthfully. */
        private final java.util.function.ToIntFunction<String> accessOf;
        private String name;
        private int ownAccess;

        NestingRestored(org.objectweb.asm.ClassVisitor next, java.util.Set<String> referenced,
                        java.util.function.ToIntFunction<String> accessOf) {
            super(org.objectweb.asm.Opcodes.ASM9, next);
            this.referenced = referenced;
            this.accessOf = accessOf;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                          String[] interfaces) {
            this.name = name;
            this.ownAccess = access;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public void visitInnerClass(String inner, String outer, String simple, int access) {
            if (inner != null) declared.add(inner);
            super.visitInnerClass(inner, outer, simple, access);
        }

        @Override
        public void visitEnd() {
            // EVERY NESTED TYPE THIS FILE MENTIONS, not only itself — which is what javac writes and what
            // the first version of this got wrong. Declaring it on the member alone left the OUTER class
            // still describing a field of type `WorldSettings$GameType`, so the two disagreed about the
            // same type and the decompiler had no consistent name to print.
            java.util.Set<String> all = new java.util.LinkedHashSet<>(referenced);
            if (name != null) all.add(name);
            for (String inner : all) {
                if (declared.contains(inner)) continue;
                int at = inner.lastIndexOf('$');
                // A SEGMENT STARTING WITH A DIGIT is anonymous or local (`Outer$1`, `Outer$1Helper`), and
                // those have no simple name to declare — the attribute takes null there, which is a
                // different statement from the one being repaired here.
                if (at <= 0 || at + 1 >= inner.length()) continue;
                if (!Character.isJavaIdentifierStart(inner.charAt(at + 1))) continue;
                // THE MEMBER'S REAL FLAGS. A compiler reads a member type's modifiers from this entry, so
                // a hardcoded `public static` reported an enum as a class extending Enum -- in a source
                // file, while the decompiled view (which reads generated text saying `enum`) had it
                // right, so one type described itself two ways depending on where it was hovered.
                //
                // ACC_STATIC is ORed in rather than read: it never appears in a class's own header, only
                // in the entry that declares the nesting, so there is nothing to copy it from. Every
                // member type obfuscation flattened was static, or it could not have survived being
                // written out as a top-level class.
                int flags = inner.equals(name) ? ownAccess : accessOf.applyAsInt(inner);
                super.visitInnerClass(inner, inner.substring(0, at), inner.substring(at + 1),
                        (flags & ENTRY_FLAGS) | org.objectweb.asm.Opcodes.ACC_STATIC);
            }
            super.visitEnd();
        }
    }

    /** Runtime → readable, for every name a class file carries. */
    private Remapper toReadable() {
        return toReadable(null);
    }

    /**
     * The same, recording every {@code $} name it rewrites into {@code nested}.
     *
     * <p>{@link Remapper#map} is called for every internal name a class file mentions — its own, its
     * supertypes, every descriptor and every signature — which makes it the one place that sees the whole
     * reference set without a second pass over the bytes. @see NestingRestored
     */
    private Remapper toReadable(java.util.Set<String> nested) {
        return new Remapper() {
            @Override
            public String map(String internalName) {
                String readable = mappings.readableClass(internalName);
                if (nested != null && readable != null && readable.indexOf('$') > 0) nested.add(readable);
                return readable;
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
