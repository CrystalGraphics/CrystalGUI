package com.crystalgui.widget.control;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import javax.annotation.Nullable;

/**
 * A toggle with a check-mark and a click-inclusive label.
 *
 * <h3>Activation is its own, not a composed {@link Button}</h3>
 *
 * <p>It carries the same {@code wasPressTarget}-decorated listener {@code Button} does rather than
 * wrapping one, and that is deliberate: composing a button would put {@code :hover}, {@code :active},
 * {@code :focus} and {@code :checked} on an inner node, so every rule in every theme would have to
 * reach through to it. Written directly, they all match the checkbox's own root, exactly as they
 * match a button's.</p>
 *
 * <h3>The mark is CSS, and Java never looks at it</h3>
 *
 * <p>{@link #MARK_PART} is a bare node whose whole appearance comes from {@code :checked} — which the
 * cascade wires to {@link #isChecked()} for free, the pseudo-class being nothing but a call to that
 * getter. So this class only ever sets and reads a boolean, and a theme decides what checked
 * <em>looks</em> like. That is the widget-layer rule stated in the small: no sizes, no colours and no
 * conditional styling in Java.</p>
 */
public class Checkbox extends UINode {

    public static final Name NAME = Name.of("checkbox");

    public static final State<Checkbox, Boolean> CHECKED =
            State.<Checkbox, Boolean>of("checked", StateTypes.BOOL,
                            Checkbox::isChecked, Checkbox::setChecked, false)
                    .omittedWhen(false);

    public static final State<Checkbox, String> LABEL =
            State.<Checkbox, String>of("label", StateTypes.STRING,
                            Checkbox::getLabel, Checkbox::setLabel, "")
                    .omittedWhen("");

    public static final Event<Checkbox, Boolean> TOGGLE = Event.of("toggle",
            (checkbox, sink) -> checkbox.attachListener(sink::accept),
            new Event.Payload<Boolean>() {
                @Override public <T> void write(StateMap<T> out, Boolean value) {
                    out.putBool("checked", value);
                }
                @Override public <T> Boolean read(StateMap<T> in) {
                    return in.getBool("checked", false);
                }
            }, RatePolicy.IMMEDIATE);

    /**
     * <b>LABEL before CHECKED</b>, and the order is why a contract is ordered at all: a
     * {@link CheckboxGroup} can refuse a check on arrival, so the widget has to already look like
     * itself when it does. The hand-written {@code readState} this replaced set them in the same
     * order, where nothing could see the requirement and nothing could check it.
     *
     * <p>Registered by {@link com.crystalgui.widget.Widgets} — see
     * {@link com.crystalgui.ui.dom.NodeKinds} for why a widget must not register itself.</p>
     */
    public static final WidgetContract<Checkbox> CONTRACT = WidgetContracts.register(
            WidgetContract.of(Checkbox.class, "checkbox")
                    .state(LABEL)
                    .state(CHECKED)
                    .event(TOGGLE)
                    .primary(CHECKED)
                    .build());

    /** The check-mark. {@code checkbox::part(mark)} in a sheet — a leaf in every shipped rule. */
    public static final String MARK_PART = "mark";
    /** The label beside it. */
    public static final String LABEL_PART = "label";

    /**
     * Fires on every change, user-driven or programmatic, carrying the new value.
     *
     * <p>Programmatic included, because {@link CheckboxGroup} enforces exclusivity by listening to it
     * — a signal that fired only for real clicks would leave a group unable to see the member it had
     * just unchecked itself.</p>
     */
    public final Signal.Value<Boolean> onCheckedChanged = new Signal.Value<>();

    private final ShadowRoot shadow;
    private final UINode mark;
    private final UIText label;
    private boolean checked;
    @Nullable
    private CheckboxGroup group;

    /** The no-argument constructor the registry's factory needs. See {@link Button#Button()}. */
    public Checkbox() {
        this("");
    }

    public Checkbox(@Nullable String label) {
        super(NAME);
        // DEFAULT origin -- the lowest there is, so any rule naming `checkbox` or a class still wins
        // without !important. The engine may not write at IMPORTANT on this host at all.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(), l -> l.flexDirection(FlexDirection.ROW));

        // CLICK, so clicking focuses it and Space then toggles the thing you just clicked. Button
        // carries the full reasoning; the two match on purpose.
        setFocusPolicy(FocusPolicy.CLICK);

        this.shadow = attachShadow();

        this.mark = new UINode();
        mark.set(Attribute.PART, MARK_PART);
        mark.setHitTest(false);
        shadow.append(mark);

        this.label = new UIText(label == null ? "" : label);
        this.label.set(Attribute.PART, LABEL_PART);
        this.label.setHitTest(false);
        shadow.append(this.label);

        attachDefaultListener(onMouseUp, (node, event) -> {
            // The LEFT button only -- see Button, where a right-click activating the control was a
            // shipped defect for as long as nothing put a second gesture on one.
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            if (event.isWasPressTarget() && isEnabled()) {
                CgPlatform.sound().play("button_click");
                setChecked(!checked);
            }
        });
    }

    @Override
    public boolean isChecked() {
        return checked;
    }

    public Checkbox setChecked(boolean value) {
        if (this.checked == value) return this;
        this.checked = value;
        // :checked is a call to isChecked(), so the cascade has no way to know the answer moved.
        invalidateStyleMatch();
        notifyStateChanged();
        onCheckedChanged.emit(value);
        return this;
    }

    public String getLabel() {
        return label.text();
    }

    public Checkbox setLabel(String value) {
        // No notifyStateChanged here: UIText.setText fires its own, and that walks out of the
        // shadow tree onto this Checkbox -- whose contract carries the label. Doing both would be
        // harmless (the dirty set dedups) and would imply the wiring is per-widget when it is not.
        label.setText(value);
        return this;
    }

    /** The mark node, for a subclass that needs to style or measure it. */
    protected final UINode mark() {
        return mark;
    }

    /** This checkbox's shadow tree, for a subclass adding parts of its own. */
    protected final ShadowRoot shadow() {
        return shadow;
    }

    public Checkbox attachListener(Signal.Value.Listener<Boolean> action) {
        onCheckedChanged.connect(action);
        return this;
    }

    /** The group this checkbox belongs to, or null. */
    @Nullable
    public CheckboxGroup getGroup() {
        return group;
    }

    /**
     * Joins {@code group}, which takes over exclusivity through {@link #onCheckedChanged} — this
     * class stays group-agnostic.
     *
     * <p>Passing {@code null} <b>leaves</b> the group it was in, rather than only forgetting the
     * reference. The old version dropped the field and left the group still holding the checkbox and
     * still listening to it, so a "removed" member went on being unchecked by its former siblings and
     * could still be the group's {@code current} — its own javadoc pointed at
     * {@link CheckboxGroup#unregister} as the thing to call instead, which is a rule in prose where a
     * line of code will do.</p>
     */
    public Checkbox setGroup(@Nullable CheckboxGroup group) {
        if (this.group == group) return this;
        if (this.group != null) this.group.unregister(this);
        this.group = group;
        if (group != null) group.register(this);
        return this;
    }
}
