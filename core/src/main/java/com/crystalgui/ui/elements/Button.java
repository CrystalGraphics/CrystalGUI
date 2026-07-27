package com.crystalgui.ui.elements;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.input.FocusPolicy;

/**
 * Clickable button with an internal text label and optional pre/post icon slots.
 *
 * <h3>Activation semantics</h3>
 * <p>Holding the mouse down over the button is purely visual (the existing {@code isPressed}/
 * {@code ACTIVE} pseudo-class, unconditionally set by {@link com.crystalgui.ui.input.UIInputHandler}
 * — Button adds no code for this half). Releasing the mouse only activates the button if the release
 * landed on the same element that received the matching press — releasing elsewhere cancels the
 * press without activating. This is enforced via {@link com.crystalgui.ui.event.MouseEvent.Up#isWasPressTarget()},
 * a signal computed generically in {@code UIInputHandler} (not Button-specific), decorated here into
 * the {@link ButtonEvent.Pressed} event. Space/Enter activation while focused comes for free from
 * {@code UIInputHandler}'s generic keyboard-activation bridge, which synthesizes the same
 * {@code MouseEvent.Down}/{@code Up} a real click would — Button needs zero keyboard-specific code.</p>
 *
 * <h3>Internal children</h3>
 * <p>The label and any pre/post icon are real {@link UIElement} children marked
 * {@link UIElement#markAsInternal()} via {@link UIElement#addInternalChild}, not true CSS
 * pseudo-elements (no pseudo-element infra exists in the style engine) — they're styled with ordinary
 * class selectors ({@code __pre-icon__}/{@code __post-icon__}). {@link #acceptsPublicChildren()}
 * returns {@code false}: a Button has no legitimate public content slot of its own.</p>
 */
public class Button extends UIElement {

    static {
        ElementRegistry.register("button", () -> new Button(""));
    }

    public static final String PRE_ICON_CLASS = "__pre-icon__";
    public static final String POST_ICON_CLASS = "__post-icon__";


    public final Signal.Action onPressed = new Signal.Action();

    private final UIText label;
    private UIElement preIcon;
    private UIElement postIcon;

    public Button(String label) {
        this.label = new UIText(label == null ? "" : label);
        addInternalChild(this.label);
        this.label.setHitTest(false);
        this.setFocusPolicy(FocusPolicy.FOCUSABLE);

        this.attachDefaultListener(this.onMouseUp, (el, event) -> {
            if (event.isWasPressTarget() && isEnabled()) {
                CrystalGuiCore.getSoundSystem().play("button_click");
                onPressed.emit();
            }
        });
    }

    @Override
    protected boolean acceptsPublicChildren() {
        return false;
    }

    public String getText() {
        return label.getText();
    }

    public Button setText(String value) {
        label.setText(value);
        return this;
    }

    /** Sets (or clears, passing {@code null}) the icon shown before the label. */
    public Button setPreIcon(UIElement icon) {
        if (preIcon != null) removeInternalChild(preIcon);
        preIcon = icon;
        if (preIcon != null) {
            preIcon.addClass(PRE_ICON_CLASS);
            insertInternalChildAt(preIcon, 0);
        }
        return this;
    }

    /** Sets (or clears, passing {@code null}) the icon shown after the label. */
    public Button setPostIcon(UIElement icon) {
        if (postIcon != null) removeInternalChild(postIcon);
        postIcon = icon;
        if (postIcon != null) {
            postIcon.setHitTest(false);
            postIcon.addClass(POST_ICON_CLASS);
            addInternalChild(postIcon);
        }
        return this;
    }

    public Button attachListener(Runnable action) {
        onPressed.connect(action);
        return this;
    }
}
