package com.crystalgui.ui.shadow;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgraphics.platform.input.CgMouseCodes;
import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * <b>Spike S2's subject, and a THROWAWAY</b> — {@code Button} rebuilt as a host with a shadow root, so
 * the cost of the conversion can be counted rather than estimated. {@code plan_ui_rewrite.md} M0.
 *
 * <p><b>This is not a new kind of button and is not the first of fifty-four.</b> At M6 {@code Button}
 * itself gets a shadow root and this class is deleted; there is no {@code Shadow*} hierarchy and there
 * will not be one, because every widget existing twice means every bug fixed twice. See
 * {@code package-info.java} for what S2 asked and what it answered.
 *
 * <h3>The comparison, which is the point</h3>
 *
 * <p>{@link com.crystalgui.ui.elements.Button} builds the same three children with
 * {@code addInternalChild} and lets {@code ua/widgets.css} style them through {@code .__pre-icon__},
 * {@code .__post-icon__} and a bare descendant {@code text} rule. Every one of those class names is
 * global: nothing stops another sheet, or another widget, naming the same thing. This class exposes
 * the same three pieces as {@code label}, {@code pre-icon} and {@code post-icon} <b>parts</b>, and
 * they are reachable only as {@code shadowbutton::part(label)}.</p>
 *
 * <p>What that buys, stated as the difference a sheet can observe:</p>
 *
 * <table>
 *   <tr><th></th><th>internal children (today)</th><th>shadow parts</th></tr>
 *   <tr><td>Styling the label</td><td>{@code button text { }}</td><td>{@code shadowbutton::part(label) { }}</td></tr>
 *   <tr><td>An unrelated {@code .__content__} rule</td><td><b>reaches in</b></td><td>cannot</td></tr>
 *   <tr><td>A {@code * { }} rule</td><td>reaches in</td><td>cannot</td></tr>
 *   <tr><td>Exposed surface</td><td>every descendant</td><td>exactly what is named</td></tr>
 * </table>
 *
 * <h3>What it does not attempt</h3>
 *
 * <p>Activation semantics, keyboard activation, the left-button-only rule and the pressed state are
 * all inherited from the engine and deliberately re-used rather than re-derived — S2 is measuring
 * <em>encapsulation</em>, and a prototype that also reimplemented behaviour would report the cost of
 * both. The one behavioural thing it does carry is {@link FocusPolicy#CLICK}, because focus is the
 * third question (see {@link ShadowRoot#retarget}).</p>
 */
public class ShadowButton extends UIElement {

    /** Part names. Constants for the same reason the {@code __class__} names are: a typo is silent. */
    public static final String LABEL_PART = "label";
    public static final String PRE_ICON_PART = "pre-icon";
    public static final String POST_ICON_PART = "post-icon";

    public final Signal.Action onPressed = new Signal.Action();

    private final ShadowRoot shadow;
    private final UIText label;
    private UIElement preIcon;
    private UIElement postIcon;

    public ShadowButton(String text) {
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(), l -> l.flexDirection(FlexDirection.ROW));
        setFocusPolicy(FocusPolicy.CLICK);

        this.shadow = ShadowRoot.attachTo(this);
        // The root is laid out as a row that fills the host, so the host's own box still governs the
        // button's geometry. A real implementation has no root box at all -- the web's shadow root is
        // a DocumentFragment and its children are the host's -- which is one of the things `ui.dom`
        // has to provide and this prototype cannot fake.
        StyleGroup.defaultPipeline(shadow.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW).widthPercent(100f).heightPercent(100f));

        this.label = ShadowRoot.part(new UIText(text == null ? "" : text), LABEL_PART);
        this.label.setHitTest(false);
        shadow.addChild(this.label);

        attachDefaultListener(this.onMouseUp, (el, event) -> {
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            if (event.isWasPressTarget() && isEnabled()) onPressed.emit();
        });
    }

    /** The shadow tree, for a test that needs to look inside one. Nothing in a sheet can. */
    public ShadowRoot shadowRoot() {
        return shadow;
    }

    public UIText label() {
        return label;
    }

    public ShadowButton setText(String text) {
        label.setText(text == null ? "" : text);
        return this;
    }

    /** Adds the leading icon slot, exposed as {@code ::part(pre-icon)}. */
    public UIElement preIcon() {
        if (preIcon == null) {
            preIcon = ShadowRoot.part(new UIElement(), PRE_ICON_PART);
            preIcon.setHitTest(false);
            shadow.addChildAt(preIcon, 0);
        }
        return preIcon;
    }

    /** Adds the trailing icon slot, exposed as {@code ::part(post-icon)}. */
    public UIElement postIcon() {
        if (postIcon == null) {
            postIcon = ShadowRoot.part(new UIElement(), POST_ICON_PART);
            postIcon.setHitTest(false);
            shadow.addChild(postIcon);
        }
        return postIcon;
    }

    /** A shadow host has no public content slot, for the same reason {@code Button} has none. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}
