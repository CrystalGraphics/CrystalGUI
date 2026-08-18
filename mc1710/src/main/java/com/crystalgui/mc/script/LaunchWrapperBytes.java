package com.crystalgui.mc.script;

import com.crystalgui.language.map.ReadableView;

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
        byte[] bytes;
        try {
            bytes = loader.getClassBytes(binaryName);
        } catch (Exception unreadable) {
            // Absent is an ordinary answer -- the caller falls back to a reflection-synthesized stub --
            // so this must not become an exception the compiler has to understand.
            return null;
        }
        if (bytes == null) return null;

        // The names LaunchWrapper passes a transformer are the un-transformed and transformed spellings
        // of the class. In a development environment they are identical, and in production the
        // deobfuscating transformer is itself one of the entries below, so passing the same name twice
        // is what its own callers do for a class that needs no renaming.
        List<IClassTransformer> transformers = loader.getTransformers();
        if (transformers == null) return bytes;
        for (IClassTransformer transformer : transformers) {
            try {
                byte[] next = transformer.transform(binaryName, binaryName, bytes);
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
}
