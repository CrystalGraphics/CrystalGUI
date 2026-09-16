package com.crystalgui.mc.v1710.lang;

import java.io.IOException;

import javax.annotation.Nullable;

import com.crystalgui.language.probe.LanguageProbe;

import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * The MC 1.7.10 half of {@link LanguageProbe}: a pre-transform byte view, and one mixed-in member.
 *
 * <p>Every probe — the script run, the classpath report, the six completion shapes, the constant-pool
 * diff — is {@code language/}'s. This was 426 lines with <b>one</b> Minecraft import in it.</p>
 */
public final class LanguageAutoTest1710 {

    private LanguageAutoTest1710() {
    }

    /** Once, from the language mod's client preInit. */
    public static void register() {
        LanguageProbe.register(new Host());
    }

    private static final class Host implements LanguageProbe.Host {

        /**
         * Pre-transform bytes — what a file-based classpath would see.
         *
         * <p>1.7.10 is the platform that can answer this: {@code LaunchClassLoader} exposes
         * {@code getClassBytes} publicly, and it is the raw read, before the transformer chain. That is
         * exactly the other side of the diff {@link LaunchWrapperBytes} supplies the live half of.</p>
         */
        @Override
        @Nullable
        public byte[] rawBytesOf(String internalName) {
            ClassLoader loader = LanguageAutoTest1710.class.getClassLoader();
            if (!(loader instanceof LaunchClassLoader)) return null;
            try {
                return ((LaunchClassLoader) loader).getClassBytes(internalName.replace('/', '.'));
            } catch (IOException unavailable) {
                return null;
            }
        }

        @Override
        public String mixinReceiver() {
            return "net.minecraft.client.Minecraft.getMinecraft().";
        }

        @Override
        public String mixinMember() {
            return "cgMixinProbe";
        }
    }
}
