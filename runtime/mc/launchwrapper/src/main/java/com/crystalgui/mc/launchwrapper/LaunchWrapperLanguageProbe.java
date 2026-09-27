package com.crystalgui.mc.launchwrapper;

import javax.annotation.Nullable;

import com.crystalgui.language.probe.LanguageProbe;

/**
 * The LaunchWrapper half of {@link LanguageProbe}, for 1.7.10 and Forge 1.8–1.12.2: a pre-transform byte
 * view, and one member a mixin added.
 *
 * <pre>{@code
 * if (isClient) LaunchWrapperLanguageProbe.register();   // from the language mod's preInit
 * }</pre>
 */
public final class LaunchWrapperLanguageProbe implements LanguageProbe.Host {

    private LaunchWrapperLanguageProbe() {
    }

    /** Once, on a client. A no-op unless the unattended run is armed. */
    public static void register() {
        LanguageProbe.register(new LaunchWrapperLanguageProbe());
    }

    /** Pre-transform bytes: the other side of the diff {@link LaunchWrapperBytes#SOURCE} is the live half of. */
    @Override
    @Nullable
    public byte[] rawBytesOf(String internalName) {
        return LaunchWrapperBytes.rawBytes(internalName);
    }

    /** {@code getMinecraft} is the MCP name on every LaunchWrapper version. */
    @Override
    public String mixinReceiver() {
        return "net.minecraft.client.Minecraft.getMinecraft().";
    }

    /** Added to {@code Minecraft} by CrystalGraphics' mixin on both hosts, and declared in no class file. */
    @Override
    public String mixinMember() {
        return "cgMixinProbe";
    }
}
