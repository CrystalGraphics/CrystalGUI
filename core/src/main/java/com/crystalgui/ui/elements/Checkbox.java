package com.crystalgui.ui.elements;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * Toggle button with an internal check-mark and an internal, click-inclusive label.
 *
 * <h3>Activation semantics</h3>
 * <p>Implements the same {@code wasPressTarget}-decorated activation listener Button uses, directly
 * on itself rather than by composing an internal {@code Button} — that keeps {@code :hover}/{@code
 * :active}/{@code :focus}/{@code :checked} all matching Checkbox's own root, the same way they match
 * Button's, instead of forcing selectors against an internal child. See
 * {@link com.crystalgui.ui.event.MouseEvent.Up#isWasPressTarget()} and {@code UIInputHandler}'s
 * generic Space/Enter keyboard-activation bridge (both shared, not Checkbox-specific).</p>
 *
 * <h3>Check-mark visual</h3>
 * <p>The mark is a plain internal {@link UIElement} with the {@link #MARK_CLASS} class — its
 * appearance is entirely CSS-driven via the {@code :checked} pseudo-class (already wired to
 * {@link #isChecked()} by {@code style/PseudoClasses.java}), e.g. {@code checkbox .__mark__ {
 * opacity: 0; } checkbox:checked .__mark__ { opacity: 1; }}. Checkbox itself does no conditional
 * styling in Java — it only ever sets/reads {@link #isChecked()}.</p>
 */
public class Checkbox extends UIElement {

    public static final String MARK_CLASS = "__mark__";

    /** Fires on every checked-state change (both user-driven and programmatic {@link #setChecked}),
     * carrying the new value. */
    public final Signal.Value<Boolean> onCheckedChanged = new Signal.Value<>();

    private final UIElement mark;
    private final UIText label;
    private boolean checked = false;
    private CheckboxGroup group;

    public Checkbox() {
        this("");
    }

    public Checkbox(String label) {
        // DEFAULT origin — lowest cascade priority, so any stylesheet rule targeting `checkbox`/a
        // class still wins over this without needing `!important`.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(), l -> l.flexDirection(FlexDirection.ROW));

        this.mark = new UIElement();
        this.mark.addClass(MARK_CLASS);
        addInternalChild(this.mark);
        this.mark.setHitTest(false);

        this.label = new UIText(label == null ? "" : label);
        addInternalChild(this.label);
        this.label.setHitTest(false);

        this.setFocusPolicy(FocusPolicy.FOCUSABLE);
        this.attachDefaultListener(this.onMouseUp, (el, event) -> {
            if (event.isWasPressTarget() && isEnabled()) {
                CrystalGuiCore.getSoundSystem().play("button_click");
                setChecked(!checked);
            }
        });
    }

    @Override
    protected boolean acceptsPublicChildren() {
        return false;
    }

    @Override
    public boolean isChecked() {
        return checked;
    }

    public Checkbox setChecked(boolean value) {
        if (this.checked == value) return this;
        this.checked = value;
        onStyleChanged();
        invalidateStyleMatch();
        onCheckedChanged.emit(value);
        return this;
    }

    public String getLabel() {
        return label.getText();
    }

    public Checkbox setLabel(String value) {
        label.setText(value);
        return this;
    }

    public Checkbox attachListener(Signal.Value.Listener<Boolean> action) {
        onCheckedChanged.connect(action);
        return this;
    }

    /** Joins {@code group}, which takes over exclusivity enforcement via {@link #onCheckedChanged} —
     * Checkbox itself stays group-agnostic. Pass {@code null} to just forget the reference (the group
     * itself has no {@code unregister} call site here; call {@link CheckboxGroup#unregister} directly
     * if you need to actually leave the group). */
    public Checkbox setGroup(CheckboxGroup group) {
        this.group = group;
        if (group != null) group.register(this);
        return this;
    }
}
