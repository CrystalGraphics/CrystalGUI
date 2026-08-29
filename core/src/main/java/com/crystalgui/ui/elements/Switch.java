package com.crystalgui.ui.elements;

import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.State;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgraphics.platform.CgPlatform;

/**
 * On/off switch with a knob that slides between the two ends.
 *
 * <h3>How the slide works</h3>
 * <p>The knob is never positioned directly. An invisible {@link #SPACER_CLASS} sibling sits before
 * it with a {@code flex-grow} that the stylesheet moves between two values; Taffy relayouts and the
 * knob is pushed across. Borrowed from LDLib2's {@code Switch}, with one deliberate difference:
 * there the duration is hardcoded in Java ({@code new Animation(0.1f, 0, Eases.LINEAR)}), whereas
 * here it lives entirely in CSS:</p>
 *
 * <pre>{@code
 * switch .__spacer__          { flex-grow: 0; transition: flex-grow 150ms ease; }
 * switch:checked .__spacer__  { flex-grow: 1; }
 * }</pre>
 *
 * <p>This element therefore contains no timing, no easing, and no animation code at all — it only
 * flips {@link #isChecked()}, and the cascade does the rest. Restyling the speed is a stylesheet
 * edit.</p>
 *
 * <p><b>Both endpoints must be explicit numbers.</b> The interpolators fall back to a binary snap at
 * the halfway point when the two values have different units (notably {@code auto ↔ length}), so a
 * rule that omits one side gets a jump instead of a slide.</p>
 *
 * <h3>Track</h3>
 * <p>The track is the element's <em>own</em> background rather than a child, so {@code switch} and
 * {@code switch:checked} style it directly.</p>
 */
public class Switch extends UIElement {

    public static final State<Switch, Boolean> CHECKED =
            State.<Switch, Boolean>of("checked", StateTypes.BOOL,
                            Switch::isChecked, Switch::setChecked, false)
                    .omittedWhen(false);

    public static final Event<Switch, Boolean> TOGGLE = Event.of("toggle",
            (toggle, sink) -> toggle.attachListener(sink::accept),
            new Event.Payload<Boolean>() {
                @Override public <T> void write(StateMap<T> out, Boolean value) {
                    out.putBool("checked", value);
                }
                @Override public <T> Boolean read(StateMap<T> in) {
                    return in.getBool("checked", false);
                }
            }, RatePolicy.IMMEDIATE);

    public static final WidgetContract<Switch> CONTRACT = WidgetContracts.register(
            WidgetContract.of(Switch.class, "switch")
                    .state(CHECKED)
                    .event(TOGGLE)
                    .build());

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public static final String SPACER_CLASS = "__spacer__";
    public static final String KNOB_CLASS = "__knob__";

    /** Fires on every state change, user-driven or programmatic, carrying the new value. */
    public final Signal.Value<Boolean> onCheckedChanged = new Signal.Value<>();

    private final UIElement spacer;
    private final UIElement knob;
    private boolean checked = false;

    public Switch() {
        // Structure only — no sizes and no durations, so a theme owns the entire look.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));

        this.spacer = new UIElement();
        this.spacer.addClass(SPACER_CLASS);
        addInternalChild(this.spacer);
        this.spacer.setHitTest(false);

        this.knob = new UIElement();
        this.knob.addClass(KNOB_CLASS);
        addInternalChild(this.knob);
        this.knob.setHitTest(false);

        // CLICK so a click focuses it and Space then toggles the thing you just clicked — see the
        // fuller reasoning on Button, which this matches deliberately. No ring on pointer focus;
        // `:focus-visible` handles that.
        this.setFocusPolicy(FocusPolicy.CLICK);
        // Same press-and-release-on-the-same-element contract as Button/Checkbox; Space/Enter
        // activation arrives for free through UIInputHandler's generic keyboard bridge.
        this.attachDefaultListener(this.onMouseUp, (el, event) -> {
            if (event.isWasPressTarget() && isEnabled()) {
                CgPlatform.sound().play("button_click");
                setChecked(!checked);
            }
        });
    }

    /** Drives the {@code :checked} pseudo-class — {@code UIElement}'s hook is documented as reserved
     * for "checkboxes / on-off sliders", which is exactly this. */
    @Override
    public boolean isChecked() {
        return checked;
    }

    public Switch setChecked(boolean value) {
        if (this.checked == value) return this;
        this.checked = value;
        onStyleChanged();
        invalidateStyleMatch();
        notifyStateChanged();
        onCheckedChanged.emit(value);
        return this;
    }

    public Switch attachListener(Signal.Value.Listener<Boolean> action) {
        onCheckedChanged.connect(action);
        return this;
    }
}
