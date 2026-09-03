package com.crystalgui.widget.control;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * An on/off switch whose knob slides between the ends.
 *
 * <h3>The knob is never positioned</h3>
 *
 * <p>An invisible {@link #SPACER_PART} sits before it with a {@code flex-grow} the stylesheet moves
 * between two values; Taffy relayouts and the knob is pushed across. Taken from LDLib2's
 * {@code Switch} with one deliberate difference — there the duration is hardcoded in Java, here it is
 * entirely in CSS:</p>
 *
 * <pre>{@code
 * switch::part(spacer)         { flex-grow: 0; transition: flex-grow 150ms ease; }
 * switch:checked::part(spacer) { flex-grow: 1; }
 * }</pre>
 *
 * <p>So this class holds no timing, no easing and no animation code at all: it flips
 * {@link #isChecked()} and the cascade does the rest, and restyling the speed is a stylesheet edit.</p>
 *
 * <p><b>Both endpoints must be explicit numbers.</b> The interpolators fall back to a binary snap at
 * the halfway point when two values have different units (notably {@code auto ↔ length}), so a rule
 * omitting one side gets a jump rather than a slide.</p>
 *
 * <h3>The track is the switch's own background</h3>
 *
 * <p>Not a part — so {@code switch} and {@code switch:checked} style it directly, with nothing to
 * reach through.</p>
 */
public class Switch extends UIElement {

    public static final Name NAME = Name.of("switch");

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

    /** Registered by {@link com.crystalgui.widget.Widgets} — never by a static block here. */
    public static final WidgetContract<Switch> CONTRACT = WidgetContracts.register(
            WidgetContract.of(Switch.class, "switch")
                    .state(CHECKED)
                    .event(TOGGLE)
                    .primary(CHECKED)
                    .build());

    /** The invisible box whose {@code flex-grow} pushes the knob. */
    public static final String SPACER_PART = "spacer";
    /** The knob itself. */
    public static final String KNOB_PART = "knob";

    /** Fires on every change, user-driven or programmatic, carrying the new value. */
    public final Signal.Value<Boolean> onCheckedChanged = new Signal.Value<>();

    private final ShadowRoot shadow;
    private final UIElement spacer;
    private final UIElement knob;
    private boolean checked;

    public Switch() {
        super(NAME);
        // Structure only -- no sizes and no durations, so a theme owns the entire look.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));

        // CLICK, so a click focuses it and Space then toggles the thing you just clicked. Button
        // carries the full reasoning; the three toggles match on purpose.
        setFocusPolicy(FocusPolicy.CLICK);

        this.shadow = attachShadow();

        this.spacer = new UIElement();
        spacer.set(Attribute.PART, SPACER_PART);
        spacer.setHitTest(false);
        shadow.append(spacer);

        this.knob = new UIElement();
        knob.set(Attribute.PART, KNOB_PART);
        knob.setHitTest(false);
        shadow.append(knob);

        attachDefaultListener(onMouseUp, (node, event) -> {
            // The left button only -- see Button, where the missing check meant a right-click
            // activated the control underneath a context menu.
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            if (event.isWasPressTarget() && isEnabled()) {
                CgPlatform.sound().play("button_click");
                setChecked(!checked);
            }
        });
    }

    /** Drives {@code :checked}, which the cascade reads straight off this getter. */
    @Override
    public boolean isChecked() {
        return checked;
    }

    public Switch setChecked(boolean value) {
        if (this.checked == value) return this;
        this.checked = value;
        invalidateStyleMatch();
        notifyStateChanged();
        onCheckedChanged.emit(value);
        return this;
    }

    /** The knob, for a subclass that needs to style or measure it. */
    protected final UIElement knob() {
        return knob;
    }

    /** This switch's shadow tree, for a subclass adding parts of its own. */
    protected final ShadowRoot shadow() {
        return shadow;
    }

    public Switch attachListener(Signal.Value.Listener<Boolean> action) {
        onCheckedChanged.connect(action);
        return this;
    }
}
