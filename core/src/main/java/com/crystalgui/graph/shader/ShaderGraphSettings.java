package com.crystalgui.graph.shader;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.core.settings.SettingsScope;

import java.util.List;

/**
 * What a shader graph <b>is</b>, as opposed to what is in it — declared as {@link Setting}s.
 *
 * <h3>These used to live on {@code CgMasterNode}, which was never storage</h3>
 * <p>The master is the <b>compiler's</b> object: constructed once, handed to the emitter, never
 * serialised. Holding the vertex format, render type and queue there meant they were unsaved,
 * unreachable from Ctrl+Z, and invisible to {@code ContentHash} — two graphs differing only in their
 * queue encoded byte-identically, so a content-addressed cache would happily serve one for the other.</p>
 *
 * <p>They are document state by the project's own test — a reload must give them back — so they live in
 * the document's {@link com.crystalgui.core.settings.Settings} at
 * {@link SettingsLayer#DOCUMENT}, and {@link #applyTo} pushes them onto the master at compile time. The
 * master goes back to being purely what the emitter reads.</p>
 *
 * <h3>Why the values are the {@code .shader} vocabulary and not Unity's</h3>
 * <p>Unity's inspector spells this {@code Surface Type} / {@code Workflow Mode} / {@code Material}, and
 * two of those three are lighting-model selectors with nothing behind them here. What is real is what
 * {@code CgShaderParser} actually reads and {@code CgTransparentRenderer} actually runs, so the options
 * are the tokens a {@code .shader} file genuinely accepts. A dropdown offering a word the parser has
 * never heard of is the same failure as a master port nothing consumes.</p>
 */
public final class ShaderGraphSettings {

    private ShaderGraphSettings() {
    }

    /** The {@code #type} line — which vertex format the generated shader declares. */
    public static final Setting<String> VERTEX_FORMAT = Setting.select(
            "shader.vertexFormat", "Vertex Format",
            List.of("spatial", "pos3_uv2_col4ub", "pos2_uv2_col4ub"), "spatial")
            .description("The vertex attribute layout the shader is drawn with.")
            .writableAt(SettingsLayer.DOCUMENT, SettingsLayer.MEMORY);

    /**
     * {@code Tags { "RenderType" = ... }}.
     *
     * <p>Drives shadow auto-generation in the material compiler, which is why it is not merely cosmetic
     * even while no lighting model exists.</p>
     */
    public static final Setting<String> RENDER_TYPE = Setting.select(
            "shader.renderType", "Render Type",
            List.of("Opaque", "Transparent"), "Opaque")
            .description("What kind of surface this is, for the render pipeline's own routing.")
            .writableAt(SettingsLayer.DOCUMENT, SettingsLayer.MEMORY);

    /** {@code Queue = "..."} — when in the frame this draws. */
    public static final Setting<String> QUEUE = Setting.select(
            "shader.queue", "Queue",
            List.of("Background", "Geometry", "AlphaTest", "Transparent", "Overlay"), "Geometry")
            .description("Draw order. Transparent draws back-to-front after everything opaque.")
            .writableAt(SettingsLayer.DOCUMENT, SettingsLayer.MEMORY);

    /**
     * Declares all three, so a generated panel can enumerate them.
     *
     * <p>Explicit, like every command set in this engine: nothing here registers itself, because a
     * registry that quietly acquired declarations nobody asked for surprises anything that walks it —
     * and a preferences panel is precisely such a thing. Idempotent, since registering replaces.</p>
     */
    public static void register() {
        SettingsRegistry registry = SettingsRegistry.get();
        registry.register(VERTEX_FORMAT);
        registry.register(RENDER_TYPE);
        registry.register(QUEUE);
    }

    /** Every shader setting, in the order a panel should show them. */
    public static List<Setting<String>> all() {
        return List.of(VERTEX_FORMAT, RENDER_TYPE, QUEUE);
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
        master.vertexFormat(scope.resolve(VERTEX_FORMAT))
                .renderType(scope.resolve(RENDER_TYPE))
                .queue(scope.resolve(QUEUE));
    }
}
