package com.crystalgui.mc.modern.lang;

import javax.annotation.Nullable;

import com.crystalgui.language.probe.LanguageProbe;

/**
 * The MC 1.20.x half of {@link LanguageProbe} — <b>which this era did not have at all</b>.
 *
 * <p>The scripting probes were 1.7.10's alone: a script run through the Run command, the classpath a
 * game launcher assembles, and what the member list actually holds in a client. None of that had ever
 * been exercised on 1.20.x, and none of it is 1.7.10-specific. It moved to {@code language/} and this
 * is what it costs here.</p>
 */
public final class LanguageProbeModern {

    private LanguageProbeModern() {
    }

    /** Once, from the language mod's client init. */
    public static void register() {
        LanguageProbe.register(new Host());
    }

    private static final class Host implements LanguageProbe.Host {

        /**
         * <b>No pre-transform view on this era</b>, and that is the loader's limitation rather than a
         * choice made here.
         *
         * <p>The live-versus-raw diff needs the bytes as they were <em>before</em> the transformer chain.
         * LaunchWrapper hands those out publicly; ModLauncher and Knot expose no equivalent — the same
         * gap {@link MinecraftBytes} records from the other side. Answering null makes the probe say the
         * comparison is unavailable, which is the truth, rather than running a diff against the same
         * bytes twice and reporting a difference of zero as though it meant something.</p>
         */
        @Override
        @Nullable
        public byte[] rawBytesOf(String internalName) {
            return null;
        }

        /** {@code getInstance()} on this era, where 1.7.10 says {@code getMinecraft()}. */
        @Override
        public String mixinReceiver() {
            return "net.minecraft.client.Minecraft.getInstance().";
        }

        @Override
        public String mixinMember() {
            return "cgMixinProbe";
        }
    }
}
