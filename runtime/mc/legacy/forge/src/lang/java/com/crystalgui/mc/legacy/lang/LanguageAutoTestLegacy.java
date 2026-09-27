package com.crystalgui.mc.legacy.lang;

import javax.annotation.Nullable;

import com.crystalgui.language.probe.LanguageProbe;

/** The Forge 1.8–1.12.2 half of {@link LanguageProbe}: a pre-transform byte view, and one mixed-in member. */
public final class LanguageAutoTestLegacy {

    private LanguageAutoTestLegacy() {
    }

    /** Once, from the language mod's client preInit. */
    public static void register() {
        LanguageProbe.register(new Host());
    }

    private static final class Host implements LanguageProbe.Host {

        /** Pre-transform bytes: the other side of the diff {@link LaunchWrapperBytes#SOURCE} is the live half of. */
        @Override
        @Nullable
        public byte[] rawBytesOf(String internalName) {
            return LaunchWrapperBytes.rawBytes(internalName);
        }

        @Override
        public String mixinReceiver() {
            return "net.minecraft.client.Minecraft.getMinecraft().";
        }

        /** Added to {@code Minecraft} by CrystalGraphics' legacy mixin, and in no file. */
        @Override
        public String mixinMember() {
            return "cgMixinProbe";
        }
    }
}
