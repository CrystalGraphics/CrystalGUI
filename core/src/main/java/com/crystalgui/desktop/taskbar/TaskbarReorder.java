package com.crystalgui.desktop.taskbar;

import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.desktop.window.WindowRegistry;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.core.data.ReadOnlyVec2f;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dragging a taskbar entry, as Windows does: the entry itself follows the pointer along the bar, never off it,
 * the bar's order changes as it passes a neighbour's middle, and the neighbours slide aside to make room.
 * Released, it settles into its slot; Escape puts the order back.
 *
 * <pre>{@code
 * TaskbarReorder reorder = new TaskbarReorder(taskbar, entriesRow);
 * // on an entry's left press:
 * reorder.press(entry, surfaceX, surfaceY);
 * // in the entry's click handler, first:
 * if (reorder.swallowClick()) return;
 * }</pre>
 *
 * <p>The pointer is read every frame, after layout, from the document's input: a drag event arrives only when
 * the element under the pointer changes, and following that moved the entry in steps. The order lives in the
 * pins and in {@link WindowRegistry#moveInTaskbar}, both written by {@link Taskbar#move}, so the bar's ordinary
 * refresh is what rearranges the row. Every motion is a compositor transform on the box: layout-free, and
 * withdrawn with a null.</p>
 */
final class TaskbarReorder {

    /** The drag's payload. */
    record Payload(UIElement entry) {
    }

    /** As an entry's arrival and close: @see TaskbarEntryMotion. */
    private static final long SLIDE_NANOS = 150L * 1_000_000L;
    private static final CgEasing CURVE = CgEasings.OUT_QUAD;

    private final Taskbar taskbar;
    private final UIElement row;

    @Nullable
    private UIElement entry;
    /** Where the order goes back to on Escape: the entry that followed the dragged one's group. */
    @Nullable
    private UIElement originalBefore;
    private boolean live;
    /** Where on the entry the pointer grabbed, in the row's space. */
    private float grabX;
    /** Set when a press became a drag, so its release does not also click. */
    private boolean swallow;

    /** Each entry's layout position at the last frame, so a moved one can slide from where it was. */
    private final Map<UIElement, float[]> last = new IdentityHashMap<>();
    private final Map<UIElement, Slide> slides = new IdentityHashMap<>();
    /** The compositor offset this class wrote on each entry, so it is never read back from the box. */
    private final Map<UIElement, float[]> offsets = new IdentityHashMap<>();
    private final Animation.Hook frameHook = this::afterLayout;
    /** Before layout, since a reorder rearranges the row and an after-layout hook may not change the tree. */
    private final Animation.Hook reorderHook = delta -> {
        if (live && entry != null) reorderUnderPointer();
        return live;
    };

    /** One entry easing from an offset back to its slot. */
    private static final class Slide {
        float fromX, fromY;
        long startNanos;
    }

    TaskbarReorder(Taskbar taskbar, UIElement row) {
        this.taskbar = taskbar;
        this.row = row;
    }

    /** Starts the gesture on a left press; a press that never moves past the threshold stays a click. */
    void press(UIElement pressed, float surfaceX, float surfaceY) {
        swallow = false;
        if (live) return;
        Box box = pressed.box();
        if (box == null) return;
        entry = pressed;
        Vector2f at = row.toLocal(surfaceX, surfaceY);
        grabX = at.x - box.x();
        Drag.start(pressed, surfaceX, surfaceY, CgMouseCodes.LEFT_BUTTON, new Payload(pressed),
                Drag.DEFAULT_THRESHOLD_PX, new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float x, float y, float startX, float startY, float dx, float dy) {
                        // The first tick is where the press became a drag
                        if (!live) begin();
                    }

                    @Override
                    public void onDragEnd(float x, float y) {
                        if (live) finish(false);
                    }

                    @Override
                    public void onDragCancel() {
                        if (live) finish(true);
                    }
                });
    }

    /** True once, for the release of a press that became a drag. */
    boolean swallowClick() {
        boolean was = swallow;
        swallow = false;
        return was;
    }

    private void begin() {
        if (entry == null) return;
        live = true;
        swallow = true;
        originalBefore = nextAfter(entry);
        taskbar.previewsSuppressed(this, true);
        Box box = entry.box();
        // ABOVE ITS NEIGHBOURS, which it passes over
        if (box != null) box.setZIndex(1);
        row.addClass(Taskbar.REORDERING_CLASS);
        UIDocument document = row.document();
        if (document != null) {
            document.animation().everyIfAbsent(row, reorderHook);
            document.animation().afterLayoutIfAbsent(row, frameHook);
        }
    }

    private void finish(boolean cancelled) {
        live = false;
        taskbar.previewsSuppressed(this, false);
        if (cancelled && entry != null) taskbar.move(entry, originalBefore);
        // From where it is drawn to its slot. A slide, not a last position: the frame hook adds the offset to that
        if (entry != null) {
            float[] offset = offsets.getOrDefault(entry, ZERO);
            Slide slide = slides.computeIfAbsent(entry, key -> new Slide());
            slide.fromX = offset[0];
            slide.fromY = offset[1];
            slide.startNanos = System.nanoTime();
        }
        entry = null;
        originalBefore = null;
    }

    private boolean afterLayout(float deltaSeconds) {
        long now = System.nanoTime();
        for (UIElement each : taskbar.entryRow()) {
            Box box = each.box();
            if (box == null) continue;
            float x = box.x(), y = box.y();
            float[] was = last.put(each, new float[]{x, y});
            if (each == entry && live) {
                ReadOnlyVec2f pointer = pointer();
                if (pointer != null) setOffset(each, box, followX(pointer, box) - x, 0f);
                continue;
            }
            if (was != null && (Math.abs(was[0] - x) > 0.01f || Math.abs(was[1] - y) > 0.01f)) {
                // MOVED UNDER US: slide from where it is drawn now
                float[] drawn = offsets.getOrDefault(each, ZERO);
                Slide slide = slides.computeIfAbsent(each, key -> new Slide());
                slide.fromX = was[0] + drawn[0] - x;
                slide.fromY = was[1] + drawn[1] - y;
                slide.startNanos = now;
            }
            Slide slide = slides.get(each);
            if (slide == null) continue;
            double progress = Math.min(1.0, (now - slide.startNanos) / (double) SLIDE_NANOS);
            float remain = (float) (1.0 - CURVE.ease(progress));
            if (progress >= 1.0) {
                slides.remove(each);
                setOffset(each, box, 0f, 0f);
                box.setZIndex(null);
            } else {
                setOffset(each, box, slide.fromX * remain, slide.fromY * remain);
            }
        }
        boolean busy = live || !slides.isEmpty();
        if (!busy) {
            last.clear();
            row.removeClass(Taskbar.REORDERING_CLASS);
        }
        return busy;
    }

    /**
     * The order follows the entry's leading edge: moving left, its left edge past a neighbour's middle puts it
     * before that neighbour; moving right, its right edge past one puts it after. An edge rather than its centre,
     * which a wide entry held at the bar's end never carries past a narrow neighbour's middle.
     */
    private void reorderUnderPointer() {
        ReadOnlyVec2f pointer = pointer();
        Box box = entry == null ? null : entry.box();
        if (pointer == null || box == null) return;
        float left = followX(pointer, box), right = left + box.width();
        List<UIElement> entries = taskbar.entryRow();
        int at = entries.indexOf(entry);
        if (at < 0) return;
        for (int i = 0; i < at; i++) {
            Box ob = entries.get(i).box();
            if (ob != null && left < ob.x() + ob.width() / 2f) {
                move(entries.get(i));
                return;
            }
        }
        int passed = -1;
        for (int i = at + 1; i < entries.size(); i++) {
            Box ob = entries.get(i).box();
            if (ob == null || right <= ob.x() + ob.width() / 2f) break;
            passed = i;
        }
        if (passed >= 0) move(passed + 1 < entries.size() ? entries.get(passed + 1) : null);
    }

    private void move(@Nullable UIElement before) {
        if (entry != null && before != nextAfter(entry)) taskbar.move(entry, before);
    }

    /** The dragged entry's x in the row under the pointer, kept between the start button and the bar's end. */
    private float followX(ReadOnlyVec2f pointer, Box box) {
        float x = row.toLocal(pointer.x(), pointer.y()).x - grabX;
        Box bar = taskbar.box(), rowBox = row.box();
        if (bar == null || rowBox == null) return x;
        // The row's and start's x are in the bar's space. Start stays first, as in Windows
        Box start = taskbar.start().box();
        float floor = start == null ? bar.padding().left : start.x() + start.width() + start.margin().right;
        float min = floor - rowBox.x();
        float max = bar.width() - bar.padding().right - rowBox.x() - box.width();
        return Math.max(min, Math.min(max, x));
    }

    @Nullable
    private ReadOnlyVec2f pointer() {
        UIDocument document = row.document();
        return document == null ? null : document.input().pointer();
    }

    /** The button after {@code of}'s group, or null at the end: what "before" means for it now. */
    @Nullable
    private UIElement nextAfter(UIElement of) {
        return taskbar.afterGroupOf(of);
    }

    private static final float[] ZERO = {0f, 0f};

    /** Writes only a changed offset: a transform is a new object, and most frames move nothing. */
    private void setOffset(UIElement element, Box box, float dx, float dy) {
        float[] was = offsets.getOrDefault(element, ZERO);
        if (Math.abs(dx - was[0]) < 0.01f && Math.abs(dy - was[1]) < 0.01f) return;
        if (dx == 0f && dy == 0f) {
            offsets.remove(element);
            box.setTransform(null);
        } else {
            offsets.put(element, new float[]{dx, dy});
            box.setTransform(Transform.translate(dx, dy));
        }
    }
}
