package com.crystalgui.graph.shader;

import com.crystalgui.ui.elements.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.ConfiguratorGroup;
import com.crystalgui.ui.elements.config.ConfiguratorPanel;

import java.util.List;
import java.util.Set;

/**
 * The node inspector — P6.1.8's control kit, as a panel a host can dock.
 *
 * <h3>The content is a PLACEHOLDER, and deliberately so</h3>
 *
 * <p><b>Nothing here is bound to the graph's selection yet.</b> The rows are the same fixed sample the
 * gallery's configurator page shows, ported across unchanged: they exercise every control kind the kit
 * has — text, number, ranged number, integral number, boolean, select, vector, group, nested group,
 * array, colour, mask, matrix, asset — and they answer to nothing. Selecting a node does not change them
 * and editing one changes no node.</p>
 *
 * <p>That is the requested scope, not an oversight, and it is worth being blunt about because an
 * inspector that <em>looks</em> live is the most misleading thing this panel could be. What it buys in the
 * meantime is real: the kit is reused across ~170 shader node types, so seeing every control together at
 * the size and in the frame it will actually be used in is how a half-pixel misalignment gets noticed
 * before it is baked into all of them.</p>
 *
 * <p>When it does get wired, {@link #panel()} is the seam — clear it and rebuild from the selected node's
 * {@link com.crystalgui.graph.NodeType} fields, which is the same description
 * {@code NodeFieldBinder} already drives the on-node editors from.</p>
 *
 * <h3>What was left behind in the gallery</h3>
 *
 * <p>The page's {@code Dialog} wrapper, its hint column and its change log. Those are review furniture for
 * a scene that has to make the kit inspectable on a page — a dock panel already has a frame, a title and
 * somewhere to live, so carrying them over would be carrying the gallery's chrome into the product.</p>
 */
public class ShaderNodeInspector extends ConfiguratorPanel {

    public ShaderNodeInspector() {
        // A plain section header: full-width band, no arrow, nothing to collapse -- Unity's "Target
        // Settings" caption, unlike the collapsible group below.
        add(ConfigDescriptor.header("Node Settings"), null);
        add(ConfigDescriptor.text("name", "Name").tooltip("Free text"), "Untitled");
        add(ConfigDescriptor.number("scale", "Scale"), 1.0);
        add(ConfigDescriptor.number("opacity", "Opacity").range(0f, 1f), 0.5);
        add(ConfigDescriptor.number("count", "Count").integral(true), 3);
        add(ConfigDescriptor.bool("exposed", "Exposed"), true);
        add(ConfigDescriptor.select("space", "Space",
                List.of("Object", "View", "World", "Tangent", "Absolute World")), "World");
        add(ConfigDescriptor.vector("offset", "Offset", 3), new double[]{0, 1, 0});
        add(ConfigDescriptor.vector("uv", "UV", 2), new double[]{0, 0});

        // A group, so the foldout and the indent are visible next to ungrouped rows rather than on a page
        // of their own -- depth only reads as depth against something that is not indented.
        ConfiguratorGroup advanced = new ConfiguratorGroup("Advanced");
        addChild(advanced);
        addTo(advanced.content(), ConfigDescriptor.number("bias", "Bias"), 0.0);
        addTo(advanced.content(), ConfigDescriptor.bool("clamp", "Clamp"), false);
        ConfiguratorGroup nested = new ConfiguratorGroup("Nested", true);
        advanced.content().addChild(nested);
        addTo(nested.content(), ConfigDescriptor.text("note", "Note"), "two levels deep");

        add(ConfigDescriptor.of("entries", "Entries", ConfigDescriptor.Kind.ARRAY)
                .element(ConfigDescriptor.text("entries.e", "")), List.of("alpha", "beta"));

        // The four remaining leaves, in a group of their own so they are easy to find and compare side by
        // side rather than scattered through the panel.
        ConfiguratorGroup leaves = new ConfiguratorGroup("Step 6");
        addChild(leaves);
        addTo(leaves.content(), ConfigDescriptor.color("tint", "Tint"), 0xFF3C8CFF);
        addTo(leaves.content(), ConfigDescriptor.mask("layers", "Layers",
                List.of("Default", "Water", "UI", "PostProcessing")), Set.of("Default", "Water"));
        addTo(leaves.content(), ConfigDescriptor.matrix("transform", "Transform", 4), null);
        addTo(leaves.content(), ConfigDescriptor.asset("shader", "Shader"), "Shaders/Lit.shader");
    }

    /**
     * This panel, as the surface a future binding rebuilds.
     *
     * <p>Returns {@code this} — the class <em>is</em> a {@link ConfiguratorPanel}, and the accessor exists
     * so callers name the seam rather than the inheritance. See the class note.</p>
     */
    public ConfiguratorPanel panel() {
        return this;
    }
}
