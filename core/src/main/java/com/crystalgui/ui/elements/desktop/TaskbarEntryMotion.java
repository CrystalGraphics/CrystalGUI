package com.crystalgui.ui.elements.desktop;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.easing.Easing;
import com.crystalgui.style.easing.ProgressFunctions;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;

import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.function.Predicate;

/**
 * An entry joining or leaving the strip — it opens the row apart, or closes it up.
 *
 * <h3>One motion, both directions</h3>
 *
 * <p>Scaling a button toward its own centre is what a WINDOW does, and it is the wrong gesture for an item
 * in a strip: the button shrinks and then everything after it <em>jumps</em> when the element is finally
 * dropped. The whole information content is that the row changed length, so the row has to be what moves.
 * Every strip animates it this way — Chrome, Firefox and VS Code ease a tab's width open and shut and slide
 * the rest across, the macOS Dock reflows its neighbours, and Windows 11's taskbar collapses the button and
 * re-centres what is left, which this bar gets free from {@code justify-content: center}.</p>
 *
 * <p>Opening and closing are the same animation with the ramp reversed, and they are one class on purpose:
 * two would drift, and only one of them would ever be debugged.</p>
 *
 * <h3>max-width, never width — a cap cannot pin</h3>
 *
 * <p>Writing a {@code width} every frame does not animate a content-sized box, it <b>pins</b> it: the rule
 * the taskbar preview paid for, where a panel was frozen at the size of the first window it ever showed.
 * That is survivable for a closing entry, which is destroyed at the end, and fatal for an opening one,
 * which has to go on resizing itself for the rest of its life as its title changes and its badge appears.
 * A {@code max-width} only CAPS: once the cap passes the natural width it is inert, and
 * {@code max-width: auto} withdraws it completely. So both directions ramp the cap and neither ever
 * states a width.</p>
 *
 * <p>The cap alone does not reach zero, though: {@code border-box} will not compress an element's own
 * padding, so the horizontal padding ramps with it. @see #padLeft</p>
 *
 * <h3>Two channels, because a layout property and a paint property are not written the same way</h3>
 *
 * <p>The cap goes through the INLINE pipeline, as {@link WindowGeometryAnimation} writes its geometry:
 * {@code LayoutProperties} hangs a {@code TaffyBridge} listener off every layout property, and the
 * animation-slot channel deliberately bypasses that, so a cap written as an animation slot would resolve
 * correctly and never reach the layout. Opacity is read at paint time and so takes the slot channel that
 * {@link WindowAnimation} uses — which matters at the end: the slot is <b>withdrawn</b> and the sheet's own
 * value takes back over, where an INLINE cleanup value would outrank every rule that ever wants to fade an
 * entry afterwards.</p>
 *
 * <h3>Measuring a width that does not exist yet</h3>
 *
 * <p>A closing entry has been on screen for a while, so its natural width is simply read. A NEW one has
 * never been laid out and there is nothing to open to — and it cannot be measured in flow, because a frame
 * at natural width is a frame of the row jumping apart and snapping back. So it spends its setup frames
 * {@code position: absolute} <b>and</b> {@code width: max-content}, invisible: out of flow so the row does
 * not move, and explicitly content-sized because Taffy does not shrink-to-fit an out-of-flow box the way
 * CSS does — absolute alone measured zero. It costs two ticks rather than one, because tickers run BEFORE
 * layout, so the tick of the frame the entry was added on still sees a box that has never been computed.
 * @see #tickFrame</p>
 *
 * <p>{@code CLIP}, never {@code HIDDEN}: the label is wider than the box for most of the ramp and would
 * otherwise spill over its neighbour, and {@code HIDDEN} would make the entry a scroll container.</p>
 */
final class TaskbarEntryMotion implements UIFrameTicker {

    /** Short: a strip item opening or closing is a small local motion, not a window crossing the screen. */
    private static final long DURATION_NANOS = 150L * 1_000_000L;

    /**
     * Decelerating in BOTH directions, against Fluent's advice that things leaving should accelerate.
     *
     * <p>The finding the window animations already record: an exit that speeds up is gone before the eye
     * follows it, and every production window manager decelerates everything.</p>
     */
    private static final Easing CURVE = ProgressFunctions.Premade.OUT_QUAD;

    private final UIElement entry;
    private final boolean opening;
    private final Runnable onDone;

    /** The natural width — the cap's far end. Negative until an opening entry has had its measure frame. */
    private float span;
    /**
     * The entry's own horizontal padding, ramped alongside the cap.
     *
     * <p><b>A CAP ALONE CANNOT CLOSE THE ROW.</b> {@code box-sizing} is {@code border-box} here, and a
     * border box will not compress an element's own padding — so an entry capped at zero still occupies
     * 6 + 9 px, and the detach at the end takes those fifteen away in a single frame. Measured, not
     * assumed: a 76.7px entry capped at 0 lays out at exactly 15.0. It reads as the collapse choking right
     * at the end, and it is invisible on the ARRIVAL, where the same floor sits at the start of the ramp
     * with the entry still at zero opacity.</p>
     */
    private float padLeft;
    private float padRight;
    /** Ticks spent waiting for the measure configuration to be laid out. @see #tickFrame */
    private int measureTicks;
    private long startNanos;
    private boolean over;

    /** An entry arriving: measured out of flow on its first frame, then the row opens for it. */
    static TaskbarEntryMotion opening(UIElement entry, Runnable settled) {
        return new TaskbarEntryMotion(entry, true, settled);
    }

    /** An entry leaving: the row closes up, and {@code detach} drops it at the end. */
    static TaskbarEntryMotion closing(UIElement entry, Runnable detach) {
        return new TaskbarEntryMotion(entry, false, detach);
    }

    /**
     * Starts immediately — the first values are written here, not on the first tick, or the entry shows a
     * frame of its END state before it begins.
     */
    private TaskbarEntryMotion(UIElement entry, boolean opening, Runnable onDone) {
        this.entry = entry;
        this.opening = opening;
        this.onDone = onDone;
        // NOT A TARGET WHILE IT MOVES. A sliver of a button is not something anyone can aim at, and a
        // closing one is out of the taskbar's map already, so a press would resolve against a window the
        // strip no longer lists.
        entry.setHitTest(false);
        // CLIPPED BY THE SHEET, not from here. @see Taskbar#ANIMATING_CLASS -- an inline overflow is the
        // only candidate the property would have, so withdrawing it at the end resolved to null.
        entry.addClass(Taskbar.ANIMATING_CLASS);
        entry.getStyle().startAnimationSlot(StylePropertyRegistry.OPACITY, opening ? 0f : 1f, 0);
        if (opening) {
            // THE MEASURE FRAME, and NO cap yet -- capping now would measure the cap. @see #tickFrame
            //
            // `max-content` as well as out-of-flow, and BOTH are needed: absolute alone came back ZERO,
            // because Taffy does not shrink-to-fit an absolutely positioned box the way CSS does, and
            // max-content alone would be measured in flow, which is the row jumping apart for a frame.
            span = -1f;
            StyleGroup.inlinePipeline(entry.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE).widthMaxContent());
        } else {
            span = Math.max(0f, entry.getRuntimeCache().getWidth());
            capturePadding();
            startNanos = System.nanoTime();
            apply(0f);
        }
    }

    /** The sheet's own padding, read once before this motion writes any of its own. */
    private void capturePadding() {
        padLeft = lengthOf(entry.getStyle().getComputed(LayoutProperties.PADDING_LEFT));
        padRight = lengthOf(entry.getStyle().getComputed(LayoutProperties.PADDING_RIGHT));
    }

    /**
     * The px value of a Taffy length, or 0 for anything else.
     *
     * <p>Compared by tag NAME, as {@code StyleValueCodecs} does: the tagged union's enum is not
     * conveniently nameable from here, and only a LENGTH is a number this can scale. A percentage would
     * resolve against the containing block and an {@code auto} is not a size, so both correctly ramp
     * nothing rather than being guessed at.</p>
     */
    private static float lengthOf(Object dimension) {
        return dimension instanceof LengthPercentageAuto value && "LENGTH".equals(value.getType().name())
                ? value.getValue()
                : 0f;
    }

    /** Registers on the entry's window; false when there is nothing to tick it. */
    boolean start() {
        UIWindow window = entry.getAttachedWindow();
        if (window == null) return false;
        window.registerTicker(this);
        return true;
    }

    @Override
    public boolean tickFrame(float deltaSeconds) {
        if (over) return false;
        // A TICKER WHOSE ELEMENT HAS LEFT THE TREE MUST STOP -- registration is one-way by design, and
        // the strip is rebuilt from under this by anything that opens or closes another window.
        if (entry.getParent() == null) {
            over = true;
            return false;
        }
        if (span < 0f) {
            // ONE LAYOUT FIRST. advanceFrame runs style, then tickers, then LAYOUT -- so on the tick of
            // the frame the entry was added to, its box has never been computed and reads zero. Waiting
            // one tick is what lets the measure configuration above actually be laid out; without it the
            // span came back 0, the guard below fired, and every arrival settled instantly at full size,
            // which looks exactly like the animation not being wired up at all.
            if (measureTicks++ == 0) return true;
            // The measure frame has been laid out: take the content width and drop into the row at zero.
            span = Math.max(0f, entry.getRuntimeCache().getWidth());
            capturePadding();
            StyleGroup.inlinePipeline(entry.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.RELATIVE).widthAuto());
            // A MEASURE THAT CAME BACK EMPTY is a layout this animation does not understand -- settle at
            // full size rather than ramping from zero to zero and leaving the entry invisible.
            if (span <= 0f) {
                finish();
                return false;
            }
            startNanos = System.nanoTime();
            apply(0f);
            return true;
        }
        long elapsed = System.nanoTime() - startNanos;
        double progress = Math.min(1.0, elapsed / (double) DURATION_NANOS);
        apply(CURVE.ease(progress));
        if (progress < 1.0) return true;
        finish();
        return false;
    }

    /**
     * Ends it now and settles: an opening entry jumps to full size, a closing one is dropped.
     *
     * <p>For a window that closes while its entry is still arriving, and for one that comes BACK before its
     * entry finished leaving — in both cases two motions would otherwise write the same cap every frame and
     * the picture would be whichever ran last.</p>
     */
    void cancel() {
        if (over) return;
        finish();
    }

    private void apply(double progress) {
        float p = (float) progress;
        float fraction = opening ? p : 1f - p;
        StyleGroup.inlinePipeline(entry.getStyle().getLayoutGroup(), l -> l
                .maxWidth(span * fraction)
                .paddingLeft(padLeft * fraction)
                .paddingRight(padRight * fraction));
        entry.getStyle().tickAnimationSlot(StylePropertyRegistry.OPACITY, fraction, 0);
    }

    private void finish() {
        over = true;
        if (opening) {
            // WITHDRAW ONLY WHAT THE SHEET ANSWERS FOR, and write the initial value for the rest.
            //
            // Removing a candidate re-resolves the property, which is what carries a layout property back
            // to TaffyBridge -- but if the withdrawal leaves NO candidate at any origin, getComputed
            // answers null and the listener is handed it. That is fine for padding, which
            // `taskbar .__entry__` states outright, and fatal for the three below, which no rule mentions:
            // it took the harness down with a NullPointerException out of resolveTouched on the frame an
            // arriving entry settled. `auto`/`auto`/`relative` ARE the initial values, so writing them is
            // indistinguishable from absence for everything except a future rule, and there is none.
            release(LayoutProperties.PADDING_LEFT, LayoutProperties.PADDING_RIGHT);
            StyleGroup.inlinePipeline(entry.getStyle().getLayoutGroup(),
                    l -> l.maxWidthAuto().widthAuto().positionType(TaffyPosition.RELATIVE));
            entry.removeClass(Taskbar.ANIMATING_CLASS);
            entry.getStyle().endAnimationSlot(StylePropertyRegistry.OPACITY);
            entry.setHitTest(true);
        }
        onDone.run();
    }

    private void release(StyleProperty<?>... properties) {
        for (StyleProperty<?> property : properties) {
            entry.getStyle().removeCandidates(property, slot -> slot.origin() == StyleOrigin.INLINE);
        }
    }
}
