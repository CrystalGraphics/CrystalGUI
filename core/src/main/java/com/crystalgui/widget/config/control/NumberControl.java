package com.crystalgui.widget.config.control;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.ui.input.DragScrub;

import javax.annotation.Nullable;
import java.util.Locale;
import com.crystalgui.ui.dom.Name;

/**
 * One number, typed into a field.
 *
 * <p>Unity reference: {@code docs/research/unity-nodes/03-scalar-float.png}.</p>
 *
 * <h3>The value is not the text</h3>
 * <p>A field being typed into passes through states that are not numbers — {@code ""}, {@code "-"},
 * {@code "1."} are all on the way to a number and none of them parses. Committing only what parses,
 * and leaving the text alone otherwise, is what lets someone clear the field and start again. Rejecting
 * or rewriting the text mid-edit is the behaviour that makes a field impossible to type a negative
 * number into, because the minus sign is deleted before the digit arrives.</p>
 *
 * <h3>Formatting is one-way</h3>
 * <p>{@link #writeToWidgets} formats, {@link #parse} reads, and the two are deliberately not inverses:
 * {@code 0.30000001} is written as {@code 0.3}. Re-formatting on every keystroke would fight the
 * caret — type {@code 0.10} and the field would rewrite it to {@code 0.1} with the caret adrift — so
 * the text is only ever written on a programmatic set.</p>
 *
 * <h3>Scrubbing lives here, not in whatever is hosting the number</h3>
 * <p>{@link #scrubWith} makes any element a drag handle for this value — see {@link DragScrub} for what
 * the gesture is and where it comes from. It is on <b>this</b> class rather than on the graph's port
 * editor because scrubbing belongs to <i>a number</i>: put it here and {@link VectorControl}'s
 * {@code X Y Z W} cells, {@link MatrixControl}'s grid, the node port editors and every configurator row
 * get it in one move. Put it in the port editor and the graph gets it, nothing else does, and the second
 * consumer writes it again.</p>
 */
public class NumberControl extends ValueControl<Double> {

    public static final Name NAME = Name.of("numbercontrol");

    /** Typed into, and scrubbed. Debounced like any field; the scrub commits on release. */
    public static final Event<NumberControl, Double> CHANGED =
            ConfigControlContracts.changed(StateTypes.DOUBLE, 0d, RatePolicy.TYPING);

    public static final WidgetContract<NumberControl> CONTRACT = ConfigControlContracts.register(
            NumberControl.class, "numbercontrol", StateTypes.DOUBLE, 0d, CHANGED);


    /** Marks an element that has been made a scrub handle — the hook {@code default.css} hangs the
     * cursor on, so no Java here names a cursor. */
    public static final String SCRUB_HANDLE_CLASS = "__scrub-handle__";

    /**
     * On the control for the duration of a scrub — a hook for a theme that wants to show the gesture.
     *
     * <p>Deliberately <b>not</b> load-bearing for the cursor, unlike {@code splitview.__dragging__} which
     * it otherwise resembles. That one exists because the divider's drag captures the pointer on the
     * SplitView <em>root</em>, so the cursor resolves from an element with no {@code cursor} of its own. A
     * scrub captures on the handle itself, which already carries {@link #SCRUB_HANDLE_CLASS}, so the
     * cursor holds for the whole gesture with no help.</p>
     */
    public static final String SCRUBBING_CLASS = "__scrubbing__";

    private final TextField field = new TextField();
    private final boolean integral;

    /** Decimal places shown, or -1 for up to four. @see ConfigDescriptor#decimals */
    private final int decimals;

    /** The value the live scrub began on. Every frame is computed from this, never from the running
     * value — {@link DragScrub} documents both bugs that live in the alternative. */
    private double scrubAnchor;

    /** Where the press was, kept apart from {@link #scrubAnchor} because that one MOVES. @see #scrubUpdate */
    private double scrubStart;

    /** The modifiers {@link #scrubAnchor} was taken under, and the travel it was taken at. @see #scrubUpdate */
    private int scrubModifiers;
    private float scrubAnchoredAtX;
    private float scrubAnchoredAtY;

    /** False until the pointer has moved far enough for this press to be a scrub rather than a click. */
    private boolean scrubbing;

    /** The no-argument constructor the registry's factory needs, over a NEUTRAL
     * descriptor -- an unlabelled control of this kind, which is a real thing rather than a
     * placeholder. Nothing decodes one: the kit is {@code localOnly}, and the registration
     * exists so a theme can address {@code numbercontrol } by tag. */
    public NumberControl() {
        this(ConfigDescriptor.number("", ""), 0d);
    }

    /** Physical pixels per local unit of the handle, sampled once when the drag begins. @see #scrubWith */
    private float scrubPixelsPerUnit = 1f;

    public NumberControl(ConfigDescriptor descriptor, double defaultValue) {
        super(NAME, descriptor, defaultValue);
        this.integral = descriptor.integral();
        this.decimals = descriptor.decimals();
        addClass("__number__");
        append(field);
        quietly(() -> writeToWidgets(defaultValue));
        if (descriptor.commitsWhileTyping()) field.setUpdateMode(TextField.UpdateMode.IMMEDIATE);

        // A UNIT THAT FOLLOWS ANOTHER VALUE is re-shown when it moves, since the text is only written on a
        // programmatic set and would otherwise keep the old suffix until the number itself changed.
        if (descriptor.live()) {
            PropertyWatch unitWatch = new PropertyWatch(this, Property.derived(descriptor::unit), (was, now) -> {
                if (!isEditing()) quietly(() -> writeToWidgets(getValue()));
            });
            whileConnected(unitWatch::start);
        }

        field.attachListener(text -> {
            Double parsed = parse(text);
            if (parsed == null) return; // mid-edit: "", "-", "1." — leave the text alone
            commit(clamp(parsed));
        });
    }

    /** The field itself, for a host that needs to reach the widget — sizing, focus, a max length. */
    public TextField field() {
        return field;
    }

    /** The label beside the box is its scrub handle. @see #scrubWith */
    @Override
    public boolean adoptLabel(UIElement label) {
        scrubWith(label);
        return true;
    }

    /** Text typed and not yet landed — the field publishes on Enter, Tab or a click away. */
    @Override
    public boolean isEditing() {
        return field.hasPendingEdit();
    }

    /**
     * Makes {@code handle} drag this value: press it and slide, right/up to increase.
     *
     * <p>Typically the label beside the box — {@code X} on a vector component, the row label in an
     * inspector. The handle is usually not this control's own child, which is why it is passed in rather
     * than assumed.</p>
     *
     * <h3>A press that does not travel is still a click</h3>
     * <p>Below {@link DragScrub#DEFAULT_THRESHOLD_PX} nothing is committed and the field takes focus
     * instead, so a label remains a way <em>into</em> the box rather than only a way to change it. The
     * threshold is enforced here rather than through {@code UIDragController}'s: that one is tied to the
     * payload overloads, and a payload turns this into a drag-and-drop that would dispatch drag events at
     * every element the pointer crosses — for a gesture with no payload and nowhere to drop.</p>
     *
     * <h3>Deltas are converted to physical pixels first</h3>
     * <p>A {@code DragListener} reports movement in the <b>source's local space</b>, and in a node graph
     * the handle is inside a zoomable plane — so raw local deltas would make the scrub rate depend on the
     * canvas zoom, and the same hand movement would mean different things at different zooms. The
     * conversion samples the handle's own transform rather than asking anything about canvases, so it
     * holds for {@code uiScale}, a zoom, or any other transform in the chain.</p>
     */
    public NumberControl scrubWith(UIElement handle) {
        handle.addClass(SCRUB_HANDLE_CLASS);
        // The handle is usually a label, and a label is usually scenery with hit-testing off. It cannot
        // be both: a gesture needs the press.
        handle.setHitTest(true);

        handle.onMouseDown.attachListener((element, event) -> {
            if (!isEnabled()) return;
            float rawX = event.getPosition().x(), rawY = event.getPosition().y();
            // A synthesized activation press (Space/Enter on a focused element) carries the cursor's
            // position, which is nowhere near this handle. Slider and ColorSelector discard those the
            // same way; honouring one here would jump the value by however far the pointer happens to be.
            if (!handle.containsSurfacePoint(rawX, rawY)) return;

            UIDocument window = document();
            if (window == null) return;

            // Focus on PRESS, not only on a sub-threshold click.
            //
            // Pressing a control is what focuses it everywhere else, and here it is load-bearing rather
            // than cosmetic: commands resolve outward from the focused element, so a gesture that changes
            // a value while leaving focus untouched produces an edit that Ctrl+Z cannot reach — the
            // command has no scope to walk up from. `UndoScope.nearest(null)` is null, and the undo entry
            // sits on a stack nothing can find.
            //
            // It presented as "undo does not work on scrub", but the edit was always recorded correctly;
            // whether Ctrl+Z found it depended entirely on what had been clicked BEFORE the drag. Select a
            // node first and it worked, which is why deleting-then-undoing looked fine.
            //
            // requestPointerFocus, never requestFocus: the latter rings :focus-visible, and a focus ring
            // appearing because you dragged a number is the exact noise that pseudo-class exists to remove.
            focusField(window);

            scrubbing = false;
            scrubAnchor = currentValue();
            scrubStart = scrubAnchor;
            scrubModifiers = modifiersNow();
            scrubAnchoredAtX = 0f;
            scrubAnchoredAtY = 0f;
            scrubPixelsPerUnit = Drag.pixelsPerLocalUnit(handle);

            Drag.start(handle, rawX, rawY,
                    new Drag.Listener() {
                        @Override
                        public void onDragUpdate(float mouseX, float mouseY, float startX, float startY,
                                                 float deltaX, float deltaY) {
                            scrubUpdate(deltaX * scrubPixelsPerUnit, deltaY * scrubPixelsPerUnit);
                        }

                        @Override
                        public void onDragEnd(float mouseX, float mouseY) {
                            // Focus already happened on press, for both outcomes — so a click and a drag
                            // leave the control in the same state rather than only the click focusing it.
                            endScrub();
                        }

                        @Override
                        public void onDragCancel() {
                            // Escape mid-drag. UIDragController routes it here before anything else sees
                            // the key, so putting the value back is the whole of the work — and it is
                            // worth having: a scrub is the one gesture where you can be well past what
                            // you wanted before you notice.
                            // The PRESS value, not the anchor: a modifier mid-drag moves the anchor, and
                            // Escape means "as it was", not "as it was when I last pressed Shift".
                            if (scrubbing) applyScrubValue(scrubStart);
                            endScrub();
                        }
                    });
            event.stopPropagation();
        }, false, true);
        return this;
    }

    private void scrubUpdate(float dxPixels, float dyPixels) {
        if (!scrubbing) {
            if (!DragScrub.passesThreshold(dxPixels, dyPixels, DragScrub.DEFAULT_THRESHOLD_PX)) return;
            scrubbing = true;
            addClass(SCRUBBING_CLASS);
            // Opened only once the gesture is real, so a click that never scrubbed does not bracket an
            // empty run for the host to record nothing into.
            beginInteraction();
        }
        int modifiers = modifiersNow();
        // A MODIFIER PRESSED MID-DRAG RE-ANCHORS, and does not re-price what is already travelled.
        //
        // The value is the anchor plus the WHOLE delta at the current rate, so Shift arriving after two
        // pixels of travel turned those two pixels into twenty — the number jumped by however far the
        // hand had already come, which is exactly as far as you had to drag to notice it. Re-anchoring
        // leaves the value where it is and prices only what comes next; Blender re-bases the same way
        // when precision starts mid-gesture. The anchor model's two properties survive it: out and back
        // still returns exactly, and replaying one frame is still idempotent.
        if (modifiers != scrubModifiers) {
            scrubAnchor = currentValue();
            scrubAnchoredAtX = dxPixels;
            scrubAnchoredAtY = dyPixels;
            scrubModifiers = modifiers;
        }
        applyScrubValue(DragScrub.value(scrubAnchor, dxPixels - scrubAnchoredAtX,
                dyPixels - scrubAnchoredAtY, modifiers, scrubSpec()));
    }

    /**
     * Writes a scrubbed value and reports it.
     *
     * <p>Two steps because they are two different things: {@link #setValue} updates the text and the
     * stored value <em>silently</em> (a scrub is not typing, so the box has to be repainted for it), and
     * {@link #commit} is what tells the host. Calling only {@code commit} leaves the field showing the
     * value the drag started on for its whole duration.</p>
     */
    private void applyScrubValue(double value) {
        setValue(value);
        commit(value);
    }

    private void endScrub() {
        scrubbing = false;
        removeClass(SCRUBBING_CLASS);
        // Unconditional, and reached from both end and cancel: a host that opened a merge run and never
        // hears it close folds the NEXT unrelated edit into this gesture's undo step.
        endInteraction();
    }

    private void focusField(UIDocument window) {
        // Pointer-driven, so requestPointerFocus rather than requestFocus — the latter rings
        // :focus-visible, and a focus ring appearing because you clicked is the exact noise that
        // pseudo-class exists to remove.
        window.focus().requestPointerFocus(field);
    }

    /** What is held down now, and nothing when there is no platform to ask — a headless tree still clicks. */
    private static int modifiersNow() {
        var input = CgPlatform.input();
        return input == null ? 0 : input.getCurrentModifiers();
    }

    private double currentValue() {
        Double held = getValue();
        return held == null ? 0d : held;
    }

    private DragScrub.Spec scrubSpec() {
        DragScrub.Spec spec = integral ? DragScrub.Spec.INTEGRAL : DragScrub.Spec.FLOAT;
        ConfigDescriptor.Range range = descriptor().range();
        if (range != null) spec = spec.withRange(range.min(), range.max());
        // ASKED PER DRAG, since a rate may follow another value -- a slider's span. @see ConfigDescriptor#scrubRate
        double scrubRate = descriptor().scrubRate();
        return Double.isNaN(scrubRate) ? spec : spec.withRate(scrubRate);
    }


    @Override
    protected void writeToWidgets(@Nullable Double value) {
        field.setText(format(value == null ? 0d : value));
    }

    private double clamp(double v) {
        ConfigDescriptor.Range range = descriptor().range();
        if (range == null) return v;
        return Math.max(range.min(), Math.min(range.max(), v));
    }

    /** The number, then the descriptor's unit if it names one: {@code 45°}, {@code 100%}. */
    private String format(double v) {
        String unit = descriptor().unit();
        return formatNumber(v) + (unit == null ? "" : unit);
    }

    private String formatNumber(double v) {
        if (integral) return String.valueOf(Math.round(v));
        if (decimals >= 0) return String.format(Locale.ROOT, "%." + decimals + "f", v);
        // Trailing zeros stripped, so 0.5 is "0.5" and 1.0 is "1" — Unity's own presentation, and the
        // difference between a readable node and one that is all decimal points.
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** A number, with or without the unit typed after it. */
    @Nullable
    private Double parse(String text) {
        String trimmed = text.trim();
        String unit = descriptor().unit();
        if (unit != null && trimmed.endsWith(unit)) {
            trimmed = trimmed.substring(0, trimmed.length() - unit.length()).trim();
        }
        if (trimmed.isEmpty()) return null;
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException incomplete) {
            return null;
        }
    }
}
