package com.crystalgui.ui.elements.inspector;

import com.crystalgui.core.data.DataContext;
import com.crystalgui.ui.elements.config.ConfigDescriptor;

/**
 * Something that can describe a subject to the {@link Inspector} — Blender's {@code Panel} plus its
 * {@code poll()}.
 *
 * <h3>Why the inspector has no types of its own</h3>
 *
 * <p>Blender's Properties editor works for a mesh, a light, a camera, a material, a keyframe and an
 * add-on's own datablock without knowing what any of them are. Three mechanisms do that, and this
 * interface is the first two:</p>
 *
 * <ol>
 *   <li>A panel is a <b>registered class</b> declaring which tab it belongs to ({@code bl_context}) and
 *       {@code poll(context)} — <b>whether it applies at all right now</b>. The editor draws every
 *       registered panel whose poll passes. A light shows no Modifiers tab not because the editor knows
 *       what a light is, but because those panels' poll returns false.</li>
 *   <li>The <b>subject comes from the context</b>, never from the editor: {@code context.object},
 *       {@code context.material}. That is a {@link DataContext}.</li>
 * </ol>
 *
 * <p>The third is {@code layout.prop(data, "location")} — properties drawn <em>reflectively</em> from
 * their declared type, so nobody writes a form per type. Ours is {@code ConfigDescriptor} /
 * {@code SettingsConfigurator}, and {@link #build} should prefer it over hand-placed widgets. <b>That is
 * the mechanism doing most of the work</b>: with contributions but no reflection you still hand-write a
 * form per type, you have only moved where it lives.</p>
 *
 * <h3>Hold nothing</h3>
 *
 * <p>A section is asked afresh whenever the subject changes, and is handed everything it needs. A section
 * that <em>caches</em> its subject is a per-type inspector again, with the lifetime bug that made the old
 * per-graph inspector map retain every graph ever opened.</p>
 */
public interface InspectorSection {

    /**
     * Which tab this belongs in — Blender's {@code bl_context}.
     *
     * <p>Tabs are <b>derived from the sections that applied</b>, never from a fixed list: a hardcoded
     * set is the old design wearing a registry.</p>
     */
    String tab();

    /**
     * Whether this applies to what is currently selected — Blender's {@code poll()}.
     *
     * <p>The extensibility hinge. Everything the inspector shows is the union of what answered yes, so a
     * feature adds a section and nothing else changes.</p>
     */
    boolean accepts(DataContext context);

    /**
     * Describes the subject into {@code form}. Called only when {@link #accepts} said yes.
     *
     * <h3>Fill a form; do not return a widget</h3>
     *
     * <p>Two sections sharing a tab write into <b>one</b> panel, which is what sharing a tab should look
     * like — returning an element each would stack two independently scrolling panels with two sets of
     * group headers and a visible seam. It also keeps the engine owning the engine-shaped parts: the
     * panel, its scrolling, its group collapse state, and when to clear it.</p>
     *
     * <p>Prefer {@link InspectorForm#row(ConfigDescriptor)} over building controls: a descriptor says
     * what the field <em>is</em> and the engine picks the widget, which is the mechanism that makes this
     * work for kinds of subject nobody has written yet.</p>
     */
    void build(InspectorForm form, DataContext context);

    /** Ordering within a tab. Declared, so two features cannot interleave by class-loading order. */
    default int order() {
        return 0;
    }

    /**
     * What this is currently describing, as a cheap identity — so an unchanged subject is not rebuilt.
     *
     * <h3>Why the engine cannot work this out</h3>
     *
     * <p>Only the section knows what "the same thing" means: one graph's selection re-asserting itself is
     * <em>not</em> a change, and a press on an already-selected node emits exactly that. Rebuilding there
     * replaces every control in the panel mid-gesture, which is the rule this codebase already states —
     * a widget must never rebuild the elements it is being clicked or dragged on.</p>
     *
     * <p>The default is a constant, so a section with no subject of its own rebuilds only when the set of
     * applicable sections changes. Anything selection-driven should override it.</p>
     */
    default String subjectKey(DataContext context) {
        return getClass().getName();
    }
}
