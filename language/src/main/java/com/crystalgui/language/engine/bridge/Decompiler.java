package com.crystalgui.language.engine.bridge;

/**
 * Turns a class's bytes back into readable Java — what the viewer shows when nothing shipped source.
 *
 * <h3>The bridge, and what may cross it</h3>
 *
 * <p>Implemented on the far side of {@code EngineClassLoader}, like every other adapter here, because
 * the decompiler is a band jar. So the whole surface is JDK types: a {@link Bytes} callback in, a
 * {@link String} out. Nothing of ours crosses except this interface's own package, which is
 * parent-first by construction.</p>
 *
 * <h3>Why the caller supplies the bytes</h3>
 *
 * <p>A decompiler needs more than the class it was asked about: it reads supertypes to decide what an
 * override is, and inner classes to render them where they are declared. It cannot open the classpath
 * itself without being told what the classpath <em>is</em>, and on a Minecraft host the honest answer is
 * not a list of files at all — {@code TypeBytes.readable} serves the bytes the runtime actually holds,
 * post-transformer and post-mixin, already remapped to readable names.</p>
 *
 * <p>That is the whole reason this takes a callback rather than a classpath: it lets the decompiled view
 * show the class <b>as the running game has it</b>, which is a thing no external decompiler can do.</p>
 */
public interface Decompiler {

    /** Where a class's bytes come from. Null for a class nothing has, which is an ordinary answer. */
    @FunctionalInterface
    interface Bytes {

        /**
         * @param internalName the JVM form — {@code java/util/Map$Entry}, not {@code java.util.Map.Entry}
         */
        byte[] read(String internalName);
    }

    /**
     * The decompiled source of {@code binaryName}, or null.
     *
     * <p><b>Null rather than an exception for anything at all</b>, including a class the decompiler
     * chokes on. CFR's published release is from 2021 and will meet bytecode it does not understand
     * eventually; when it does, the viewer has to say "could not decompile this one" and keep working,
     * not take a hover down with it. The caller cannot distinguish the causes and does not need to.</p>
     *
     * @param binaryName {@code java.util.ArrayList}, or {@code java.util.Map$Entry} for a nested type
     */
    String decompile(String binaryName, Bytes bytes);
}
