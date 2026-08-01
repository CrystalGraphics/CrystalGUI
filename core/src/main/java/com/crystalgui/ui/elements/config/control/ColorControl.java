package com.crystalgui.ui.elements.config.control;

import com.crystalgui.core.data.Transform2D;
import com.crystalgui.render.texture.CgUiQuad;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.ColorSelector;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.elements.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.ValueControl;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * ARGB, edited through a swatch that opens the full {@link ColorSelector}.
 *
 * <p>Unity reference: {@code docs/research/unity-inspector/01-inspector-property.png}'s colour row and
 * {@code docs/research/unity-nodes/01-color-field.png}.</p>
 *
 * <h3>Ported from {@code ShaderColorFieldWidget}, minus the GLSL</h3>
 * <p>The graph's colour field already solved "a swatch that opens a picker in a promoted, draggable,
 * click-to-toggle {@link Dialog}" — including the two reasons neither a bare {@link
 * com.crystalgui.ui.elements.Popover} (not draggable) nor {@code showModal()} (makes the whole document
 * inert) is right for a picker twice the width of what opens it. This is that same shape working in
 * ARGB directly: an inspector already has the int, and asking it to round-trip through
 * {@code vec4(...)} text would discard a type only to immediately reparse it. A shader-graph consumer
 * that wants the GLSL spelling keeps using {@code ShaderColorFieldWidget} at the graph boundary — this
 * control never needs to know that spelling exists.</p>
 */
public class ColorControl extends ValueControl<Integer> {

    public static final String SWATCH_CLASS = "__swatch__";
    public static final String COLOR_BAR_CLASS = "__color-bar__";
    public static final String ALPHA_BAR_CLASS = "__alpha-bar__";
    public static final String ALPHA_FILL_CLASS = "__alpha-fill__";

    private final UIElement swatch = new UIElement();
    private final UIElement colorBar = new UIElement();
    private final UIElement alphaFill = new UIElement();
    private final Dialog dialog = new Dialog("Color");
    private final ColorSelector picker = new ColorSelector();

    public ColorControl(ConfigDescriptor descriptor, @Nullable Integer defaultValue) {
        super(descriptor, defaultValue == null ? 0xFFFFFFFF : defaultValue);
        addClass("__color__");
        markAsInternal();

        swatch.addClass(SWATCH_CLASS);
        colorBar.addClass(COLOR_BAR_CLASS);
        UIElement alphaBar = new UIElement();
        alphaBar.addClass(ALPHA_BAR_CLASS);
        alphaFill.addClass(ALPHA_FILL_CLASS);
        alphaBar.addChild(alphaFill);
        swatch.addChild(colorBar);
        swatch.addChild(alphaBar);
        // The parts are scenery; the swatch takes every press, same as ShaderColorFieldWidget's — a
        // thin alpha strip is effectively unclickable on its own.
        colorBar.setHitTest(false);
        alphaBar.setHitTest(false);

        paint(getValue());
        picker.setInitialColor(getValue());
        picker.onColorChanged.connect(argb -> {
            paint(argb);
            commit(argb);
        });
        dialog.getContent().addChild(picker);
        swatch.addChild(dialog);

        swatch.onMouseDown.attachListener((el, event) -> {
            event.stopPropagation();
            if (dialog.isOpen()) {
                dialog.close();
                return;
            }
            dialog.show();
            UIWindow window = swatch.getAttachedWindow();
            if (window == null) return;
            // Promoted BY HAND: Dialog.show() is modeless, and the spec promotes only showModal() —
            // see ShaderColorFieldWidget's own note on why showModal() (inert whole document) and a
            // bare Popover (not draggable) are both wrong for this.
            window.getTopLayer().add(dialog);
            placeAtPointer(window, dialog, event.getPosition().x(), event.getPosition().y());
        }, false, false);

        addInternalChild(swatch);
    }

    /** The colour opaque, the alpha as a proportional width — reading a translucent colour against
     * whatever sits behind the row is not something an eye can do reliably. */
    private void paint(@Nullable Integer value) {
        int argb = value == null ? 0xFFFFFFFF : value;
        colorBar.generalStyle(g -> g.background(new CgUiQuad(argb | 0xFF000000)));
        float alpha = ((argb >>> 24) & 0xFF) / 255f;
        alphaFill.layout(l -> l.widthPercent(alpha * 100f));
    }

    @Override
    protected void writeToWidgets(@Nullable Integer value) {
        paint(value);
        picker.setColor(value == null ? 0xFFFFFFFF : value);
    }

    /** As {@code ShaderColorFieldWidget.placeAtPointer} — the pointer, not the swatch, and the world
     * coordinate has to come back through the root transform, the one definition of {@code uiScale}. */
    private static void placeAtPointer(UIWindow window, Dialog dialog, float worldX, float worldY) {
        var local = Transform2D.apply(new Matrix4f(window.getRootTransform()).invert(), worldX, worldY);
        dialog.moveTo(local.x() - 6f, local.y() - 6f);
    }

    /** The swatch, for a host that needs to reach the widget directly. */
    public UIElement swatch() {
        return swatch;
    }

    /** The picker itself, for a host that wants to react to the session (e.g. HDR mode) rather than
     * only the committed value. */
    public ColorSelector picker() {
        return picker;
    }
}
