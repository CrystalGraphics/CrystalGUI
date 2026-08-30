package com.crystalgui.testsupport;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.LayoutGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.event.UIEvent;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Before;

/**
 * <b>The new engine's fixture, with the old one's verbs</b> — {@code plan_m6.md} §2.3.
 *
 * <p>164 of the 214 widget test files construct a {@code UIWindow}, 127 drive frames and 102 read
 * {@code getRuntimeCache()}. Those tests are the net the port is held to, so they move with the
 * widgets — and a test that has to be rewritten to move is a test somebody rewrites into passing.
 * This offers the SAME verbs {@code UiTestBase} and {@code EditorTestBase} do, over a
 * {@link UIDocument}, so a test changes its base class and its element types and nothing else.</p>
 *
 * <h3>Drive input through {@code consumeMouseEvent} at a POINT, never {@code send} at a node</h3>
 *
 * <p>The old engine's rows record this four separate times, each a bug a test could not see:
 * dispatching straight at an element skips the focus walk, so a widget that can never be focused
 * passes; it skips {@code emitMouseDown}, so "focus is already in this window" is never established
 * and the press-in-content rule is invisible; and it skips keymap resolution entirely, so a widget
 * eating a chord it has no use for looks fine. {@link #press} therefore moves the pointer and feeds
 * a real button event, exactly as a platform would.</p>
 *
 * <h3>Animations off, and it is not squeamishness</h3>
 *
 * <p>Inherited from {@link UiTestBase} for the same reason it gives: a live transform is a REAL
 * transform, hit-testing walks the same chain the paint does, and a test pressing something mid-
 * animation misses it by an amount that depends on the machine.</p>
 */
public abstract class UiDocumentTestBase extends UiTestBase {

    /** The surface, in logical units. The same 800x600 the service fixtures use. */
    public static final float W = 800f;
    public static final float H = 600f;

    protected UIDocument document;

    /** Monotonic, so a synthesised double-click is a decision rather than an accident of timing. */
    private long clock = 1_000L;

    @Before
    public final void buildDocument() {
        document = new UIDocument().markFrameThread();
    }

    /** Installs the user-agent sheet. NOT automatic: a test that asserts on it must say so. */
    protected final void withDefaultStyles() {
        document.styles().addStylesheet(StyleSheet.DEFAULT);
    }

    // ── Building ─────────────────────────────────────────────────────────────

    /** A node at an absolute position — what most geometry fixtures want. */
    protected static UINode at(String id, float x, float y, float width, float height) {
        UINode node = new UINode().setId(id);
        layout(node, l -> l.positionType(TaffyPosition.ABSOLUTE).left(x).top(y).width(width).height(height));
        return node;
    }

    /** A node sized in flow. */
    protected static UINode sized(String id, float width, float height) {
        UINode node = new UINode().setId(id);
        layout(node, l -> l.width(width).height(height));
        return node;
    }

    /**
     * Writes layout style at INLINE origin — an AUTHOR's write, which is what a fixture is.
     *
     * <p>Never {@code importantPipeline}: the engine may not write there and neither may its tests,
     * or a fixture is asserting against a cascade position no widget can reach.</p>
     */
    protected static void layout(UINode node, Consumer<LayoutGroup> style) {
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), style);
    }

    // ── Driving ──────────────────────────────────────────────────────────────

    /** A whole frame: motion, cascade, layout, paint-free, and the pointer diffed against it. */
    protected final void frame() {
        document.frame(0.016f, W, H);
    }

    /** Layout only — no motion, no input. For a geometry assertion that should not need a frame. */
    protected final void layoutOnly() {
        document.layout(W, H);
    }

    protected final void move(float x, float y) {
        document.input().consumeMouseEvent(mouse(x, y, -1, false, 0f));
    }

    protected final void press(float x, float y) {
        press(x, y, 0);
    }

    protected final void press(float x, float y, int button) {
        move(x, y);
        document.input().consumeMouseEvent(mouse(x, y, button, true, 0f));
    }

    protected final void release(float x, float y) {
        release(x, y, 0);
    }

    protected final void release(float x, float y, int button) {
        move(x, y);
        document.input().consumeMouseEvent(mouse(x, y, button, false, 0f));
    }

    /** Press and release at one point — one click, with the frame between them a real one. */
    protected final void click(float x, float y) {
        press(x, y);
        frame();
        release(x, y);
    }

    protected final void wheel(float notches) {
        document.input().consumeMouseEvent(mouse(0f, 0f, -1, false, notches));
    }

    /** @return whether anything consumed it — which is what a host acts on. */
    protected final boolean key(int keyCode, boolean pressed) {
        return document.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event((char) 0, keyCode, pressed, false, clock++));
    }

    protected final boolean keyPress(int keyCode) {
        return key(keyCode, true);
    }

    private CgSystemInput.Mouse.Event mouse(float x, float y, int button, boolean pressed, float wheel) {
        return new CgSystemInput.Mouse.Event((int) x, (int) y, 0, 0, button, pressed, wheel, clock++);
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /** What is under a point, or null over nothing. The hit test needs no paint to have happened. */
    protected final UINode hit(float x, float y) {
        Box box = document.boxes().hitTest(x, y);
        return box == null ? null : box.node();
    }

    /** A node's settled box. Fails loudly rather than answering a zero box nobody asked for. */
    protected final Box boxOf(UINode node) {
        Box box = document.boxes().boxOf(node);
        if (box == null) {
            throw new AssertionError("no box for " + node + " -- hidden, frozen, or laid out yet?");
        }
        return box;
    }

    /** Every node under {@code scope} carrying {@code className}, in document order. */
    protected static List<UINode> allWithClass(UINode scope, String className) {
        return scope.getElementsByClassName(className);
    }

    /** Every node in the COMPOSED subtree, shadow trees included — what paint and hit-testing walk. */
    protected static List<UINode> composed(UINode scope) {
        List<UINode> out = new ArrayList<>();
        for (UINode node : scope.composedSubtree()) out.add(node);
        return out;
    }

    // ── Listening ────────────────────────────────────────────────────────────

    /**
     * A TARGET-phase listener — what a widget's own handler is.
     *
     * <p>Prefer this to a capture+bubble subscription in a fixture: the boundary events and the
     * shadow retarget both dispatch to a chain, so a listener attached in every phase fires more than
     * once and the count is not the thing the test meant to assert.</p>
     */
    protected static <T extends UIEvent> void onTarget(UINode node, Class<T> type,
                                                       UIEvent.Listener<UINode, T> listener) {
        node.events.getGroup(type).attachListener(listener, false, false);
    }

    /** A bubble-phase listener — what an ancestor watching its subtree wants. */
    protected static <T extends UIEvent> void onBubble(UINode node, Class<T> type,
                                                       UIEvent.Listener<UINode, T> listener) {
        node.events.getGroup(type).attachListener(listener, false, true);
    }
}
