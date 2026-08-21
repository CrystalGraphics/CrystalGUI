package com.crystalgui.ui.elements.desktop;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The compositor host — CrystalOS's desktop, and the parent of every {@link WindowFrame}.
 *
 * <p><b>Nobody constructs one.</b> Every {@code UIWindow} owns a desktop and hands it out through
 * {@code UIWindow.desktop()}; opening a UI is {@code window.openWindow(frame)} and nothing else. That
 * is the same ownership {@code UIWindow.windowOverlayLayer()} already has — an engine-owned layer built
 * on first use, which is observably "always there" while costing nothing to a window that never opens
 * one. A compositor that each application had to assemble for itself would be a compositor each
 * application assembled slightly differently.</p>
 *
 * <p>One desktop per {@code UIWindow}, which is the display surface rather than a window
 * ({@code plan_windowing.md}, Design B). Everything a window manager needs already existed at the
 * element layer — stacking that paint and hit-testing agree on, clipping, focus, drag, pointer capture —
 * so what is genuinely new is this: somewhere for frames to live, a work area to bound them, and a
 * placement rule for a window nobody positioned.</p>
 *
 * <h3>It sits over the root, in the windows band</h3>
 * <p>The desktop is an internal child of the window's root element, filling it, above whatever the root
 * already held. That IS the band model: <i>desktop content</i> (the root's own children) &lt;
 * <i>windows</i> (this) &lt; <i>pinned</i> (W14) &lt; the global top layer, which paints after the whole
 * main tree by construction. Its geometry is written from Java at <b>IMPORTANT</b> for the same reason
 * the overlay layer's is: the surface the compositor draws on must not be movable by a stylesheet.</p>
 *
 * <p><b>Zero-sized until it holds a window</b>, which is what keeps it free rather than merely cheap.
 * An always-full-size overlay would sit in front of the root's own content and swallow every click that
 * missed a window — so it takes up no space and hit-tests nothing at all while empty, and claims the
 * surface only once there is genuinely a compositor to be in front. Once a window IS open, clicks on
 * bare desktop belong to the desktop (that is what W2's empty-desktop blur means), and W7 removes the
 * question entirely by making the editor itself a frame.</p>
 *
 * <h3>The window layer is internal; the frames on it are public</h3>
 * <p>Exactly {@code UIWindow.windowOverlayLayer}'s arrangement, and for the same reason: a layer added
 * with {@code addInternalChild} may live under a root that accepts no children, while the frames added
 * to <em>it</em> stay ordinary public children — so a window can still remove itself
 * ({@code removeChild} silently refuses an internal child, and returns a boolean nobody checks).</p>
 *
 * <p>That relies on an ordering the engine states as a trap: {@code markAsInternal()} <b>recurses</b>,
 * so children a container already had when it was made internal become internal too. The window layer
 * is added in the constructor — before {@code UIWindow} attaches this desktop — and every frame arrives
 * afterwards, which is exactly the side of that rule a frame needs to be on.</p>
 *
 * <h3>The layer's box IS the work area</h3>
 * <p>The taskbar (W4) is <b>laid out</b> as a bottom bar rather than overlaid, so the space left for
 * windows needs no bar-shaped special case anywhere: maximise fills the layer, drags clamp at it, and
 * W13's fullscreen hiding the bar simply re-flows the layer to full height. Windows' own model —
 * maximise respects the taskbar, fullscreen covers it — falls out of the flex column.</p>
 *
 * <h3>What is deliberately not here yet</h3>
 * <p>Raise-on-click, the active window and empty-desktop blur are W2; the taskbar is W4. <b>When raise
 * arrives it must be a {@code z-index} assignment and never a child-list move</b>: {@code removeChild}/
 * {@code addChild} run {@code unregisterElement}/{@code registerElement} over the whole frame subtree —
 * session capture, modal and popover stack pops, every Taffy node destroyed and rebuilt — and a raise
 * happens on a click, which is precisely when a widget must never rebuild the elements it is being
 * clicked on. {@code sortedChildren} already keeps paint order and hit-testing agreeing by z.</p>
 */
public class Desktop extends UIElement {

    /** The layer frames live on, and the work area they are bounded by. */
    public static final String WINDOW_LAYER_CLASS = "__windows__";

    private final WindowLayer windows = new WindowLayer();

    /**
     * How many windows the cascade has placed since it last wrapped — Win32's {@code CW_USEDEFAULT},
     * which offsets each successive window by a caption height and starts over when it walks off.
     */
    private int cascadeStep;

    /**
     * {@code UIWindow} builds the one desktop a window has; {@code UIWindow.desktop()} is how to reach
     * it. Public because the tag registry needs a factory and a test needs to be able to make one, not
     * because an application should.
     */
    public Desktop() {
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.COLUMN));
        // THE CLASS IS THE WHOLE OF THE LAYER'S GEOMETRY. Without it `desktop .__windows__` matches
        // nothing, the work area sizes to content, and its children are all absolutely positioned --
        // so it measures 0x0 and every rule that reads the work area quietly stands down instead of
        // failing: no clamp, no cascade, windows written wherever they were asked to go.
        windows.addClass(WINDOW_LAYER_CLASS);
        addInternalChild(windows);
        syncPresence();
    }

    /**
     * Fills the root while there is a window to show, and takes up no space at all while there is not.
     *
     * <p><b>IMPORTANT origin</b>, matching {@code UIWindow.windowOverlayLayer}: this is the compositor's
     * own surface, and a stylesheet that could move or resize it could put every window somewhere the
     * clamp does not agree with. Everything else about a desktop — what it paints, what its taskbar
     * looks like — stays in {@code ua/desktop.css} where a theme can reach it.</p>
     *
     * <p>The empty case is not an optimisation. A full-size overlay hit-tests, so an empty desktop
     * sitting over an application's own root would eat every click that landed on bare background —
     * a UI that had never opened a window would simply stop responding, and nothing about the symptom
     * would point here.</p>
     */
    private void syncPresence() {
        boolean live = !windows.frames.isEmpty();
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> {
            l.positionType(TaffyPosition.ABSOLUTE).left(0).top(0);
            if (live) l.widthPercent(100f).heightPercent(100f);
            else l.width(0).height(0);
        });
    }

    /** Whether any window is on the desktop — i.e. whether the compositor is currently the surface. */
    public boolean isLive() {
        return !windows.frames.isEmpty();
    }

    /** A desktop owns its chrome; windows go through {@link #addWindow}. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /**
     * Puts a window on the desktop, cascading it into place unless it already has one.
     *
     * <p>Placement is <b>deferred to the frame's first layout</b> rather than computed here: the offset
     * needs the caption's measured height and the work area's measured box, and at this point neither
     * exists. {@code WindowFrame.onLayoutChanged} calls back into {@link #placeByCascade} once they do.</p>
     */
    public <T extends WindowFrame> T addWindow(T frame) {
        windows.addChild(frame);
        return frame;
    }

    /** The window layer — the work area's box, and the containing block every frame is placed in. */
    public UIElement windowLayer() {
        return windows;
    }

    /**
     * Every window currently on the desktop, in insertion order.
     *
     * <p>A live view of the layer's own typed list, not a copy and not a filtered scan — see
     * {@link WindowLayer}. Iterating it while adding or removing a window is the usual
     * {@code ConcurrentModificationException}; copy first if a caller needs that.</p>
     */
    public List<WindowFrame> windows() {
        return windows.frames();
    }

    /**
     * Places a window nobody positioned, one caption height further down and across than the last —
     * Win32's cascade, wrapping back to the origin when the next step would put the window's own body
     * off the work area.
     *
     * <p>Reads only measured values, and does nothing while any of them is zero: a box measuring zero
     * carries no information about where anything belongs, and the frame will ask again on the layout
     * pass that gives it one.</p>
     */
    void placeByCascade(WindowFrame frame) {
        float step = frame.captionHeight();
        float areaWidth = windows.getRuntimeCache().getWidth();
        float areaHeight = windows.getRuntimeCache().getHeight();
        float frameWidth = frame.getRuntimeCache().getWidth();
        float frameHeight = frame.getRuntimeCache().getHeight();
        if (step <= 0f || areaWidth <= 0f || areaHeight <= 0f || frameWidth <= 0f || frameHeight <= 0f) {
            return;
        }

        float offset = cascadeStep * step;
        if (offset + frameWidth > areaWidth || offset + frameHeight > areaHeight) {
            cascadeStep = 0;
            offset = 0f;
        }
        cascadeStep++;
        frame.moveTo(offset, offset);
    }

    /**
     * The work area, as an element that tells its windows when it changed size.
     *
     * <p>A subclass for two overrides. The first is the layout callback: the alternative is a per-frame
     * clamp ticker, which is what {@code Dialog} has to do, having no container it owns. Here the
     * container is ours, and its own callback fires exactly when the work area changes and at no other
     * time — a window moving inside the layer does not resize the layer.</p>
     *
     * <h3>The frame list is maintained, never derived</h3>
     * <p>The obvious spelling of "tell every window" is a walk over {@code getChildren()} with an
     * {@code instanceof} filter, and it is the wrong shape here for a reason that is not about how many
     * windows there are: this runs inside a <b>layout callback</b>, which fires on every pass that
     * resizes the work area, and a re-clamp writes style and re-dirties layout — so the walk repeats
     * for as long as the geometry is settling. Anything on that path is worth keeping O(windows) with
     * no per-element type test, and the list costs one {@code instanceof} per <em>mutation</em> instead.</p>
     *
     * <p>Both public mutation paths are covered and there are exactly two: {@code addChild} delegates to
     * {@link #addChildAt}, and {@code removeSelf}, {@code clearAllChildren} and a reparent to another
     * parent all go through {@link #removeChild}. Internal children never reach either, which is why a
     * resize handle or an overlay slot cannot desynchronise the list.</p>
     *
     * <p>And it <b>refuses anything that is not a window</b>. Not defensiveness: it is what makes the
     * list provably the layer's children rather than a cache that could drift from them, so nothing
     * downstream ever has to re-filter. {@code Desktop.addWindow} is the sanctioned way in.</p>
     */
    private final class WindowLayer extends UIElement {

        private final List<WindowFrame> frames = new ArrayList<>();

        List<WindowFrame> frames() {
            return Collections.unmodifiableList(frames);
        }

        @Override
        public UIElement addChildAt(UIElement child, int index) {
            if (!(child instanceof WindowFrame)) {
                throw new UnsupportedOperationException(
                        "The desktop's window layer holds WindowFrames — use Desktop.addWindow(frame)");
            }
            super.addChildAt(child, index);
            // AFTER the super call, and at the same index: addChildAtInternal may have re-entered this
            // class through removeChild (a frame moving from another desktop), and inserting first would
            // then be undone by that removal. Index-matched so the list order is the child order, which
            // is what makes "insertion order" in windows() mean anything.
            frames.add(Math.min(index, frames.size()), (WindowFrame) child);
            syncPresence();
            return this;
        }

        @Override
        public boolean removeChild(UIElement child) {
            boolean removed = super.removeChild(child);
            if (removed) {
                frames.remove(child);
                // THE LAST WINDOW LEAVING GIVES THE SURFACE BACK. Without this a desktop that has ever
                // been used goes on covering the application's own root forever, so closing the last
                // window would leave a UI that paints correctly and answers no clicks.
                syncPresence();
            }
            return removed;
        }

        @Override
        protected void onLayoutChanged() {
            super.onLayoutChanged();
            for (int i = 0; i < frames.size(); i++) frames.get(i).reclamp();
        }
    }
}
