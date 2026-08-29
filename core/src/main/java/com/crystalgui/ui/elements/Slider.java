package com.crystalgui.ui.elements;

import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.State;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * Continuous or stepped slider.
 *
 * <h3>Layout</h3>
 * <pre>
 * slider              (row, align-items center; paints nothing itself)
 * ├── __fill__        flex-grow = fraction        (the filled portion of the bar)
 * ├── __thumb__       fixed size, may exceed the bar's height
 * └── __spacer__      flex-grow = 1 - fraction    (the unfilled portion)
 * </pre>
 *
 * <p>The root deliberately carries no background and is sized to the <em>thumb</em>, not the bar:
 * {@code __fill__} and {@code __spacer__} draw the bar between them. A bar-height root would leave
 * the taller thumb overhanging its box, and clicks on the overhang would miss the slider entirely
 * since hit-testing uses the root.</p>
 *
 * <p>The fill ends exactly where the thumb begins and the track resumes after it, which is what the
 * reference art shows. Using flex rather than absolute positioning means the thumb is never
 * half-off the end at the extremes — it's laid out, not offset.</p>
 *
 * <h3>Why this deliberately does NOT animate</h3>
 * <p>{@code Switch} slides its knob with a CSS {@code transition} on {@code flex-grow}. That would
 * be actively wrong here: during a drag the thumb must track the cursor exactly, and a transition
 * would make it lag behind by the transition's duration. So the fill/spacer values are written at
 * {@code IMPORTANT} origin with no transition declared, and a theme should not add one for the drag
 * path. (Animating only keyboard/programmatic changes would need the transition suppressed for the
 * duration of a drag — deliberately not attempted.)</p>
 *
 * <h3>Input</h3>
 * <p>Everything is handled on the root, with children hit-test-disabled, so {@code :hover}/
 * {@code :active} keep matching the slider itself. Grabbing the thumb tracks the cursor by
 * <em>delta from the grab point</em> so the thumb doesn't jump under the finger; clicking the track
 * jumps to that position first and then tracks. Both are LDLib2 idioms
 * ({@code Scroller} and {@code ColorSelector} respectively) — it has no slider of its own.</p>
 */
public class Slider extends UIElement {

    public static final State<Slider, Float> MIN =
            State.of("min", StateTypes.FLOAT, Slider::getMin, (s, v) -> s.setRange(v, s.getMax()), 0f);

    public static final State<Slider, Float> MAX =
            State.of("max", StateTypes.FLOAT, Slider::getMax, (s, v) -> s.setRange(s.getMin(), v), 1f);

    public static final State<Slider, Float> STEP =
            State.of("step", StateTypes.FLOAT, Slider::getStep, Slider::setStep, 0f);

    /**
     * Sanitized on the way in, and this is the slot that most needs it: a value is the one piece of a
     * slider a PEER sends, and "the setter will cope" is a hope rather than a guarantee. NaN is the
     * case that matters -- it fails every comparison, so a range check written the obvious way lets it
     * through and it then poisons every layout it reaches.
     */
    public static final State<Slider, Float> VALUE =
            State.of("value", StateTypes.FLOAT, Slider::getValue, Slider::setValue, 0f)
                    .sanitizedBy(v -> v == null || Float.isNaN(v) ? 0f : v);

    /** A drag. Throttled, and the released value always travels. @see RatePolicy */
    public static final Event<Slider, Float> VALUE_CHANGED = Event.of("value",
            (slider, sink) -> slider.attachListener(sink::accept),
            new Event.Payload<Float>() {
                @Override public <T> void write(StateMap<T> out, Float value) {
                    out.putFloat("value", value);
                }
                @Override public <T> Float read(StateMap<T> in) {
                    return in.getFloat("value", 0f);
                }
            }, RatePolicy.DRAGGING);

    /**
     * RANGE BEFORE VALUE, and that is why a contract applies slots in declaration order. Taking the
     * value first clamps it against the OLD bounds, so a slider arriving with range 0-100 and value 80
     * would land at 1 if the previous range was 0-1 -- silently, and only for a widget whose range the
     * server had also changed.
     */
    public static final WidgetContract<Slider> CONTRACT = WidgetContracts.register(
            WidgetContract.of(Slider.class, "slider")
                    .state(MIN)
                    .state(MAX)
                    .state(STEP)
                    .state(VALUE)
                    .event(VALUE_CHANGED)
                    .build());

    public static final String FILL_CLASS = "__fill__";
    public static final String THUMB_CLASS = "__thumb__";
    public static final String SPACER_CLASS = "__spacer__";

    /** Fires whenever the value actually changes, from any source. */
    public final Signal.Value<Float> onValueChanged = new Signal.Value<>();

    private final UIElement fill;
    private final UIElement thumb;
    private final UIElement spacer;

    private float min = 0f;
    private float max = 1f;
    private float value = 0f;
    /** 0 = continuous. Otherwise the value snaps to multiples of this. */
    private float step = 0f;

    /** Value at the moment a thumb drag began; drag deltas are applied relative to it. */
    private float dragStartValue;

    public Slider() {
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));

        this.fill = newPart(FILL_CLASS);
        this.thumb = newPart(THUMB_CLASS);
        this.spacer = newPart(SPACER_CLASS);

        this.setFocusPolicy(FocusPolicy.CLICK);
        applyFraction();

        this.attachDefaultListener(this.onMouseDown, (el, event) -> {
            if (!isEnabled()) return;
            // Space/Enter on a focused element is delivered as a synthesized MouseEvent.Down at the
            // cursor position (UIInputHandler.handleActivationKey). Honouring that would fling the
            // slider to wherever the mouse happens to be, so a press whose coordinates aren't
            // actually over this slider is discarded; arrow keys below are the keyboard path.
            float rawX = event.getPosition().x(), rawY = event.getPosition().y();
            if (!containsScreenPoint(rawX, rawY)) return;

            // MouseEvent positions are raw/physical; all geometry below is logical. Convert once.
            float mouseX = screenToLocal(rawX, rawY).x();
            if (!isOverThumb(mouseX)) {
                setValue(valueAtX(mouseX)); // click on the track jumps first
            }
            beginDrag(rawX);
        });

        this.attachDefaultListener(this.onMouseScroll, (el, event) -> {
            if (!isEnabled() || !isFocused()) return;
            setValue(value + event.getScroll() * stepOrDefault());
            // We consumed the wheel, so suppress the default "scroll the nearest scroll container"
            // behaviour — otherwise a focused slider inside a scrolling list would move AND scroll
            // the list out from under the cursor.
            event.preventDefault();
        });

        this.events.getGroup(KeyboardEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled()) return;
            switch (event.getKeyCode()) {
                case CgKeyCodes.KEY_LEFT -> setValue(value - stepOrDefault());
                case CgKeyCodes.KEY_RIGHT -> setValue(value + stepOrDefault());
                case CgKeyCodes.KEY_HOME -> setValue(min);
                case CgKeyCodes.KEY_END -> setValue(max);
                default -> {
                    return;
                }
            }
            // Consume, so Tab-traversal/activation doesn't also act on an arrow key we handled.
            event.stopPropagation();
        }, false, false);
    }

    private UIElement newPart(String cssClass) {
        UIElement part = new UIElement();
        part.addClass(cssClass);
        addInternalChild(part);
        part.setHitTest(false); // keep :hover/:active on the slider root
        return part;
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    // ── Value ───────────────────────────────────────────────────────────────

    public float getValue() {
        return value;
    }

    public float getMin() {
        return min;
    }

    public float getMax() {
        return max;
    }

    /** Clamps to the range, snaps to {@link #setStep}, and signals only on a real change. */
    public Slider setValue(float newValue) {
        float snapped = clampAndSnap(newValue);
        if (snapped == this.value) return this;
        this.value = snapped;
        applyFraction();
        onStyleChanged();
        notifyStateChanged();
        onValueChanged.emit(snapped);
        return this;
    }

    public Slider setRange(float min, float max) {
        this.min = min;
        this.max = max;
        setValue(this.value); // re-clamp into the new range
        applyFraction();
        notifyStateChanged();
        return this;
    }

    /** {@code 0} for continuous. A positive value snaps to multiples of it from {@code min}. */
    public Slider setStep(float step) {
        this.step = Math.max(0f, step);
        setValue(this.value);
        notifyStateChanged();
        return this;
    }

    public float getStep() {
        return step;
    }

    /** Position within the range as 0..1. Returns 0 for a degenerate (zero-width) range. */
    public float getFraction() {
        float span = max - min;
        return span <= 0f ? 0f : (value - min) / span;
    }

    public Slider attachListener(Signal.Value.Listener<Float> action) {
        onValueChanged.connect(action);
        return this;
    }

    float clampAndSnap(float raw) {
        float lo = Math.min(min, max);
        float hi = Math.max(min, max);
        float clamped = Math.max(lo, Math.min(hi, raw));
        if (step <= 0f) return clamped;
        float snapped = min + Math.round((clamped - min) / step) * step;
        return Math.max(lo, Math.min(hi, snapped));
    }

    /** Keyboard/scroll increment: one step, or 1% of the range when continuous. */
    private float stepOrDefault() {
        return step > 0f ? step : (max - min) * 0.01f;
    }

    /** Pushes the current fraction into the flex weights. IMPORTANT origin because this is runtime
     * state a stylesheet has no business overriding — the same reasoning UIText uses for its
     * measured size. */
    private void applyFraction() {
        float f = getFraction();
        StyleGroup.importantPipeline(fill.getStyle().getLayoutGroup(), l -> l.flexGrow(f));
        StyleGroup.importantPipeline(spacer.getStyle().getLayoutGroup(), l -> l.flexGrow(1f - f));
    }

    // ── Pointer mapping ─────────────────────────────────────────────────────

    /** Distance the thumb's CENTRE travels: the full content box.
     *
     * <p>The thumb overhangs the content box by half its width at each end (negative margins — see
     * the slider rules in {@code ore.css}), so its centre sweeps exactly from the content box's left
     * edge to its right edge. That is also why the fill's right edge and the spacer's left edge both
     * land under the thumb's centre, hiding their end caps.</p> */
    private float travelLength() {
        return Math.max(1f, getTaffyLayout().contentBoxWidth());
    }

    private float contentLeft() {
        var layout = getTaffyLayout();
        return getRuntimeCache().getX() + layout.border().left + layout.padding().left;
    }

    /** Absolute value under a given <em>local</em> x — used for track clicks. The thumb's centre
     * lands on the cursor, so this is a straight remap with no half-thumb offset. */
    private float valueAtX(float mouseX) {
        float t = (mouseX - contentLeft()) / travelLength();
        return min + Math.max(0f, Math.min(1f, t)) * (max - min);
    }

    /** Takes a local x, like everything else in this section. */
    private boolean isOverThumb(float mouseX) {
        float x = thumb.getRuntimeCache().getX();
        return mouseX >= x && mouseX <= x + thumb.getRuntimeCache().getWidth();
    }

    /** Takes the RAW pointer x — {@code UIDragController} does the local-space conversion, and
     * reports every subsequent coordinate already converted. */
    private void beginDrag(float rawMouseX) {
        var window = getAttachedWindow();
        if (window == null) return;
        this.dragStartValue = this.value;
        float range = max - min;
        float travel = travelLength();
        window.getInputHandler().getDragController().startDrag(this, rawMouseX,
                getRuntimeCache().getY(),
                // Delta from the grab point, not absolute: grabbing the thumb anywhere along its
                // width must not teleport it so its centre lands under the cursor.
                (mx, my, sx, sy, dx, dy) -> setValue(dragStartValue + (dx / travel) * range));
    }
}
