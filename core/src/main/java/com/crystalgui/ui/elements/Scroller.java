package com.crystalgui.ui.elements;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgraphics.platform.CgPlatform;

/**
 * A scrollbar: a track with a variable-length thumb.
 *
 * <h3>Layout</h3>
 * <pre>
 * scroller            flex along the bar's axis
 * ├── __head__        step-back button — display:none by default
 * ├── __track__       the groove; flex-grow 1
 * │   └── __thumb__   length = viewport/content ratio, position = value
 * └── __tail__        step-forward button — display:none by default
 * </pre>
 *
 * <p>Four nodes to LDLib's five: its extra wrapper between track and bar is dropped. The buttons are
 * {@code display: none} in the shipped themes (Ore hides them too), which costs no layout at all —
 * and because they're flex items, hiding them makes {@code __track__} fill the whole bar, so the
 * geometry with buttons off is identical to having no buttons.</p>
 *
 * <p>The groove is {@code __track__} rather than the root precisely so the thumb's percentages are
 * measured <em>between the buttons</em>. Painting the groove on the root would make the thumb's
 * travel wrong the moment a theme turned the buttons on.</p>
 *
 * <h3>Relationship to Slider</h3>
 * <p>Nearly the same control, with one real difference: a slider's thumb is a fixed size, a
 * scrollbar's length encodes how much of the content is visible. That's why the thumb is sized in
 * <em>percentages</em> here rather than by {@code Slider}'s flex weights — a percentage expresses
 * "this fraction of the track" directly.</p>
 *
 * <p>Standalone on purpose: a scrollbar is useful without a scroll container behind it (a future
 * TextArea needs exactly this), which is why it isn't buried inside {@code ScrollerView}.</p>
 */
public class Scroller extends UIElement implements com.crystalgui.ui.UIFrameTicker {

    public enum Orientation {
        HORIZONTAL,
        VERTICAL
    }

    public static final String TRACK_CLASS = "__track__";
    public static final String THUMB_CLASS = "__thumb__";
    /** Step-back button (up / left). Hidden by default; a theme opts in with {@code display: flex}. */
    public static final String HEAD_CLASS = "__head__";
    /** Step-forward button (down / right). Hidden by default. */
    public static final String TAIL_CLASS = "__tail__";
    /** Present while the orientation is vertical, so CSS can flip the bar's own axis. */
    public static final String VERTICAL_CLASS = "__vertical__";

    /** Fires whenever the value actually changes, from any source. 0..1. */
    public final Signal.Value<Float> onValueChanged = new Signal.Value<>();

    /**
     * A request to move by a signed fraction of the range — emitted by the step buttons and by
     * track-paging clicks, <em>not</em> by dragging.
     *
     * <p>Separate from {@link #onValueChanged} on purpose. A container that eases its scrolling keeps
     * this bar's value showing the <em>rendered</em> position, so a nudge expressed as "set value to
     * X" would be overwritten by the next sync before the animation caught up — clicks would never
     * accumulate. A relative intent lets the container apply it to its own target instead.</p>
     */
    public final Signal.Value<Float> onScrollIntent = new Signal.Value<>();

    private final UIElement head;
    private final UIElement track;
    private final UIElement thumb;
    private final UIElement tail;

    /**
     * How far one button press moves the value, as a fraction of the whole range.
     *
     * <p>Small on purpose. Browser scrollbar arrows nudge by roughly one line, <em>not</em> by a
     * chunk proportional to the content — so a container that knows its real geometry should set this
     * from a pixel step ({@code ScrollerView} does: {@code lineHeight / contentLength}), keeping a
     * click worth the same distance no matter how long the list is.</p>
     */
    private float stepFraction = 0.05f;

    /** True while the thumb is being dragged. Lets a container tell a drag (which must land instantly,
     * or the thumb lags the cursor) apart from a button/page click (which should ease). */
    @lombok.Getter
    private boolean dragging = false;

    private Orientation orientation = Orientation.VERTICAL;
    /** Scroll position, 0..1. */
    private float value = 0f;
    /** Visible fraction of the content, 0..1 — drives the thumb's length. */
    private float visibleRatio = 1f;

    private float dragStartValue;

    public Scroller() {
        this.head = newPart(HEAD_CLASS);
        this.track = newPart(TRACK_CLASS);
        this.thumb = new UIElement();
        this.thumb.addClass(THUMB_CLASS);
        this.track.addChild(this.thumb);
        this.thumb.markAsInternal();
        this.tail = newPart(TAIL_CLASS);

        // The groove takes whatever the buttons leave. With them hidden (the default) that's the
        // entire bar, so the layout is the same as if they didn't exist.
        StyleGroup.defaultPipeline(track.getStyle().getLayoutGroup(), l -> l.flexGrow(1));

        setOrientation(Orientation.VERTICAL);
        applyThumb();

        this.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled()) return;
            var target = event.getTarget();
            if (target == thumb) {
                beginDrag(event.getPosition().x(), event.getPosition().y());
            } else if (target == head) {
                beginRepeat(-1f);
            } else if (target == tail) {
                beginRepeat(1f);
            } else if (target == track || target == this) {
                // Jump to where you clicked, centring the thumb there, rather than paging by a
                // screenful. Absolute rather than relative on purpose: setValue routes through the
                // container's normal (eased) path, so the thumb glides to the spot.
                setValue(valueForThumbCentredAt(event));
            }
        }, false, true);
    }

    private UIElement newPart(String cssClass) {
        UIElement part = new UIElement();
        part.addClass(cssClass);
        addInternalChild(part);
        return part;
    }

    /** The step-back button. Hidden unless a theme shows it; wired either way. */
    public UIElement head() {
        return head;
    }

    /** The step-forward button. */
    public UIElement tail() {
        return tail;
    }

    /** The groove the thumb travels in. */
    public UIElement track() {
        return track;
    }

    public float getStepFraction() {
        return stepFraction;
    }

    /** How far one head/tail press moves the value, as a fraction of the whole range. */
    public Scroller setStepFraction(float stepFraction) {
        this.stepFraction = Math.max(0f, stepFraction);
        return this;
    }

    /** The bar owns a fixed structure; there is no content to host. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public UIElement thumb() {
        return thumb;
    }

    // ── Value / ratio ───────────────────────────────────────────────────────

    public float getValue() {
        return value;
    }

    /** Scroll position as 0..1. Clamped; signals only on a real change. */
    public Scroller setValue(float newValue) {
        float clamped = Math.max(0f, Math.min(1f, newValue));
        if (clamped == this.value) return this;
        this.value = clamped;
        applyThumb();
        onStyleChanged();
        onValueChanged.emit(clamped);
        return this;
    }

    public float getVisibleRatio() {
        return visibleRatio;
    }

    /**
     * Fraction of the content currently visible, 0..1 — the thumb's length. A ratio of 1 means
     * everything fits, i.e. nothing to scroll.
     *
     * <p>Floored at a small minimum so an enormous content area still leaves a grabbable thumb rather
     * than a sub-pixel sliver.</p>
     */
    public Scroller setVisibleRatio(float ratio) {
        float clamped = Math.max(MIN_THUMB_RATIO, Math.min(1f, ratio));
        if (clamped == this.visibleRatio) return this;
        this.visibleRatio = clamped;
        applyThumb();
        return this;
    }

    private static final float MIN_THUMB_RATIO = 0.1f;

    public Orientation getOrientation() {
        return orientation;
    }

    public Scroller setOrientation(Orientation orientation) {
        this.orientation = orientation;
        boolean vertical = orientation == Orientation.VERTICAL;
        // The bar lays its parts out along its own axis, so head/track/tail stack correctly whichever
        // way it points.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(vertical ? FlexDirection.COLUMN : FlexDirection.ROW));
        if (vertical) {
            addClass(VERTICAL_CLASS);
        } else {
            removeClass(VERTICAL_CLASS);
        }
        applyThumb();
        return this;
    }

    public Scroller attachListener(Signal.Value.Listener<Float> action) {
        onValueChanged.connect(action);
        return this;
    }

    // ── Press-and-hold repeat ───────────────────────────────────────────────

    /** Pause before a held button starts repeating, so a single click stays a single step. */
    private static final float REPEAT_DELAY_SECONDS = 0.3f;
    /**
     * How fast a held arrow scrolls, in "lines" per second.
     *
     * <p>Applied as a tiny increment <em>every frame</em> rather than as a whole line every N
     * milliseconds. Same idea as a browser's held arrow: the update rate stays as high as the frame
     * rate, and the slowness comes from each step being small — which is much smoother than fewer,
     * bigger jumps at the same average speed.</p>
     */
    private static final float HOLD_LINES_PER_SECOND = 5f;

    /** 0 when nothing is held, otherwise -1 (head) or +1 (tail). */
    private float heldDirection = 0f;
    private float heldElapsed = 0f;
    private float repeatTimer = 0f;

    /**
     * Steps once, then keeps stepping while the button stays down — the auto-repeat every desktop
     * scrollbar has. A single click is one step because the first repeat waits
     * {@link #REPEAT_DELAY_SECONDS}.
     */
    private void beginRepeat(float direction) {
        requestScroll(direction * stepFraction);
        this.heldDirection = direction;
        this.heldElapsed = 0f;
        this.repeatTimer = 0f;
        var window = getAttachedWindow();
        if (window != null) window.registerTicker(this);
    }

    @Override
    public boolean tickFrame(float deltaSeconds) {
        if (heldDirection == 0f) return false;
        // Poll the real button state rather than waiting for a mouse-up event: a release outside the
        // button (or outside the window entirely) would never be delivered here, and the repeat would
        // run forever.
        var adapter = CgPlatform.input();
        if (adapter == null || !adapter.isMouseDown(CgMouseCodes.LEFT_BUTTON)) {
            heldDirection = 0f;
            return false;
        }

        heldElapsed += deltaSeconds;
        if (heldElapsed < REPEAT_DELAY_SECONDS) return true;

        // A fraction of a line per frame, not a whole line per tick — small steps at full frame rate.
        requestScroll(heldDirection * stepFraction * HOLD_LINES_PER_SECOND * deltaSeconds);
        return true;
    }

    /**
     * Emits a relative move. Falls back to applying it to this bar's own value when nobody is
     * listening, so a standalone {@code Scroller} (no container behind it) still works from its
     * buttons.
     */
    private void requestScroll(float deltaFraction) {
        if (onScrollIntent.hasListeners()) {
            onScrollIntent.emit(deltaFraction);
        } else {
            setValue(value + deltaFraction);
        }
    }

    private boolean isVertical() {
        return orientation == Orientation.VERTICAL;
    }

    /**
     * Pushes length and position into the thumb, as percentages of the track.
     *
     * <p>IMPORTANT origin because both are runtime state a stylesheet has no business overriding —
     * the same reasoning as {@code Slider.applyFraction} and {@code UIText}'s measured size. A theme
     * still controls the thumb's thickness on the cross axis, its background and its states.</p>
     */
    private void applyThumb() {
        float lengthPercent = visibleRatio * 100f;
        float positionPercent = value * (100f - lengthPercent);
        StyleGroup.importantPipeline(thumb.getStyle().getLayoutGroup(), l -> {
            if (isVertical()) {
                l.heightPercent(lengthPercent).topPercent(positionPercent);
            } else {
                l.widthPercent(lengthPercent).leftPercent(positionPercent);
            }
        });
    }

    // ── Dragging ────────────────────────────────────────────────────────────

    /**
     * The value that would put the thumb's <em>centre</em> under a track click.
     *
     * <p>Offsetting by half the thumb is what makes it land centred instead of starting there; the
     * denominator is the thumb's travel (groove minus thumb), not the groove, so clicking the very
     * end reaches exactly 1. {@link #setValue} clamps, so clicks in the half-thumb margins at each
     * end simply pin to the extremes.</p>
     */
    private float valueForThumbCentredAt(MouseEvent.Down event) {
        var local = screenToLocal(event.getPosition().x(), event.getPosition().y());
        var groove = track.getRuntimeCache();
        var t = thumb.getRuntimeCache();

        float pos = isVertical() ? local.y() : local.x();
        float start = isVertical() ? groove.getY() : groove.getX();
        float grooveLength = isVertical() ? groove.getHeight() : groove.getWidth();
        float thumbLength = isVertical() ? t.getHeight() : t.getWidth();

        float travel = Math.max(1f, grooveLength - thumbLength);
        return (pos - start - thumbLength / 2f) / travel;
    }

    /** Distance the thumb can travel: the GROOVE minus the thumb's own length — not the whole bar,
     * which would be wrong the moment a theme shows the head/tail buttons. */
    private float travelLength() {
        var groove = track.getRuntimeCache();
        var t = thumb.getRuntimeCache();
        float span = isVertical() ? groove.getHeight() - t.getHeight() : groove.getWidth() - t.getWidth();
        return Math.max(1f, span);
    }

    /** Takes the RAW pointer position — {@code UIDragController} converts to local space itself. */
    private void beginDrag(float rawMouseX, float rawMouseY) {
        var window = getAttachedWindow();
        if (window == null) return;
        this.dragStartValue = this.value;
        this.dragging = true;
        float travel = travelLength();
        // An anonymous class rather than a lambda so onDragEnd can clear the dragging flag — a
        // container reads it to decide between an instant landing and an eased one.
        window.getInputHandler().getDragController().startDrag(this, rawMouseX, rawMouseY,
                new com.crystalgui.ui.input.UIDragController.DragListener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                        // Delta from the grab point, so grabbing the thumb anywhere along its length
                        // doesn't teleport it under the cursor — as Slider and SplitView do.
                        float delta = isVertical() ? dy : dx;
                        setValue(dragStartValue + delta / travel);
                    }

                    @Override
                    public void onDragEnd(float mx, float my) {
                        dragging = false;
                    }
                });
    }
}
