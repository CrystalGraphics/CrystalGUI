package com.crystalgui.ui;

import com.crystalgraphics.api.PoseStack;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgui.ui.tree.UITreeTraversal;
import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.AvailableSpace;
import dev.vfyjxf.taffy.style.TaffyDimension;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;
import dev.vfyjxf.taffy.tree.Layout;
import dev.vfyjxf.taffy.tree.NodeId;
import dev.vfyjxf.taffy.tree.TaffyTree;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

import java.util.*;

/**
 * Runtime engine. Owns the paint context, the live
 * {@link TaffyTree}, and drives the per-frame layout + paint entry points.
 *
 * <p>Deliberately does NOT implement any platform (LWJGL2/LWJGL3/MC) widget or Screen
 * interface itself. Need MC-Sided adapters.</p>
 */
public final class UIWindow {
    public static final Layout EMPTY_LAYOUT = new Layout();

    public final Ui ui;

    @Getter
    private final TaffyTree taffyTree;

    /**
     * Commands invocable in this window — what key bindings, menu items and the command palette all
     * resolve ids against.
     *
     * <p>Per window rather than a global static, for two reasons. A server-driven UI can have two windows
     * whose {@code "edit.save"} legitimately mean different things; and a global mutable registry leaks
     * between tests, so one test registering a command would silently change what another resolves.</p>
     *
     * <p>Declared <b>before</b> {@link #inputHandler}: field initialisers run in source order and the
     * handler's keymap resolver takes this in its constructor.</p>
     */
    @Getter
    private final CommandRegistry commands = new CommandRegistry();

    @Getter
    private final UIInputHandler inputHandler = new UIInputHandler(this);

    @Getter
    private final StyleEngine styleEngine = new StyleEngine(this);
    private long lastFrameNanos = System.nanoTime();

    private final List<UIElement> elements = new ArrayList<>();

    private int actualScreenWidth;
    private int actualScreenHeight;

    @Getter
    private float uiScale = 2;
    /** @see #getRootTransform() */
    private final Matrix4f rootTransform = new Matrix4f().scale(2, 2, 1f);
    @Getter
    private float leftPos, topPos, width, height;
    @Getter
    private float layoutWidth = Float.NaN, layoutHeight = Float.NaN;
    @Getter @Setter
    private int screenWidth, screenHeight;


    private final Map<NodeId, UIElement> elementByNode = new HashMap<>();
    private final Set<NodeId> nodesWithNewLayout = new HashSet<>();
    private final Set<NodeId> nodesWithNewGeometry = new HashSet<>();

    /** @see TopLayer — CSS Position 4 §top-layer; owns its own list, reparenting and passes. */
    @Getter
    private final TopLayer topLayer = new TopLayer(this);

    /**
     * The document's modal dialogs, outermost first — the spec's "active modal dialog" generalised to a
     * stack so one modal can open another. The topmost is the active one; everything outside its subtree
     * is inert.
     *
     * <p>A list rather than a single field because {@code showModal()} on top of an open modal is legal
     * and has to unwind in order. Kept here rather than in {@link TopLayer} because modality is about
     * <em>inertness</em>, not painting, and because the spec hangs it off the {@code Document} — which is
     * what this class is.</p>
     */
    private final List<UIElement> modalStack = new ArrayList<>();

    /**
     * Open <b>auto</b> popovers, bottom-most first — the spec's "showing auto popover list".
     *
     * <p>Separate from {@link #closeWatchers} on purpose, because the two answer different questions and
     * the same element is often in one and not the other. This list drives <b>light dismiss</b> (click
     * outside); that one drives <b>Escape</b>. A modal dialog has a close watcher but is not light
     * dismissable, and a {@code manual} popover is neither.</p>
     */
    private final List<UIElement> autoPopovers = new ArrayList<>();

    /**
     * Elements with an active close watcher, oldest first — Escape asks the <b>last</b> one.
     *
     * <p>The web's {@code CloseWatcher} is a general primitive that dialogs and popovers both build on,
     * so this is one stack rather than a modal-specific field. Ordering is the whole point: a dropdown
     * opened from inside a modal must eat Escape before the modal does, exactly as a live drag eats it
     * before either.</p>
     */
    private final List<UIElement> closeWatchers = new ArrayList<>();

    /**
     * Monotonic counter bumped every time a popover is shown — including a re-show of one already open.
     *
     * <p>Read before a press is dispatched and handed back to {@link #lightDismiss(UIElement, int)}, so a
     * popover shown <em>during</em> that press is exempt from it. See there for why a counter and not a
     * snapshot of the stack.</p>
     */
    private int popoverShowSeq;

    public UIWindow(Ui ui) {
        this.ui = ui;
        // Deliberately NOT installing edit.undo/edit.redo here — see UndoCommands.install(UIWindow).
        // This engine never injects its own defaults: StyleSheet.DEFAULT says so in its own header, and
        // a keymap that acquires bindings nobody registered is the same surprise as a stylesheet that
        // applies itself. It cost four KeymapTest failures to be reminded.
        this.taffyTree = new TaffyTree();
        this.taffyTree.disableRounding();
        this.taffyTree.setLayoutChangeListener(((nodeId, oldLayout, newLayout) -> {
            nodesWithNewLayout.add(nodeId);
            if (Objects.equals(oldLayout, newLayout)) return;
            nodesWithNewGeometry.add(nodeId);
        }));
    }

    public void init(int screenWidth, int screenHeight) {
        if (this.actualScreenWidth == screenWidth && this.actualScreenHeight == screenHeight)
            return;

        inputHandler.resetHandler();
        this.actualScreenWidth = screenWidth;
        this.actualScreenHeight = screenHeight;

        this.screenWidth = Math.round(actualScreenWidth / uiScale);
        this.screenHeight = Math.round(actualScreenHeight / uiScale);

        final var rootElement = ui.rootElement;

        if (rootElement.getAttachedWindow() != this)
            rootElement.setAttachedWindow(this);

        rootElement.initScreen(this.screenWidth, this.screenHeight);
        rootElement.getStyle().markTaffyStyleDirty();
        calculateLayout();
    }

    /**
     * Where an overlay — a menu, a dialog, a toast — may legally be parented, near {@code near}.
     *
     * <p><b>{@code ui.rootElement} is not the answer, and assuming it was is a bug this codebase hit five
     * times in one afternoon.</b> A promoted element must be in the tree before it can be promoted, so
     * every overlay has to be parented first — and the obvious place to put it is the root. That works
     * right up until the root is a composite: {@code CrystalEditor} returns
     * {@code acceptsPublicChildren() == false}, and every one of those five call sites threw
     * {@code UnsupportedOperationException} from wherever it happened to be standing.</p>
     *
     * <p>The nearest ancestor that accepts children is the better answer rather than merely a working one.
     * Promotion reparents the Taffy node to the root anyway, so the DOM parent decides only cascade
     * inheritance and lifetime — and an overlay inherits the colours of the panel it belongs to, and goes
     * away when that panel does.</p>
     *
     * @param near where the overlay belongs, usually what was clicked. Null means window-level, which is
     *             right for a command palette and wrong for a context menu
     */
    public UIElement overlayHost(@Nullable UIElement near) {
        for (UIElement element = near; element != null; element = element.getParent()) {
            if (element.acceptsPublicChildren()) return element;
        }
        // The root when it CAN take children -- the long-standing answer, and the one every caller already
        // expects -- and the window's own layer only when it cannot. Falling through to the layer
        // unconditionally also works, but it would move every window-level overlay in the engine for the
        // sake of the one case that was broken.
        return ui.rootElement.acceptsPublicChildren() ? ui.rootElement : windowOverlayLayer();
    }

    /** @see #windowOverlayLayer() */
    private final UIElement overlayLayer = new UIElement();

    /**
     * The host of last resort — a layer this window owns, which <b>cannot</b> refuse an overlay.
     *
     * <p>This exists because the search above has no guaranteed answer, and the obvious fallback is the one
     * element most likely to be wrong. Returning {@code ui.rootElement} unconditionally is what let the
     * composite-root crash come back a fourth time: the walk fixed every overlay anchored to something, and
     * a <em>window-level</em> overlay — a command palette, a New File prompt — passes {@code near == null},
     * never enters the loop, and fell straight through to a root that refuses. Each fix covered the case in
     * front of it and left the fallback as the original bug, which is why it kept reappearing somewhere new
     * and looking unrelated.</p>
     *
     * <p>Attached with {@code addInternalChild}, which bypasses {@code acceptsPublicChildren} by design —
     * the same mechanism every composite uses to build its own parts, and what makes this legal under a
     * root that accepts nothing. The layer itself is internal; overlays added to it are ordinary public
     * children, so they still remove themselves, still match selectors and still serialize exactly as they
     * did when parented anywhere else. Marking the overlays internal too would be shorter and would leave
     * every menu unable to close itself, since {@code removeChild} refuses an internal child.</p>
     *
     * <p>Zero-sized and absolutely positioned, so it contributes nothing to layout. That is sound because
     * everything routed here is promoted to the top layer, whose containing block is the root rather than
     * this — an overlay that is <em>not</em> promoted has no business being parented by the window at all,
     * which is the contract {@link #addOverlay} already states.</p>
     *
     * <p>Built on first use rather than in {@link #init}: a window is legally constructed, handed a root and
     * asked for an overlay in any order, and a field that is only correct when init ran first is the same
     * shape of latent bug this method exists to remove.</p>
     */
    private UIElement windowOverlayLayer() {
        if (overlayLayer.getParent() == null) {
            overlayLayer.addClass("__overlays__");
            StyleGroup.importantPipeline(overlayLayer.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE).left(0).top(0).width(0).height(0));
            ui.rootElement.addInternalChild(overlayLayer);
        }
        return overlayLayer;
    }

    /**
     * Parents an overlay somewhere legal and returns it.
     *
     * <p>Use this rather than {@code addChild} for anything that will be promoted. {@code OverlayHostTest}
     * enforces that nothing in {@code core/} reaches for {@code rootElement.addChild} instead.</p>
     */
    public <T extends UIElement> T addOverlay(T overlay, @Nullable UIElement near) {
        if (overlay.getParent() == null) overlayHost(near).addChild(overlay);
        return overlay;
    }

    /** The root's declared width/height, or {@code auto} when unset. */
    private TaffyDimension rootDimension(StyleProperty<TaffyDimension> property) {
        return Optional.ofNullable(ui.rootElement.getStyle().computeCandidate(property))
                .orElseGet(TaffyDimension::auto);
    }

    /**
     * Refreshes the available space handed to Taffy from the root's <em>current</em> declared size.
     *
     * <p>Only a percentage root gets definite available space; anything else sizes to content. This
     * has to be re-read per layout rather than once at {@link #init}, because {@code init} runs before
     * any stylesheet has been applied (scenes call {@code init} then {@code paintFrame}, and
     * {@code drainDirtyMatch} only runs inside {@code calculateStyle}) and then early-returns forever
     * after. A root that becomes percentage-sized via CSS would otherwise keep {@code MAX_CONTENT}
     * available space for the rest of the run and size to its content instead.</p>
     */
    private void resolveRootAvailableSpace() {
        this.layoutWidth = rootDimension(LayoutProperties.WIDTH).isPercent() ? this.screenWidth : Float.NaN;
        this.layoutHeight = rootDimension(LayoutProperties.HEIGHT).isPercent() ? this.screenHeight : Float.NaN;
    }

    /**
     * Recomputes the root's on-screen box and centring offset from its <em>resolved</em> layout.
     *
     * <p>Must run per layout, not once per screen resize. {@link UIElement#getLayoutX()} returns
     * {@link #getLeftPos()} for the root and every other element's absolute position accumulates from
     * there, so a stale offset here silently mis-positions the entire tree — which is exactly what
     * happened to any window whose root was sized from a stylesheet rather than from Java.</p>
     */
    private void resolveRootPlacement() {
        final var rootElement = ui.rootElement;
        var width = rootDimension(LayoutProperties.WIDTH);
        var height = rootDimension(LayoutProperties.HEIGHT);

        boolean isRelative = Optional.ofNullable(
                        rootElement.getStyle().computeCandidate(LayoutProperties.POSITION))
                .orElse(TaffyPosition.RELATIVE) != TaffyPosition.ABSOLUTE;

        var bounds = rootElement.getRuntimeCache();
        this.width = switch (width.getType()) {
            case PERCENT -> width.getValue() * this.screenWidth;
            case LENGTH -> width.getValue();
            default -> bounds.getWidth(); // auto — take whatever the layout resolved to
        };
        this.height = switch (height.getType()) {
            case PERCENT -> height.getValue() * this.screenHeight;
            case LENGTH -> height.getValue();
            default -> bounds.getHeight();
        };

        var rootTaffyLocation = rootElement.getTaffyLayout().location();
        float newLeft = Math.round(isRelative ? (this.screenWidth - this.width) / 2 : rootTaffyLocation.x);
        float newTop = Math.round(isRelative ? (this.screenHeight - this.height) / 2 : rootTaffyLocation.y);

        // Only invalidate on a real change: every element's cached absolute position derives from
        // these, so clearing unconditionally would throw the whole tree's layout cache away each frame.
        if (newLeft != this.leftPos || newTop != this.topPos) {
            this.leftPos = newLeft;
            this.topPos = newTop;
            rootElement.clearLayoutCache();
        }
    }

    void calculateLayout() {
        resolveRootAvailableSpace();

        TaffySize<AvailableSpace> availableSpace = new TaffySize<>(
                Float.isNaN(layoutWidth) ? AvailableSpace.MAX_CONTENT : AvailableSpace.definite(layoutWidth),
                Float.isNaN(layoutHeight) ? AvailableSpace.MAX_CONTENT : AvailableSpace.definite(layoutHeight)
        );

        int passes = 0;
        while (isLayoutDirty()) {
            // A LAYOUT THAT DOES NOT SETTLE MUST NOT HANG THE PROCESS.
            //
            // This loop runs until nothing is dirty, and an element that re-dirties the tree from its own
            // onLayoutChanged makes that never happen. Every occurrence so far has presented identically:
            // the window stops responding before it paints, with a stack that is pure Taffy and names
            // nothing that could be searched for. Three separate causes have worn that same disguise --
            // a subtree wrongly marked internal so removals were refused and the tree grew without bound,
            // preview attachment adding elements from inside this very loop, and a stylesheet rule
            // reaching into a canvas's absolutely positioned plane.
            //
            // So the loop is bounded, and the overflow is REPORTED rather than silently tolerated: one
            // more pass runs with dirtying recorded, and the elements responsible are named. A frame
            // abandoned mid-settle is a visibly wrong frame; a hung window is an unusable program, and
            // the log line is the difference between a five-attempt hunt and a name.
            if (++passes > MAX_LAYOUT_PASSES) {
                reportUnsettledLayout(availableSpace);
                break;
            }
            if (taffyTree.isDirty(ui.rootElement.taffyNodeId)) {
                taffyTree.computeLayout(ui.rootElement.taffyNodeId, availableSpace);

                for (var nodeId : nodesWithNewLayout) {
                    var element = elementByNode.get(nodeId);
                    if (element != null) {
                        element.onLayoutChanged(nodesWithNewGeometry.contains(nodeId));
                    }
                }
                nodesWithNewLayout.clear();
                nodesWithNewGeometry.clear();
            }

        }

        resolveRootPlacement();
    }

    public boolean isLayoutDirty() {
        return taffyTree.isDirty(ui.rootElement.taffyNodeId);
    }

    /**
     * How many settle passes a frame may take before the layout is declared broken.
     *
     * <p>Generous on purpose. A tree that genuinely needs several passes is normal — {@code UIText}
     * re-wraps against its settled width and pushes a height back, and a few of those chained together
     * take a handful. Sixty-four is far past anything legitimate and far short of a frame a human would
     * notice, so this only ever fires on a real cycle.</p>
     */
    private static final int MAX_LAYOUT_PASSES = 64;

    /** Set only while diagnosing an unsettled layout — see {@link #noteDirtied}. */
    private boolean recordingDirtySources;

    private final java.util.LinkedHashSet<UIElement> dirtySources = new java.util.LinkedHashSet<>();

    /**
     * Records an element that dirtied the tree, <b>while diagnosing only</b>.
     *
     * <p>Called from {@link UIElement#markTreeDirty()}. Costs a field read and a branch on the normal
     * path, which is why the recording is opt-in rather than always on: this is the hottest write in the
     * layout system.</p>
     */
    void noteDirtied(UIElement element) {
        if (recordingDirtySources) dirtySources.add(element);
    }

    /**
     * Runs one more pass with dirtying recorded, then names the culprits.
     *
     * <p>The elements listed are the ones re-dirtying the tree after it has been laid out — i.e. the
     * cycle. Reported with tag and classes rather than identity, because what a reader needs is
     * "{@code shadergrapheditor.__content__}" — something to grep for.</p>
     */
    private void reportUnsettledLayout(TaffySize<AvailableSpace> availableSpace) {
        dirtySources.clear();
        recordingDirtySources = true;
        try {
            if (taffyTree.isDirty(ui.rootElement.taffyNodeId)) {
                taffyTree.computeLayout(ui.rootElement.taffyNodeId, availableSpace);
                for (var nodeId : nodesWithNewLayout) {
                    var element = elementByNode.get(nodeId);
                    if (element != null) {
                        element.onLayoutChanged(nodesWithNewGeometry.contains(nodeId));
                    }
                }
                nodesWithNewLayout.clear();
                nodesWithNewGeometry.clear();
            }
        } finally {
            recordingDirtySources = false;
        }

        StringBuilder culprits = new StringBuilder();
        int listed = 0;
        for (UIElement element : dirtySources) {
            if (listed++ == 12) {
                culprits.append(" ...and ").append(dirtySources.size() - 12).append(" more");
                break;
            }
            culprits.append("\n    ").append(describe(element));
        }
        dirtySources.clear();

        CrystalGuiCore.LOGGER.error(
                "Layout did not settle after {} passes — abandoning this frame. Something re-dirties the "
                        + "tree from inside onLayoutChanged; structural changes belong in a frame ticker, "
                        + "not in the layout pass. Dirtied by:{}",
                MAX_LAYOUT_PASSES, culprits.length() == 0 ? " (nothing recorded)" : culprits);
    }

    /** {@code shadergrapheditor.__content__#id} — something a reader can grep for. */
    private static String describe(UIElement element) {
        StringBuilder out = new StringBuilder(element.tagName());
        for (String cssClass : element.getClasses()) out.append('.').append(cssClass);
        if (element.getId() != null && !element.getId().isEmpty()) out.append('#').append(element.getId());
        return out.toString();
    }

    /**
     * Lays out and paints the whole tree, once, synchronously, right now. Call this from
     * wherever your per-frame render hook lives (harness scene, or later the platform
     * adapter's render callback). No batching, no queued commands — by the time this method
     * returns, every visible element's GPU draw calls have already been issued in painter's
     * order, using bounds computed by this same call.
     */
    /**
     * Everything {@link #paintFrame()} does <em>except</em> painting: advance the frame clock,
     * resolve styles, tick animations, and run layout.
     *
     * <p>Exists so layout can be driven without a GL surface or a draw — headless tests, and
     * benchmarks that need to isolate layout/shaping cost from rendering cost. A UI with many text
     * elements pays a per-element material bind at draw time that can dwarf everything else, and
     * measuring layout through {@code paintFrame()} therefore measures mostly the renderer.
     *
     * <p>Deliberately does not touch the input handler: no frame was presented, so there is nothing
     * for hover/click state to be relative to.
     */
    public void updateWithoutPainting() {
        advanceFrame();
    }

    /** Shared prologue of {@link #paintFrame()} and {@link #updateWithoutPainting()}. */
    private float advanceFrame() {
        long now = System.nanoTime();
        float deltaSeconds = (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;

        styleEngine.calculateStyle(deltaSeconds);
        tickAnimations(deltaSeconds);
        // A TICKER MAY HAVE BUILT A SUBTREE, not merely set a class on one — and an element that has
        // never matched a selector must not reach Taffy at all, because the FIRST layout pass is where
        // irreversible decisions are taken from it. Re-matching afterwards, as the loop below does, is
        // too late for those.
        //
        // UIText.selfSizesWidth is the one that bites, and it is deliberate rather than a bug: it is
        // decided EXACTLY ONCE, on the first recompute after attachment, because re-deriving it every
        // pass oscillates forever (its own javadoc has the proof). Give that one pass an unstyled
        // parent and it concludes the wrong thing permanently — Taffy's default flex-direction is
        // COLUMN, whose cross axis stretches children to the parent's width, so the text is handed a
        // real width, decides it is not self-sizing, and then contributes ZERO width for the rest of
        // its life once the real `flex-direction: row` arrives.
        //
        // What that looked like: the Blackboard rebuilds its list from a ticker (it must — rebuilding
        // mid-drag detaches the drag source), and every pill came back with its capsule shrunk to just
        // padding and its label spilling out the side onto the panel behind it. Nothing was wrong with
        // the CSS, the widget or the text; the rows had simply been measured once before they had a
        // style, and one bit of that measurement was permanent.
        if (styleEngine.hasPendingMatches()) styleEngine.calculateStyle(0f);
        calculateLayout();

        // STYLE AND LAYOUT INTERLEAVE UNTIL CLEAN — they are not one pass each in a fixed order.
        //
        // Both of the steps above can dirty the cascade. A ticker or an onLayoutChanged hook that sets a
        // class is a normal thing to write, and every virtualised list is one: ListView binds its rows from
        // inside layout, and each bind adds or removes the selection class. But drainDirtyMatch only runs
        // inside calculateStyle, which has already happened — so the class landed and the COMPUTED style
        // did not, and the row painted once with the previous occupant's.
        //
        // What that looks like is the bug that took three attempts to find, because the class and the
        // selection were provably correct the whole time: expanding a folder handed a pooled row that used
        // to be selected to some unrelated file, and that file flashed blue for one frame while the row
        // that really was selected stayed unpainted. The same is true of any class-driven visual set from a
        // ticker; selection is merely the one with a colour loud enough to notice.
        //
        // Re-running BOTH is what makes it correct: a re-cascade can change a layout input (a matched rule
        // carrying width, or a font-size a measure function reads), so laying out again is not optional.
        // Passing zero states that the frame's time is already spent — TransitionEngine reads
        // System.nanoTime() and ignores this argument entirely, so it cannot double-advance either way.
        //
        // The bound is a backstop, not an expected limit. Two rules that dirty each other would otherwise
        // spin inside a single frame with the window unpainted, which is worse than one stale frame; the
        // observed case settles in one extra pass, and UIText's own measure-and-push loop already settles
        // inside calculateLayout rather than here.
        for (int pass = 0; pass < MAX_RESTYLE_PASSES && styleEngine.hasPendingMatches(); pass++) {
            styleEngine.calculateStyle(0f);
            calculateLayout();
        }
        return deltaSeconds;
    }

    /** @see #advanceFrame() */
    private static final int MAX_RESTYLE_PASSES = 4;

    public void paintFrame() {
        advanceFrame();

        CgUiPaintContext paintContext = CgUiPaintContext.getInstance();
        paintContext.beginFrame(actualScreenWidth, actualScreenHeight);

        PoseStack pose = paintContext.getPoseStack();
        pose.pushPose();

        // Same matrix RuntimeCache.localToWorld falls back to, so painted and not-yet-painted
        // frames agree on what uiScale means. Don't inline a scale() here — that's how the two
        // definitions drifted before.
        pose.mulPoseMatrix(rootTransform);

        ui.rootElement.drawSubtree(paintContext);

        pose.popPose();

        topLayer.paint(paintContext, pose, rootTransform);

        paintContext.endFrame();
        inputHandler.beginFrame();
        inputHandler.endFrame();
    }


    public void unregisterElement(UIElement element) {
        if (element == null) return;
        // Before anything else: a detached element must not linger in the top layer, or it would keep
        // painting and hit-testing after leaving the tree.
        topLayer.remove(element);
        // Nor in the modal stack, and this one is worse than a leak — a modal that left the tree without
        // being closed would keep the ENTIRE window inert with nothing left to interact with, which is
        // unrecoverable from the user's side. Cheap unconditionally: a no-op for the elements that were
        // never modal, which is nearly all of them.
        popModal(element);
        // Same reasoning, one step further: a popover that left the tree must stop being light-dismissable
        // and stop being asked for Escape, or the stacks accumulate elements that can never be closed.
        popAutoPopover(element);
        popCloseWatcher(element);


        elementByNode.remove(element.taffyNodeId);
        if (element.taffyNodeId != null) {
            if (element.getParent() != null) {
                var parentID = element.getParent().taffyNodeId;
                // parent may already belong to other tree.
                if (parentID != null && taffyTree.containsNode(parentID)) {
                    taffyTree.removeChild(parentID, element.taffyNodeId);
                }
            }
            taffyTree.remove(element.taffyNodeId);
            element.taffyNodeId = null;
        }

        elements.remove(element);
        styleEngine.onElementDetached(element);
    }

    /**
     * The root's Taffy node — what {@link TopLayer} reparents promoted nodes onto, making the root box
     * their containing block.
     *
     * <p><b>Derived, not stored.</b> This used to be a field that {@code registerElement} was supposed to
     * fill in and never did, so it was permanently {@code null} — and because both reparenting methods
     * bail out silently on a null root, <em>promotion never actually moved a Taffy node at all</em>. The
     * one documented divergence it exists to implement (a promoted element's containing block is the
     * initial containing block) was inert from the day it was written. Nothing caught it because every
     * promoted element until now had an explicit pixel size and absolute offsets, so the wrong percentage
     * basis had nothing to show; the first promoted element sized in {@code %} — a modal backdrop — made
     * it obvious immediately. Deriving removes the class of bug rather than fixing this instance.</p>
     */
    NodeId getRootNodeId() {
        return ui.rootElement.taffyNodeId;
    }

    public void registerElement(UIElement element) {
        if (element == null) return;

        // Registering something that still holds a node means it is being registered twice. The
        // newLeaf() below would overwrite the reference and ORPHAN the old node — and an orphan is not
        // inert: Taffy keeps it in its previous parent's child list and keeps laying it out, so the
        // element has two live layouts and the stale one can win. Free it first, so registration is
        // idempotent rather than quietly accumulating nodes.
        if (element.taffyNodeId != null) {
            elementByNode.remove(element.taffyNodeId);
            if (taffyTree.containsNode(element.taffyNodeId)) taffyTree.remove(element.taffyNodeId);
            element.taffyNodeId = null;
        }
        elements.remove(element);

        elements.add(element);

        element.taffyNodeId = taffyTree.newLeaf(element.getStyle().getTaffyBridge().style);
        var measureFunc = element.measureFunc();
        if (measureFunc != null) {
            taffyTree.setMeasureFunc(element.taffyNodeId, measureFunc);
        }
        elementByNode.put(element.taffyNodeId, element);
        if (element.getParent() != null) {
            var parentID = element.getParent().taffyNodeId;
            if (taffyTree.containsNode(parentID)) {
                // taffyChildIndex, NOT getSiblingIndex: a promoted sibling holds a DOM slot and no
                // Taffy slot, so the two drift apart by one per open popup. See UIElement.
                taffyTree.insertChildAtIndex(parentID, element.taffyChildIndex(), element.taffyNodeId);
            }
        }
        styleEngine.markDirty(element);
    }

    /** Every element currently attached to this window's tree. Read-only — used by {@link StyleEngine}
     * to re-match the whole tree when a stylesheet is added or removed. */
    public List<UIElement> getElements() {
        return Collections.unmodifiableList(elements);
    }


    /**
     * The transform every element's {@code localToWorld} chain hangs off: physical pixels per
     * logical layout unit.
     *
     * <p><b>Single source of truth for what {@code uiScale} means.</b> {@link #paintFrame} seeds the
     * {@code PoseStack} from this, and {@link UIElement.RuntimeCache#localToWorld} falls back to it
     * for the root — so hit-testing is correct <em>before</em> anything has ever been painted.
     * Previously the two were defined independently (the pose scaled itself, the cache fell back to
     * identity) and disagreed by exactly {@code uiScale} until the first paint installed the real
     * matrix, which made pointer maths silently wrong in that window.</p>
     *
     * <p>The scale deliberately lives here and in the {@code PoseStack} rather than in the ortho
     * projection: {@code CgTextRenderer} picks its glyph raster size from the pose scale
     * ({@code baseTargetPx * extractMaxScale(pose)}), so moving it would rasterize glyphs at logical
     * size and let the projection magnify them — blurry text. {@code CgUiPaintContext.pushScissor}
     * also reads this matrix to reach physical {@code glScissor} pixels, which the projection has no
     * effect on.</p>
     *
     * @return the live internal matrix — treat as read-only.
     */
    public Matrix4f getRootTransform() {
        return rootTransform;
    }

    /** Rescales the whole tree. Invalidates every cached transform, since they all derive from
     * {@link #getRootTransform()} — without that, hit-testing would keep using the old scale. */
    public void setUiScale(float uiScale) {
        if (this.uiScale == uiScale) return;
        this.uiScale = uiScale;
        this.rootTransform.identity().scale(uiScale, uiScale, 1f);
        invalidatePoseCaches(ui.rootElement);
    }

    private static void invalidatePoseCaches(UIElement element) {
        element.getRuntimeCache().resetPoseCache();
        for (UIElement child : element.getChildren()) {
            invalidatePoseCaches(child);
        }
    }

    // ── Smooth scrolling ────────────────────────────────────────────────────

    /** Elements with a smooth scroll in flight. Only these are ticked, so the cost is zero on a
     * window with nothing animating. */
    private final Set<UIElement> scrollAnimations = new HashSet<>();

    void registerScrollAnimation(UIElement element) {
        scrollAnimations.add(element);
    }

    /**
     * Advances every in-flight smooth scroll. Driven from {@link #paintFrame()}; call it directly if
     * you drive layout yourself (as the headless tests do).
     */
    public void tickScrollAnimations(float deltaSeconds) {
        if (scrollAnimations.isEmpty()) return;
        scrollAnimations.removeIf(element ->
                element.getAttachedWindow() != this || !element.tickScrollAnimation(deltaSeconds));
    }

    /** Per-frame callbacks that aren't scroll animations — press-and-hold repeats, blinking carets. */
    private final Set<UIFrameTicker> tickers = new HashSet<>();

    /** Registers a per-frame callback; it is dropped as soon as it reports it's done. */
    public void registerTicker(UIFrameTicker ticker) {
        tickers.add(ticker);
    }

    /** Everything that wants a per-frame callback: smooth scrolls plus registered tickers. Driven
     * from {@link #paintFrame()}; call it directly if you drive frames yourself. */
    public void tickAnimations(float deltaSeconds) {
        tickScrollAnimations(deltaSeconds);
        if (!tickers.isEmpty()) {
            // Snapshot: a ticker may register another (or itself) while running.
            for (UIFrameTicker ticker : new ArrayList<>(tickers)) {
                if (!ticker.tickFrame(deltaSeconds)) tickers.remove(ticker);
            }
        }
    }

    // ── Tree queries ────────────────────────────────────────────────────────

    /**
     * First element in the window matching {@code selector}, in document order, or {@code null}.
     *
     * <p>Unlike {@link UIElement#querySelector}, the root element <em>is</em> a candidate — it plays
     * the part of the document here, so a window-level query considering it matches
     * {@code document.querySelector}. Same selector subset and same live-tree combinator semantics;
     * see {@link UITreeTraversal#querySelector}.</p>
     */
    public UIElement querySelector(String selector) {
        return UITreeTraversal.querySelector(ui.rootElement, selector, true);
    }

    /** Every match in the window, in document order, root included. */
    public List<UIElement> querySelectorAll(String selector) {
        return UITreeTraversal.querySelectorAll(ui.rootElement, selector, true);
    }

    /** First element in the window with this id, or {@code null}. Root included. */
    public UIElement getElementById(String id) {
        return UITreeTraversal.getElementById(ui.rootElement, id, true);
    }

    /** Every element in the window carrying this class, in document order. Root included. */
    public List<UIElement> getElementsByClassName(String className) {
        return UITreeTraversal.getElementsByClassName(ui.rootElement, className, true);
    }

    /**
     * Topmost element under the pointer, or {@code null}.
     *
     * <p>Blink's rule is "hit testing is done in paint-order" — it reuses the paint walk to record
     * hit-test data rather than keeping a second, drift-prone traversal. Same rule here: the top
     * layer paints last, so it is tested <b>first</b>, and within it <b>backwards</b>, because the
     * last-painted element is the visually topmost one.</p>
     *
     * <p>This must be a separate walk, not a reordering of the main one. {@link #elementHitTest}
     * only recurses into children when the pointer is inside a clipping ancestor's content box, so a
     * promoted element inside an {@code overflow: hidden} scroller is unreachable from the root walk
     * exactly when the pointer is outside that scroller — which is precisely the case a tooltip
     * exists to handle.</p>
     */
    public UIElement getHoveredElement(float mouseX, float mouseY) {
        UIElement promoted = topLayer.hitTest(mouseX, mouseY);
        if (promoted != null) return promoted;
        // A modal makes the entire rest of the document inert, and hit-testing an inert node must act as
        // if `pointer-events: none` — so the main-tree walk is skipped wholesale rather than filtered.
        // The modal itself lives in the top layer, so it was already offered above.
        if (getActiveModal() != null) return null;
        return elementHitTest(ui.rootElement, mouseX, mouseY);
    }

    // ── Modality ────────────────────────────────────────────────────────────

    /** The topmost open modal, or {@code null}. The spec's {@code Document}'s "active modal dialog". */
    @Nullable
    public UIElement getActiveModal() {
        return modalStack.isEmpty() ? null : modalStack.get(modalStack.size() - 1);
    }

    /**
     * Whether {@code element} is blocked by an active modal — i.e. inert by virtue of sitting outside it.
     *
     * <p>Tested against the <b>topmost</b> modal only, and that is the whole rule — the spec defines
     * inertness against "the document's active modal dialog", singular. A lower modal in the stack being
     * blocked by a higher one is not a gap, it is the point: open a modal from inside a modal and the
     * first correctly stops accepting input until the second closes.</p>
     */
    public boolean isModalBlocked(UIElement element) {
        UIElement modal = getActiveModal();
        if (modal == null || element == null) return false;
        for (UIElement el = element; el != null; el = el.getParent()) {
            if (el == modal) return false;
        }
        return true;
    }

    /** Marks {@code element} modal. Idempotent — re-pushing raises it, matching {@link TopLayer#add}. */
    public void pushModal(UIElement element) {
        Objects.requireNonNull(element, "element");
        modalStack.remove(element);
        modalStack.add(element);
    }

    /** Clears {@code element}'s modality. No-op if it was not modal. */
    public void popModal(UIElement element) {
        modalStack.remove(element);
    }

    // ── Close watchers ──────────────────────────────────────────────────────

    /** Registers {@code element} to receive Escape, ahead of anything registered before it. Idempotent —
     * re-registering raises it, which is what reopening a popup should do. */
    public void pushCloseWatcher(UIElement element) {
        Objects.requireNonNull(element, "element");
        closeWatchers.remove(element);
        closeWatchers.add(element);
    }

    public void popCloseWatcher(UIElement element) {
        closeWatchers.remove(element);
    }

    /** The element Escape should ask, or {@code null}. */
    @Nullable
    public UIElement getTopCloseWatcher() {
        return closeWatchers.isEmpty() ? null : closeWatchers.get(closeWatchers.size() - 1);
    }

    // ── Light dismiss (the popover stack) ───────────────────────────────────

    /** Open auto popovers, bottom-most first. Read-only. */
    public List<UIElement> getAutoPopovers() {
        return Collections.unmodifiableList(autoPopovers);
    }

    public void pushAutoPopover(UIElement element) {
        Objects.requireNonNull(element, "element");
        autoPopovers.remove(element);
        autoPopovers.add(element);
    }

    public void popAutoPopover(UIElement element) {
        autoPopovers.remove(element);
    }

    /**
     * The spec's <b>light dismiss</b>: a press on {@code target} closes every open auto popover that
     * {@code target} is not inside.
     *
     * <p>Ported from HTML's "light dismiss open popovers" — find the target's topmost popover ancestor,
     * then hide everything above it. Clicking <em>inside</em> a popover therefore closes its submenus but
     * not itself, and clicking anywhere unrelated closes the whole chain. Doing it any other way means
     * either a submenu that cannot be dismissed without killing its parent, or a parent that dies when
     * you reach for its child.</p>
     *
     * @param target what was pressed, or {@code null} for a press that hit nothing at all.
     */
    public void lightDismiss(@Nullable UIElement target) {
        lightDismiss(target, Integer.MAX_VALUE);
    }

    /**
     * As {@link #lightDismiss(UIElement)}, but sparing any popover shown after {@code shownBefore} — the
     * value {@link #popoverShowSeq()} returned before the press was dispatched.
     *
     * <p><b>This is what stops a popover dismissing itself.</b> Light dismiss runs after the mouse-down event
     * is delivered, so a handler that opens a context menu on press has already put it in the stack by the
     * time dismissal runs — and the pressed element is not inside it, so the naive algorithm closed the menu
     * on the very press that asked for it. It opened and vanished in the same frame, which from the outside is
     * indistinguishable from never opening at all.</p>
     *
     * <p><b>A counter rather than a snapshot of the stack</b>, and the difference is a real case: a context
     * menu that is <em>already open</em> and gets re-shown at a new position by the press is in any
     * before-snapshot, so a membership test dismisses it — right-clicking elsewhere closed the menu instead of
     * moving it. Asking "was this shown during the press" answers both that and the first-open case with one
     * rule, since {@code show} bumps the counter whether or not the popover was already open.</p>
     */
    public void lightDismiss(@Nullable UIElement target, int shownBefore) {
        if (autoPopovers.isEmpty()) return;
        UIElement ancestor = topmostPopoverAncestor(target);
        // Copy and walk downwards: closing mutates the live list, and requestClose() can run arbitrary
        // listener code that opens or closes further popovers.
        List<UIElement> doomed = new ArrayList<>();
        for (int i = autoPopovers.size() - 1; i >= 0; i--) {
            UIElement popover = autoPopovers.get(i);
            if (popover == ancestor) break;
            if (popover instanceof com.crystalgui.ui.elements.Popover p && p.getLastShownSeq() > shownBefore) {
                continue; // shown by the very press that is now dismissing
            }
            doomed.add(popover);
        }
        for (UIElement popover : doomed) popover.requestClose();
    }

    /** The current show sequence. Capture before dispatching a press; hand to
     * {@link #lightDismiss(UIElement, int)} after. */
    public int popoverShowSeq() {
        return popoverShowSeq;
    }

    /** Bumped by {@code Popover.show*}, including a re-show of an already-open popover. */
    public int nextPopoverShowSeq() {
        return ++popoverShowSeq;
    }

    /**
     * The innermost open auto popover that {@code target} belongs to, or {@code null}.
     *
     * <p>"Belongs to" covers DOM ancestry <em>and</em> the invoker: a press on the button that opened a
     * popover must not light-dismiss it, or a dropdown button would close on the press and reopen on the
     * click, visibly flickering and never staying shut.</p>
     */
    @Nullable
    private UIElement topmostPopoverAncestor(@Nullable UIElement target) {
        if (target == null) return null;
        for (int i = autoPopovers.size() - 1; i >= 0; i--) {
            UIElement popover = autoPopovers.get(i);
            if (isInclusiveDescendant(target, popover)) return popover;
            UIElement invoker = popover.getPopoverInvoker();
            if (invoker != null && isInclusiveDescendant(target, invoker)) return popover;
        }
        return null;
    }

    private static boolean isInclusiveDescendant(UIElement node, UIElement of) {
        for (UIElement el = node; el != null; el = el.getParent()) {
            if (el == of) return true;
        }
        return false;
    }

    UIElement elementHitTest(UIElement element, float mouseX, float mouseY) {
        if (element.getStyle().taffyBridge.style.display == TaffyDisplay.NONE) return null;
        // Hit-testing off takes the whole SUBTREE with it, matching CSS `pointer-events: none`, which
        // applies to an element and its descendants alike.
        //
        // This used to only skip the element itself, so children of a "transparent" element were
        // still hittable — and since children are tested BEFORE the parent's own flag is consulted, a
        // pointer-transparent container with any content at all was transparent everywhere except
        // exactly where its content was. It surfaced on the drag ghost, whose text label stayed
        // hittable: the ghost sits under the cursor by construction, so drop targeting rejected every
        // position where the label happened to be and accepted the rest — hit testing that looked
        // random. Every widget using this before was a childless leaf, which is why it went unnoticed.
        if (!element.isHitTest()) return null;
        // Same subtree rule, and the spec asks for it in the same words: hit-testing an inert node "must
        // act as if the pointer-events CSS property were set to none". Only the element's OWN attribute
        // is read — an ancestor's already returned above, on the way down.
        if (element.isInertAttribute()) return null;

        Matrix4f transform = element.getRuntimeCache().worldToLocal.get();
        var local = Transform2D.apply(transform, mouseX, mouseY);
        float localX = local.x(), localY = local.y();
        var overflow = element.resolveOverflowClip();
        boolean contentCanClipOut = overflow.isClipped();
        if (!contentCanClipOut || element.isMouseOverContent(localX, localY, overflow)) {
            for (var child : element.getRuntimeCache().sortedChildren.get()) {
                // Promoted children are tested by the top-layer walk in getHoveredElement, ahead of
                // this one. Testing them here too would reach them through their DOM ancestor's clip
                // — the wrong answer, and only reachable when the pointer happens to be inside that
                // ancestor, so it would present as an intermittent off-by-a-container hit.
                if (child.isInTopLayer()) continue;
                var result = elementHitTest(child, mouseX, mouseY);
                if (result != null) {
                    return result;
                }
            }
        }
        if (element.isMouseOverElement(localX, localY)) {
            return element;
        }
        return null;
    }

}
