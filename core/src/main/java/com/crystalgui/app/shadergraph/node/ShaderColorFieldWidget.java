package com.crystalgui.app.shadergraph.node;

import com.crystalgui.graph.NodeField;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.form.ColorSelector;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.widget.config.control.ColorControl;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.node.NodeFieldWidgets;

import java.util.Locale;

/**
 * Puts {@link ColorControl} behind every {@link NodeField.Kind#COLOR} field — a swatch you click to
 * open the full {@link ColorSelector} picker.
 *
 * <h3>Why the registration lives here and not with the widget</h3>
 * <p>{@code NodeFieldWidgets} is domain-agnostic: it maps a <em>kind</em> to a control and knows nothing
 * about what the value means. A shader colour is spelled {@code vec4(1.0, 0.5, 0.0, 1.0)}, which is GLSL
 * — so the parsing and formatting are the shader side's business, and this is the shader side. A
 * different graph domain storing colours as {@code #RRGGBB} registers its own and gets the same picker
 * with its own spelling.</p>
 *
 * <h3>Since P6.1.8 step 7: the picker is {@link ColorControl}, not a hand-rolled swatch</h3>
 * <p>This class used to build its own swatch, its own promoted-{@code Dialog} toggle logic, and its own
 * pointer-anchored placement — all of which {@link ColorControl} now does, because the inspector's
 * colour row needed exactly the same thing. What is left here is only what is genuinely GLSL's: parsing
 * and formatting {@code vec4(...)}.</p>
 *
 * <h3>Full width in the node body, labelled in the inspector — the same control, a host's own call</h3>
 * <p>{@link ColorControl#selfLabelling()} is {@code false}: Unity's inspector shows a label beside a
 * colour field ({@code docs/research/unity-inspector/01-inspector-property.png}), so the general case
 * keeps one. A node is ~130px wide, where a label would either dwarf the swatch or shrink it to
 * unusability — the same trade-off this class reasoned through before {@code ConfigControl} existed —
 * so this build method claims {@link GraphNode#FULL_WIDTH_CLASS} explicitly rather than asking the
 * control to decide. That is not a special case the kit failed to anticipate: a control's
 * {@code selfLabelling()} is the DEFAULT a row honours, and a caller placing it who knows its own
 * constraints — exactly what a node body is — may still override that call, the same way it always
 * could before this control had an opinion of its own.</p>
 */
public final class ShaderColorFieldWidget {

    private ShaderColorFieldWidget() {
    }

    /**
     * Registers the picker for colour fields. Idempotent, and safe to call before any GL exists —
     * nothing here touches a material until something paints.
     */
    public static void install() {
        NodeFieldWidgets.register(NodeField.Kind.COLOR, ShaderColorFieldWidget::build);
        // The inverse, so an edit made anywhere other than through this swatch reaches it — undo being
        // the case that matters. setValueObject is silent, so this cannot echo back out as a new edit.
        NodeFieldWidgets.registerApplier(NodeField.Kind.COLOR, (control, field, value) -> {
            if (control instanceof ColorControl swatch) swatch.setValue(parseVec4(field.resolve(value)));
        });
    }

    private static UINode build(NodeField field, String value, java.util.function.Consumer<String> onChange) {
        ConfigDescriptor descriptor = ConfigDescriptor.color(field.id(), field.label());
        ColorControl control = new ColorControl(descriptor, parseVec4(field.resolve(value)));
        // See the class javadoc: full width is this call site's decision, not the control's default.
        control.addClass(GraphNode.FULL_WIDTH_CLASS);
        control.changed.connect(argb -> onChange.accept(formatVec4((Integer) argb)));
        return control;
    }

    /**
     * {@code vec4(r, g, b, a)} with components 0..1, to ARGB.
     *
     * <p>Tolerant on purpose: a malformed literal returns opaque white rather than throwing. The value
     * is a document string a user can type into, so "not yet valid" is a normal state — and a picker
     * that refused to open on a typo would be the only way to fix it.</p>
     */
    static int parseVec4(String literal) {
        if (literal == null) return 0xFFFFFFFF;
        int open = literal.indexOf('(');
        int close = literal.lastIndexOf(')');
        if (open < 0 || close <= open) return 0xFFFFFFFF;

        String[] parts = literal.substring(open + 1, close).split(",");
        float[] rgba = { 1f, 1f, 1f, 1f };
        for (int i = 0; i < Math.min(4, parts.length); i++) {
            try {
                rgba[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException malformed) {
                return 0xFFFFFFFF;
            }
        }
        // A one-argument vec4 broadcasts, exactly as GLSL does — vec4(1.0) is opaque white, and reading
        // it as (1,1,1,1) rather than (1,0,0,0) is the difference between a white swatch and a red one.
        if (parts.length == 1) {
            rgba[1] = rgba[0];
            rgba[2] = rgba[0];
            rgba[3] = rgba[0];
        }
        return (channel(rgba[3]) << 24) | (channel(rgba[0]) << 16) | (channel(rgba[1]) << 8) | channel(rgba[2]);
    }

    /** ARGB back to a GLSL literal, at a fixed precision so an unchanged colour is an unchanged string. */
    static String formatVec4(int argb) {
        return String.format(Locale.ROOT, "vec4(%.3f, %.3f, %.3f, %.3f)",
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f);
    }

    private static int channel(float unit) {
        return Math.max(0, Math.min(255, Math.round(unit * 255f)));
    }
}
