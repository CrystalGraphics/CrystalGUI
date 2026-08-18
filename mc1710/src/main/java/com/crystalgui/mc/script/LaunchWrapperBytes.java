package com.crystalgui.mc.script;

import com.crystalgui.language.map.ReadableView;

import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.util.List;

/**
 * Post-transform bytes, out of LaunchWrapper.
 *
 * <p>The whole of what Minecraft 1.7.10 has to contribute to §15.5 A, and it is a dozen lines because
 * {@code LaunchClassLoader} happens to expose both halves publicly:</p>
 *
 * <pre>
 * public byte[] getClassBytes(String name)              // RAW, pre-transform
 * public List&lt;IClassTransformer&gt; getTransformers()      // the chain itself
 * </pre>
 *
 * <p>{@code runTransformers} is private, but it is only a loop over that list — so reading the raw bytes
 * and walking the public list reproduces it exactly, with no reflection.</p>
 *
 * <h3>Why this cannot read the class file</h3>
 *
 * <p>{@code ByteSource.ofClassLoader} does, and its own javadoc says it is <i>"correct off a Minecraft
 * host and not on one, because it reads what is on disk and that is precisely the thing that lies
 * there"</i>. On 1.7.10 production the jars are Notch-obfuscated and remapped <em>as they load</em>, so
 * SRG members exist only in memory; and on every version, transformers add members no file has.</p>
 *
 * <p><b>Mixin-added members come for free.</b> Mixin applies through a transformer in exactly this list,
 * so a class whose bytes exist only because a mixin produced them is returned here like any other. That
 * is the claim no file-based classpath can satisfy and the reason this class exists.</p>
 *
 * <h3>The NAME has to be untransformed first, and in a dev client nothing says so</h3>
 *
 * <p>Callers ask for {@code net/minecraft/world/World}. In production the jar entry is {@code ave.class}
 * — Notch names, remapped to SRG <em>as they load</em> — so {@code getClassBytes} for that name returns
 * <b>null</b> and every Minecraft type looks absent. In a development client the two spellings are
 * identical, so the mistake is completely invisible there: it was found by running the obfuscated client,
 * where the namespace probe reported that it could not read {@code World} at all.</p>
 *
 * <p>{@code LaunchClassLoader.findClass} does exactly this before it reads anything:</p>
 *
 * <pre>
 * final String untransformedName = untransformName(name);   // SRG  → Notch
 * final String transformedName   = transformName(name);     // Notch → SRG
 * byte[] basicClass = getClassBytes(untransformedName);
 * runTransformers(untransformedName, transformedName, basicClass);
 * </pre>
 *
 * <p>Both delegate to a {@link IClassNameTransformer}, which the loader keeps in a private field — but
 * the same object is also an ordinary entry in the public transformer list, so it can be found by type
 * instead of by reflection. That is the whole of the fix, and it is why the pair of names is passed to
 * each transformer rather than one name twice: a deobfuscating transformer decides what to rename from
 * the difference between them.</p>
 */
final class LaunchWrapperBytes {

    /** Shared: stateless, and the loader it reads is a process-wide singleton anyway. */
    static final ReadableView.ByteSource SOURCE = new ReadableView.ByteSource() {
        @Override
        public byte[] bytesOf(String internalName) {
            return transformed(internalName);
        }
    };

    private LaunchWrapperBytes() {
    }

    private static byte[] transformed(String internalName) {
        LaunchClassLoader loader = Launch.classLoader;
        if (loader == null) return null;

        // getClassBytes takes a BINARY name; every other seam in the mapping path speaks internal names,
        // so the conversion happens here rather than at each call site -- one spelling of a class name
        // per layer is what stops a lookup silently matching nothing.
        String binaryName = internalName.replace('/', '.');

        List<IClassTransformer> transformers = loader.getTransformers();
        if (transformers == null) transformers = java.util.Collections.<IClassTransformer>emptyList();

        // THE TWO SPELLINGS, exactly as LaunchClassLoader.findClass computes them. Identical in a dev
        // client, which is why getting this wrong is invisible there and total in production.
        IClassNameTransformer renamer = renamerIn(transformers);
        String untransformedName = renamer == null ? binaryName : renamer.unmapClassName(binaryName);
        String transformedName = renamer == null ? binaryName : renamer.remapClassName(binaryName);
        if (untransformedName == null) untransformedName = binaryName;
        if (transformedName == null) transformedName = binaryName;

        byte[] bytes;
        try {
            bytes = loader.getClassBytes(untransformedName);
        } catch (Exception unreadable) {
            // Absent is an ordinary answer -- the caller falls back to a reflection-synthesized stub --
            // so this must not become an exception the compiler has to understand.
            return null;
        }
        if (bytes == null) return null;

        for (IClassTransformer transformer : transformers) {
            try {
                byte[] next = transformer.transform(untransformedName, transformedName, bytes);
                // A transformer returning null means "removed"; keeping the previous bytes would present
                // a class the runtime does not have.
                if (next == null) return null;
                bytes = next;
            } catch (Throwable refused) {
                // ONE TRANSFORMER'S FAILURE IS NOT THE CLASS'S. A mod's transformer throwing on a class
                // it did not expect must not make that class unresolvable to the editor -- the run would
                // still work, and the editor would disagree with it about a type that plainly exists.
                // Carry the bytes as they stand and let the rest of the chain run.
                continue;
            }
        }
        return bytes;
    }

    /**
     * The loader's class-NAME transformer, found by type in its own public list.
     *
     * <p>{@code LaunchClassLoader} keeps this in a private {@code renameTransformer} field and registers
     * it in the transformer list as well, so there is nothing to reflect on: the entry that implements
     * {@link IClassNameTransformer} is the same object. Null off a deobfuscated environment, where there
     * is no renaming to do and both spellings are the one the caller asked with.</p>
     *
     * <p>Not cached. It is one {@code instanceof} over a list of a few dozen, against a list that a mod
     * may still be appending to during startup — and a cache populated before the deobfuscating
     * transformer registered would answer null for the rest of the process, which is the failure this
     * method exists to fix.</p>
     */
    private static IClassNameTransformer renamerIn(List<IClassTransformer> transformers) {
        for (IClassTransformer transformer : transformers) {
            if (transformer instanceof IClassNameTransformer) return (IClassNameTransformer) transformer;
        }
        return null;
    }
}
