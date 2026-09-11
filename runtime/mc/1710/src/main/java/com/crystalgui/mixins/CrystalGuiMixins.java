package com.crystalgui.mixins;

import com.crystalgraphics.mc.shared.LoaderProbe;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mixin config plugin for the 1.7.10 variant, and the gate that lets its config travel in a jar
 * that also carries other loaders' mixins.
 *
 * <p>A single jar's 1.7.10 config is named in the manifest, which is the only channel FML 1.7.10
 * reads — and ModLauncher reads it too, so Forge and NeoForge see a config that is not theirs. Mixin
 * asks {@link #shouldApplyMixin} before it looks a target class up, so a mixin belonging to another
 * variant is never resolved, never linked, and never fails.</p>
 *
 * <pre>
 * {
 *   "package": "com.crystalgui.mixins",
 *   "plugin":  "com.crystalgui.mixins.CrystalGuiMixins",
 *   "client":  ["MixinGuiScreen"]
 * }
 * </pre>
 *
 * <h3>Why the ClassNode here is the SHADED one</h3>
 *
 * <p>{@code org.spongepowered.asm.lib.tree.ClassNode}, not {@code org.objectweb.asm.tree.ClassNode}.
 * A plugin compiled against vanilla Mixin's modern spelling <i>loads</i> under UniMixins — it is even
 * transformed to fit the environment — and then dies the first time a mixin is applied:</p>
 *
 * <pre>
 * CompanionPluginError: Companion plugin attempted to use a deprecated API in [mixins.crystalgui.json]
 *   plugin [...]: Accessing [org.spongepowered.asm.lib.tree.ClassNode.&lt;init&gt;(...)]
 * Caused by: NoSuchMethodError: org.spongepowered.asm.lib.tree.ClassNode.&lt;init&gt;(...)
 * </pre>
 *
 * <p>UniMixins classifies such a plugin as legacy and routes {@code preApply} through a copy
 * constructor its shaded ASM does not have. So a 1.7.10 config's plugin is compiled against the
 * Mixin this loader actually runs, and a 1.13+ config's plugin — when one of those first carries a
 * mixin — is compiled against the modern spelling. Two classes, one per ASM package, which is what
 * the plan's contract names as the fallback and what measurement chose.</p>
 *
 * <p>What both share is {@link LoaderProbe}: which loader this is, asked of Mixin's own service
 * rather than fingerprinted, in one place.</p>
 */
public final class CrystalGuiMixins implements IMixinConfigPlugin {

    /**
     * Which loader each mixin package belongs to.
     *
     * <p>Data rather than an {@code if}, so a variant is added by adding a row. J4 replaces this
     * table's <i>source</i> with the {@code variants.json} the merge writes; the lookup stays.</p>
     */
    private static final Map<String, String> OWNERS = new HashMap<String, String>();

    static {
        OWNERS.put("com.crystalgui.mixins", LoaderProbe.FML1710);
    }

    /** The package this config declared, from {@link #onLoad}. */
    private String mixinPackage = "";

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage == null ? "" : mixinPackage;
        System.out.println("[crystalgui] mixin config " + this.mixinPackage
                + ": loader is " + LoaderProbe.describe()
                + ", this config belongs to " + OWNERS.get(this.mixinPackage));
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String owner = OWNERS.get(mixinPackage);
        return owner != null && owner.equals(LoaderProbe.current());
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }
}
