package com.crystalgui.graph.shader;

import com.crystalgui.graph.NodeField;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.ColorSelector;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.elements.graph.NodeFieldWidgets;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.core.data.Transform2D;
import org.joml.Matrix4f;

import java.util.Locale;

/**
 * Puts the {@link ColorSelector} behind every {@link NodeField.Kind#COLOR} field — a swatch you click to
 * open a picker.
 *
 * <h3>Why the registration lives here and not with the widget</h3>
 * <p>{@code NodeFieldWidgets} is domain-agnostic: it maps a <em>kind</em> to a control and knows nothing
 * about what the value means. A shader colour is spelled {@code vec4(1.0, 0.5, 0.0, 1.0)}, which is GLSL
 * — so the parsing and formatting are the shader side's business, and this is the shader side. A
 * different graph domain storing colours as {@code #RRGGBB} registers its own and gets the same picker
 * with its own spelling.</p>
 *
 * <h3>A promoted Dialog, not an inline picker and not a Popover</h3>
 * <p>A node is ~130px wide and the picker is twice that. Inline it would either dwarf the node or be
 * shrunk to unusability, so the swatch is the control and the picker is what the swatch summons —
 * which is also what Unity does.</p>
 *
 * <p><b>Both obvious presentations are wrong here, for opposite reasons.</b> A {@code Popover} promotes
 * to the top layer but is not draggable, and a picker that cannot be moved sits on top of the node it is
 * editing. A {@code Dialog} is draggable but {@code show()} is modeless, and the spec promotes only
 * {@code showModal()} — so on a graph canvas it stays inside the pan/zoom-transformed plane and comes out
 * beneath the nodes, positioned in plane space, and drawn at the canvas zoom rather than the UI scale.
 * The answer is a modeless dialog promoted explicitly: {@code TopLayer.add} is public exactly so a caller
 * can ask for "above everything" without also asking for "and nothing else may be touched", which is
 * what {@code showModal()} would impose on the whole graph.</p>
 */
public final class ShaderColorFieldWidget {

    public static final String SWATCH_CLASS = "__color-swatch__";
    public static final String COLOR_BAR_CLASS = "__color-bar__";
    public static final String ALPHA_BAR_CLASS = "__alpha-bar__";
    public static final String ALPHA_FILL_CLASS = "__alpha-fill__";

    private ShaderColorFieldWidget() {
    }

    /**
     * Registers the picker for colour fields. Idempotent, and safe to call before any GL exists —
     * nothing here touches a material until something paints.
     */
    public static void install() {
        NodeFieldWidgets.register(NodeField.Kind.COLOR, ShaderColorFieldWidget::build);
    }

    private static UIElement build(NodeField field, String value, java.util.function.Consumer<String> onChange) {
        UIElement swatch = new UIElement();
        swatch.addClass(SWATCH_CLASS);
        // Takes the whole row, no label. The swatch IS the value drawn — "Value: [a colour]" says
        // nothing the colour does not, and the word costs the bar the width it needs to be judged.
        swatch.addClass(com.crystalgui.ui.elements.graph.GraphNode.FULL_WIDTH_CLASS);
        // Two bars, Unity's arrangement: the colour at full opacity above, its alpha as a proportional
        // strip below. A single quad painted at the real alpha cannot express it — it blends against the
        // node behind it, so a translucent colour reads as a darker opaque one and every alpha below
        // about a third is indistinguishable from the node itself. Splitting them means the colour is
        // always legible AND the alpha is always readable, neither at the other's expense.
        UIElement colorBar = new UIElement();
        colorBar.addClass(COLOR_BAR_CLASS);
        UIElement alphaBar = new UIElement();
        alphaBar.addClass(ALPHA_BAR_CLASS);
        UIElement alphaFill = new UIElement();
        alphaFill.addClass(ALPHA_FILL_CLASS);
        alphaBar.addChild(alphaFill);
        swatch.addChild(colorBar);
        swatch.addChild(alphaBar);
        // The parts are scenery; the swatch takes every press. Without this the click target is whichever
        // stripe the pointer happened to land on, and the 2px alpha bar is effectively unclickable.
        colorBar.setHitTest(false);
        alphaBar.setHitTest(false);

        int[] current = { parseVec4(field.resolve(value)) };
        paint(colorBar, alphaFill, current[0]);

        // A Dialog — the picker's own presentation, title bar and all, and draggable by its head.
        Dialog dialog = new Dialog("Color");
        ColorSelector picker = new ColorSelector();
        // The session starts here, so the picker's "original" is what the node currently holds — that is
        // what its reset swatch has to restore to.
        picker.setInitialColor(current[0]);
        picker.onColorChanged.connect(argb -> {
            current[0] = argb;
            paint(colorBar, alphaFill, argb);
            onChange.accept(formatVec4(argb));
        });
        dialog.getContent().addChild(picker);
        swatch.addChild(dialog);

        swatch.onMouseDown.attachListener((el, event) -> {
            // Consumed, or the press keeps travelling and GraphNode starts dragging the node out from
            // under the picker that is opening on top of it.
            event.stopPropagation();
            if (dialog.isOpen()) {
                dialog.close();
                return;
            }
            dialog.show();
            // PROMOTED BY HAND, and this is the crux of the whole widget. Dialog.show() is modeless and
            // the spec promotes only showModal(), so without this the dialog stays inside the graph's
            // pan/zoom-transformed plane: painted under sibling nodes, positioned in plane space so
            // dragging fights the transform, and drawn at the canvas ZOOM rather than the UI scale — so
            // every measurement in the picker comes out a size it was never tuned at.
            //
            // showModal() would promote, but at the price of making the entire graph inert: the node
            // being edited could not be seen against anything, and no other node could be selected while
            // a colour was open. Promoting a modeless dialog is the combination this needs and the one
            // the spec has no shorthand for — the top layer is a list, and TopLayer.add is public
            // precisely so a caller can say "above everything" without also saying "and nothing else may
            // be touched".
            UIWindow window = swatch.getAttachedWindow();
            if (window == null) return;
            window.getTopLayer().add(dialog);
            // Re-anchored on EVERY open, not just the first: the press is always on the swatch, so the
            // picker always appears next to the node it belongs to. Remembering a dragged position
            // instead would open the picker for one node on top of a different one.
            placeAtPointer(window, dialog, event.getPosition().x(), event.getPosition().y());
        }, false, false);
        return swatch;
    }

    /**
     * Opens the dialog at the pointer.
     *
     * <p>Without this it lands at the top-left corner of the window: a promoted element's containing
     * block is the root, and nothing has written it a {@code left}/{@code top} yet, so it sits at the
     * origin — which on a panned graph canvas is nowhere near the node being edited.</p>
     *
     * <p><b>The cursor, not the swatch.</b> Anchoring to the swatch means chasing it through the plane's
     * pan and zoom, and it puts the picker over the node whose colour is being judged. The pointer is
     * already where the user is looking, it is one press away from the title bar so the window can be
     * dragged straight off without re-aiming, and it is the same convention as a context menu.</p>
     *
     * <p>The press position is in <b>world</b> coordinates and {@code left}/{@code top} are in the root's
     * space, so it has to come back through the root transform — the one definition of {@code uiScale}.
     * Skip that and the dialog opens at a point multiplied by the UI scale, which looks right at 1x and
     * drifts further off the cursor at every step above it.</p>
     */
    private static void placeAtPointer(UIWindow window, Dialog dialog, float worldX, float worldY) {
        var local = Transform2D.apply(
                new Matrix4f(window.getRootTransform()).invert(), worldX, worldY);
        // Slightly up and left of the cursor, so it opens ON the title bar rather than with its corner
        // exactly under the pointer. moveTo clamps into the containing block, so a press near an edge
        // slides the dialog back on screen instead of opening it half outside the window.
        dialog.moveTo(local.x() - 6f, local.y() - 6f);
    }

    /**
     * The colour opaque, the alpha as a width.
     *
     * <p>Forcing the bar opaque is the point of having two of them: alpha is reported by the strip's
     * length, which is exact and readable at any value, instead of by a transparency the eye has to
     * judge against whatever is behind the node.</p>
     */
    private static void paint(UIElement colorBar, UIElement alphaFill, int argb) {
        colorBar.generalStyle(g -> g.background(new CgUiQuad(argb | 0xFF000000)));
        float alpha = ((argb >>> 24) & 0xFF) / 255f;
        alphaFill.layout(l -> l.widthPercent(alpha * 100f));
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
