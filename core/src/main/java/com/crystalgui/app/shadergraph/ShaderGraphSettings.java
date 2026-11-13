package com.crystalgui.app.shadergraph;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.core.settings.SettingsScope;

import java.util.List;

/**
 * What a shader graph <b>is</b>, as opposed to what is in it — declared as {@link Setting}s.
 *
 * <h3>These live in the document, not on {@code CgMasterNode}</h3>
 * <p>The master is the <b>compiler's</b> object: constructed once, handed to the emitter, never
 * serialised. A setting held there would be unsaved, unreachable from Ctrl+Z, and invisible to
 * {@code ContentHash}. So it lives in the document's {@link com.crystalgui.core.settings.Settings} at
 * {@link SettingsLayer#DOCUMENT}, and {@link #applyTo} pushes it onto the master at compile time.</p>
 *
 * <h3>The material format's vocabulary</h3>
 * <p>The options are the {@code Material { BlendMode }} values a graph's material can take
 * ({@link CgMasterNode#BLEND_MODES}): the blend mode is what Unreal's material sets, and its sorting follows
 * from it. A dropdown offering a word the format does not read is the same failure as a master port nothing
 * consumes.</p>
 */
public final class ShaderGraphSettings {

    private ShaderGraphSettings() {
    }

    /** {@code Material { BlendMode }}: how the graph's material draws over what is behind it. */
    public static final Setting<String> BLEND_MODE = Setting.select(
            "shader.blendMode", "Blend Mode", CgMasterNode.BLEND_MODES, "Opaque")
            .description("Opaque hides what is behind it; Translucent weighs by Alpha; Additive adds its colour.")
            .writableAt(SettingsLayer.DOCUMENT, SettingsLayer.MEMORY);

    /**
     * Declares every shader setting, so a generated panel can enumerate them.
     *
     * <p>Explicit, like every command set in this engine: nothing here registers itself, because a
     * registry that quietly acquired declarations nobody asked for surprises anything that walks it —
     * and a preferences panel is precisely such a thing. Idempotent, since registering replaces.</p>
     */
    public static void register() {
        SettingsRegistry.get().register(BLEND_MODE);
    }

    /** Every shader setting, in the order a panel should show them. */
    public static List<Setting<String>> all() {
        return List.of(BLEND_MODE);
    }

    /**
     * Pushes a scope's resolved values onto the master, immediately before an emit.
     *
     * <p>Resolved rather than read from one store, so a graph that says nothing still compiles — it
     * simply gets the declared defaults. That is what lets the master keep working for a caller that has
     * no document at all, which the preview renderers rely on.</p>
     */
    public static void applyTo(SettingsScope scope, CgMasterNode master) {
        if (scope == null || master == null) return;
        master.blendMode(scope.resolve(BLEND_MODE));
    }
}
