package com.crystalgui.mc.modern.lang;

import com.crystalgui.language.map.ReadableView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The bytes of a runtime class on 1.20.x, read from the loader that will run them.
 *
 * <pre>{@code
 * public ReadableView.ByteSource liveBytes() {
 *     return MinecraftBytes.SOURCE;
 * }
 * }</pre>
 *
 * <h3>Why a classloader is honest here and is not on 1.7.10</h3>
 *
 * <p>{@code ByteSource.ofClassLoader} carries a warning that it reads what is on disk and that disk is
 * what lies — which is true of 1.7.10, where the jar is Notch-obfuscated and LaunchWrapper renames
 * classes <em>as they load</em>. <b>1.20.x is not that.</b> The installed Minecraft jar is already in
 * the namespace the runtime speaks: SRG members on Forge, intermediary on Fabric, official on NeoForge
 * and in every dev run. Reading it therefore answers in the same namespace the compiler will resolve
 * against, which is the property that made the 1.7.10 route unusable.</p>
 *
 * <p>What it still misses is a member a mixin added, since those exist only after transformation.
 * ModLauncher and Knot expose no "give me the transformed bytes" call, so that gap is theirs rather
 * than a choice made here — and it is a gap in <em>completeness</em>, not in namespace.</p>
 *
 * <h3>It refuses the platform, deliberately</h3>
 *
 * <p>A classloader answers for {@code java.lang.Object} as readily as for {@code Level}, and a live tier
 * that does so outranks the classpath for names it has nothing to say about. At compliance 9 and above
 * ECJ discards a {@code java.lang.Object} not attributed to {@code java.base}, and the type is reported
 * unresolvable while a perfectly good classpath sits behind it. {@code ScriptNameEnvironment} guards
 * this too; a byte source that never offers the JDK in the first place is the other half.</p>
 */
public final class MinecraftBytes {

    /** The one instance — stateless, and the loader it reads is fixed. */
    public static final ReadableView.ByteSource SOURCE = MinecraftBytes::bytesOf;

    private MinecraftBytes() {
    }

    private static byte[] bytesOf(String internalName) throws IOException {
        if (internalName == null || isPlatform(internalName)) return null;
        ClassLoader loader = MinecraftBytes.class.getClassLoader();
        if (loader == null) return null;
        try (InputStream stream = loader.getResourceAsStream(internalName + ".class")) {
            if (stream == null) return null;
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read = stream.read(buffer); read > 0; read = stream.read(buffer)) {
                collected.write(buffer, 0, read);
            }
            return collected.toByteArray();
        }
    }

    /** {@code java/lang/Object} and friends — never this source's to answer for. */
    private static boolean isPlatform(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/") || internalName.startsWith("sun/");
    }
}
