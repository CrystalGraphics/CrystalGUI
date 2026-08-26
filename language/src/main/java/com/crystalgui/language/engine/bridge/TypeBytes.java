package com.crystalgui.language.engine.bridge;

import com.crystalgraphics.platform.CgPlatform;

/**
 * Class files for types the classpath cannot supply — the §15.5 A crossing.
 *
 * <h3>Why this is a bridge type rather than a parameter of its own</h3>
 *
 * <p>The engine side resolves names against ECJ's {@code FileSystem}, which reads files. On a Minecraft
 * host that view is a lie twice over: production ships obfuscated jars whose classes are remapped
 * <em>as they load</em>, and every platform runs transformers that add members no class file on disk
 * has. So the compiler has to be able to ask for bytes rather than for a path.</p>
 *
 * <p><b>It cannot ask the platform directly.</b> {@code ScriptService} lives in
 * {@code language.platform} and {@code ReadableView} in {@code language.map}, and neither package is
 * parent-first — so a child-side class naming one gets the band loader's own copy, with its own statics.
 * That failure is silent and total: {@code CgPlatform.provide(ScriptServices.SERVICE, …)} runs on the host, the compiler
 * reads a different registry, finds nothing, and quietly resolves against files as though no platform
 * were installed. Everything works and nothing is live.</p>
 *
 * <p>So the host composes the whole answer and hands the child something made of JDK types — the same
 * rule {@link MemberNameMapper} states for {@code MappingSet}, the console for its {@code Consumer} and
 * the sandbox for its {@code Predicate}. This interface adds no vocabulary: a name in, bytes or null
 * back.</p>
 *
 * <h3>Two methods, because they are two different tiers</h3>
 *
 * <p>They are asked at different points and a caller must be able to tell them apart:</p>
 *
 * <ul>
 *   <li>{@link #readable} is consulted <b>first</b>, ahead of the classpath. What the runtime holds is
 *       what will execute, so where the two disagree the runtime wins (§15.2).</li>
 *   <li>{@link #synthesized} is consulted <b>last</b>, after the classpath has also said nothing. A stub
 *       is erased of everything reflection cannot see and describes a class as <em>loaded</em> rather
 *       than as the compiler would read it, so it is a weaker answer than either real source and must
 *       never pre-empt one.</li>
 * </ul>
 *
 * <p>Folding them into one method would lose that ordering, and the loss would show as a stub silently
 * shadowing a perfectly good class file — an erased signature where the author wrote a generic one.</p>
 *
 * <h3>Internal names, and null for absent</h3>
 *
 * <p>{@code net/minecraft/world/World}, with slashes — the form a mapping file uses and the form the
 * compiler asks in. Null rather than an exception, because a miss is the ordinary case: most names go to
 * the classpath, and turning "I do not have this" into a throw would make every one of them fatal.</p>
 */
public interface TypeBytes {

    /** Supplies nothing — what a host with no platform installs, and what every test gets by default. */
    TypeBytes NONE = new TypeBytes() {
        @Override
        public byte[] readable(String internalName) {
            return null;
        }

        @Override
        public byte[] synthesized(String internalName) {
            return null;
        }
    };

    /**
     * The type's bytes as the <b>readable</b> namespace sees them, or null.
     *
     * <p>Read from the live runtime and remapped, so a member the script writes as {@code getBlock}
     * resolves against a class that really declares {@code func_147439_a}. Nothing is written to disk,
     * which is what lets it answer for a class whose bytes exist only because a mixin produced them.</p>
     */
    byte[] readable(String internalName);

    /**
     * The same bytes, for a COMPILER rather than a reader — every member also declared under the name the
     * runtime knows it by.
     *
     * <p>A script written against SRG names (`Minecraft.func_71410_x()`) runs perfectly, because that IS
     * the runtime's name for it; it just did not compile, because {@link #readable} renames the member and
     * the old name is gone. The two views part company for exactly one consumer — the name environment —
     * and everything that READS a class keeps the plain one, since aliases in a decompiled library viewer
     * would show every mapped member twice.</p>
     *
     * <p>Defaults to {@link #readable}, which is correct for any implementation with no mapping behind it:
     * where nothing is renamed there is no second name to offer.</p>
     */
    default byte[] forCompiling(String internalName) {
        return readable(internalName);
    }

    /**
     * A reflective stub for a type that exists to the JVM and has no bytes anywhere, or null.
     *
     * <p>Supertypes, members and signatures, no bodies. The two cases that occur are a class generated
     * at runtime and a class a previous script defined — both would otherwise fail to compile against
     * something the author can demonstrably call.</p>
     */
    byte[] synthesized(String internalName);
}
