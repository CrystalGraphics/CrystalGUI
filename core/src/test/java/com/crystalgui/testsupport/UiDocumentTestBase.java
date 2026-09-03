package com.crystalgui.testsupport;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.LayoutGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.UIEvent;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import com.crystalgui.desktop.Desktop;
import org.junit.After;
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

    /**
     * <b>How wide it is on screen, and zero when it is not on screen at all.</b>
     *
     * <p>{@code box()} is nullable: a node that is hidden, frozen, {@code display: none} or simply
     * not in a document has NO box, where the old engine's runtime cache always answered. So a
     * visibility check written as {@code node.box().width() > 0} does not read false, it throws --
     * and it throws for exactly the state it was asked about.</p>
     *
     * <p>Only for asking whether something is showing. A measurement that must exist should read
     * {@code box()} directly and fail loudly if it is null, because "zero-sized" and "never laid
     * out" are different facts and a helper that flattens them hides the second.</p>
     */
    protected static float widthOf(UIElement node) {
        Box box = node == null ? null : node.box();
        return box == null ? 0f : box.width();
    }

    /** The content-box height, zero when there is no box. @see #widthOf */
    protected static float contentBoxHeightOf(UIElement node) {
        Box box = node == null ? null : node.box();
        return box == null ? 0f : box.contentBoxHeight();
    }

    /** @see #widthOf */
    protected static float heightOf(UIElement node) {
        Box box = node == null ? null : node.box();
        return box == null ? 0f : box.height();
    }

    /**
     * <b>Animations back on after every test, wherever the test left them.</b>
     *
     * <p>{@code Desktop.setAnimationsEnabled} is STATIC, so a class that turns it off and fails to
     * put it back turns it off for every class that runs after it in the same JVM -- and JUnit
     * orders neither classes nor {@code @After} methods, so the damage lands on a different victim
     * each run. It presents as a flaky suite whose failure COUNT moves between runs on an unchanged
     * tree, which reads as a race in the engine rather than a leaked flag in a fixture.</p>
     *
     * <p>Restoring the PRODUCTION DEFAULT rather than a value captured on the way in is the half
     * that is easy to get wrong: a captured value can itself be somebody else's leak, so putting it
     * back propagates the leak instead of ending it. A test that wants them off says so in its own
     * {@code @Before}, which runs after this.</p>
     */
    @After
    public void animationsBackOnHoweverTheTestLeftThem() {
        Desktop.setAnimationsEnabled(true);
    }

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
    protected static UIElement at(String id, float x, float y, float width, float height) {
        UIElement node = new UIElement().setId(id);
        layout(node, l -> l.positionType(TaffyPosition.ABSOLUTE).left(x).top(y).width(width).height(height));
        return node;
    }

    /** A node sized in flow. */
    protected static UIElement sized(String id, float width, float height) {
        UIElement node = new UIElement().setId(id);
        layout(node, l -> l.width(width).height(height));
        return node;
    }

    /**
     * Writes layout style at INLINE origin — an AUTHOR's write, which is what a fixture is.
     *
     * <p>Never {@code importantPipeline}: the engine may not write there and neither may its tests,
     * or a fixture is asserting against a cascade position no widget can reach.</p>
     */
    protected static void layout(UIElement node, Consumer<LayoutGroup> style) {
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), style);
    }

    // ── Driving ──────────────────────────────────────────────────────────────

    /** A whole frame: motion, cascade, layout, paint-free, and the pointer diffed against it. */
    protected final void frame() {
        frame(0.016f);
    }

    /**
     * A frame of a stated length -- for anything on a CLOCK: a caret blink, a tooltip delay, a
     * transition, a repeat.
     *
     * <p>Load-bearing for the port. The old engine drove these through {@code tickAnimations(0.6f)},
     * and collapsing that to a bare {@code frame()} advances 16ms instead of 600 -- so the thing
     * under test never fires and the failure reads as the FEATURE being broken rather than the clock
     * never having been advanced.</p>
     */
    protected final void frame(float deltaSeconds) {
        document.frame(deltaSeconds, viewportW, viewportH);
    }

    /**
     * <b>The surface this fixture presents, which a compositor test has to be able to state.</b>
     *
     * <p>{@link Desktop} is the document's, not any node's -- {@code Desktop.of(document)} -- so it
     * fills the VIEWPORT and not whatever wrapper a fixture happened to build. A test that wraps its
     * content in a 400x300 node and then asserts the desktop is 400 wide is asserting against the
     * wrong box, and gets the viewport's 800 instead.</p>
     */
    protected final void viewport(float width, float height) {
        viewportW = width;
        viewportH = height;
    }

    private float viewportW = W;
    private float viewportH = H;

    /** Layout only — no motion, no input. For a geometry assertion that should not need a frame. */
    protected final void layoutOnly() {
        document.layout(viewportW, viewportH);
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

    /**
     * Presses {@code keyCode} with {@code modifiers} held, as a real chord arrives.
     *
     * <p>The modifier state is the PLATFORM's — {@code Input} reads {@code getCurrentModifiers} rather
     * than taking it from the event — so a test that does not hold one is sending an unmodified key
     * whatever it thinks it is sending. Without this every chord in every test was silently unmodified,
     * which is why nothing ever noticed that no keymap was installed.</p>
     */
    protected final boolean chord(int keyCode, int modifiers) {
        TestPlatformService.install();
        TestPlatformService.holdModifiers(modifiers);
        return key(keyCode, true);
    }

    /**
     * Lets go of whatever {@link #chord} was holding.
     *
     * <p><b>Not folded into {@code chord}</b>, and the difference is the whole of a switcher: a
     * held-modifier gesture POLLS the modifier rather than listening for a key-up, so releasing it in
     * the same breath as the press opens the switcher and commits it before a frame is drawn. A test
     * that wants to see the gesture on screen has to keep holding, exactly as a user does.</p>
     */
    protected final void releaseModifiers() {
        TestPlatformService.holdModifiers(0);
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

    /**
     * A node's centre in SURFACE pixels -- what {@code press}/{@code move}/{@code click} take.
     *
     * <p>Here rather than in each test because the two ways of getting it wrong are both silent and
     * both arrive together in a ported file. {@code Box.x()} is PARENT-RELATIVE on this engine where
     * the old runtime cache was absolute, so a thumb inside its own scrollbar reads 0 and the press
     * lands near the origin; and the old {@code UIWindow} defaulted to {@code uiScale} 2, so ported
     * arithmetic multiplies by a 2 this fixture does not have. {@code worldX()} already carries the
     * root transform, so only the HALF-EXTENT is scaled -- scaling the origin too doubles it again.</p>
     */
    protected final int[] centreOf(UIElement node) {
        Box box = boxOf(node);
        float scale = document.boxes().uiScale();
        return new int[]{
                Math.round(box.worldX() + box.width() / 2f * scale),
                Math.round(box.worldY() + box.height() / 2f * scale)
        };
    }

    /** The document's scale, so a test can express a distance in surface pixels without pinning it. */
    protected final float uiScale() {
        return document.boxes().uiScale();
    }

    /**
     * <b>What a listener OUTSIDE the widget would see under a point</b> — the raw hit, retargeted.
     *
     * <p>{@link #hit} answers the box that is actually under the pointer, which inside a composite is
     * routinely a {@code <slot>} or a part: real nodes, with real boxes, and invisible to anyone
     * outside the tree that owns them. Dispatch retargets before it calls a listener, so "who takes
     * this press" is a question about the retargeted node — asking the raw one makes every composite
     * answer with its own plumbing.</p>
     */
    protected final UIElement hitTarget(float x, float y) {
        UIElement raw = hit(x, y);
        return raw == null ? null : UIElement.retarget(raw, document);
    }

    /** What is under a point, or null over nothing. The hit test needs no paint to have happened. */
    protected final UIElement hit(float x, float y) {
        Box box = document.boxes().hitTest(x, y);
        return box == null ? null : box.node();
    }

    /** A node's settled box. Fails loudly rather than answering a zero box nobody asked for. */
    protected final Box boxOf(UIElement node) {
        Box box = document.boxes().boxOf(node);
        if (box == null) {
            throw new AssertionError("no box for " + node + " -- hidden, frozen, or laid out yet?");
        }
        return box;
    }

    /**
     * A widget's PART by name, searched through its shadow tree.
     *
     * <p>The old engine gave every internal child a `__double-underscore__` class, so a test found
     * one with an ordinary selector. A part is not a class and it is not in the light tree, so no
     * outer selector reaches it -- `querySelector(".thumb")` answers nothing, which reads as the
     * widget not having been built. Fails loudly rather than returning null, because every caller
     * is about to dereference it.</p>
     */
    protected static UIElement part(UIElement host, String partName) {
        ShadowRoot root = host.shadowRoot();
        if (root == null) {
            throw new AssertionError(host + " has no shadow tree, so it has no parts");
        }
        UIElement found = findPart(root, partName);
        if (found == null) {
            throw new AssertionError("no part named " + partName + " in " + host);
        }
        return found;
    }

    private static UIElement findPart(UIElement at, String partName) {
        for (UIElement child : at.children()) {
            if (partName.equals(child.get(Attribute.PART))) return child;
            UIElement deeper = findPart(child, partName);
            if (deeper != null) return deeper;
        }
        return null;
    }

    /** Every node under {@code scope} carrying {@code className}, in document order. */
    protected static List<UIElement> allWithClass(UIElement scope, String className) {
        return scope.getElementsByClassName(className);
    }

    /**
     * The first node under {@code scope} matching {@code selector}, <b>crossing shadow boundaries</b>.
     *
     * <p>This is a TEST helper and deliberately not an engine one. On the web -- and here --
     * {@code querySelector} stops at a shadow root, because that is what encapsulation MEANS: a rule
     * or a query from outside cannot reach in. A test is the one caller with a legitimate reason to
     * look anyway, since it is asserting about structure the widget owns.</p>
     *
     * <p>It is what most ported tests need. They were written when every internal child was an
     * ordinary light child carrying a {@code __class__}, so {@code querySelector("." + X)} found it;
     * now the same node is a shadow PART and the light-tree query answers nothing -- which reads as
     * the widget not having been built rather than as the query not reaching it.</p>
     *
     * <p>{@code selector} takes the shapes those tests already use: {@code .class}, a bare class
     * name, a {@code tag}, or a part name.</p>
     */
    protected static UIElement deep(UIElement scope, String selector) {
        List<UIElement> found = deepAll(scope, selector);
        if (found.isEmpty()) {
            throw new AssertionError("nothing matching \"" + selector + "\" under " + scope
                    + " -- if it is a shadow part, that is expected of querySelector and not of this");
        }
        return found.get(0);
    }

    /** As {@link #deep}, but {@code null} for no match -- what {@code querySelector} answers. */
    protected static UIElement deepOrNull(UIElement scope, String selector) {
        List<UIElement> found = deepAll(scope, selector);
        return found.isEmpty() ? null : found.get(0);
    }

    /** As {@link #deep}, every match, in composed order; empty rather than failing. */
    protected static List<UIElement> deepAll(UIElement scope, String selector) {
        // THE REAL SELECTOR ENGINE FIRST, run once per tree. `querySelectorAll` stops at a shadow
        // boundary by design -- that is the encapsulation the engine exists to provide -- so a deep
        // query is that same query repeated inside every shadow root beneath the scope. Written as a
        // single-token match at first, which silently answered NOTHING for any selector with a
        // combinator in it (`.nested .item` matched no node, because no node has a class of that
        // name) and read as the tree not containing what it plainly contained.
        // A BARE `__x__` IS A CLASS, not a type. The ported tests inherited both spellings from the
        // old engine's token matcher, which accepted either; the real selector engine parses an
        // undotted token as a TYPE and rejects one starting with underscores outright -- twelve
        // status-bar tests failed with `Unparseable selector fragment` rather than finding nothing.
        String query = selector.startsWith("__") ? "." + selector : selector;
        LinkedHashSet<UIElement> out = new LinkedHashSet<>();
        try {
            out.addAll(scope.querySelectorAll(query));
            for (UIElement node : scope.composedSubtree()) {
                ShadowRoot shadow = node.shadowRoot();
                if (shadow != null) out.addAll(shadow.querySelectorAll(query));
            }
        } catch (RuntimeException unparseable) {
            // Anything the engine will not parse falls through to the token match below, which is
            // what every one of these queries used to be. Degrading beats throwing out of a helper.
        }
        // ...and then PART NAMES, which no selector can spell from outside the tree that owns them:
        // `::part(x)` is a rule's vocabulary, not a query's. Only for a bare single token, so a real
        // selector is never second-guessed.
        if (selector.indexOf(' ') < 0 && selector.indexOf('>') < 0) {
            String want = selector.startsWith(".") ? selector.substring(1) : selector;
            // ...AND THE SAME NAME WITHOUT ITS WRAPPER. The old engine's parts were internal children
            // wearing a `__double-underscore__` class, and the port turned each into a part named by
            // the bare word -- `__mark__` became `mark`. Every ported test still asks for the old
            // spelling, and a query that answers nothing for it reads as the part having been dropped
            // rather than renamed.
            for (UIElement node : scope.composedSubtree()) {
                if (node != scope && want.equals(node.get(Attribute.PART))) out.add(node);
            }
            // The bare name is a LAST RESORT, never a widening. Part names are short and shared --
            // `label`, `mark`, `items`, `content` belong to a dozen widgets each -- so matching
            // `__label__` against every part called `label` turns one status item into every label in
            // the bar. Only when nothing else answered at all, which is the case it was added for.
            if (out.isEmpty() && want.startsWith("__") && want.endsWith("__") && want.length() > 4) {
                String bare = want.substring(2, want.length() - 2);
                for (UIElement node : scope.composedSubtree()) {
                    if (node != scope && bare.equals(node.get(Attribute.PART))) out.add(node);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Every node in the COMPOSED subtree, shadow trees included — what paint and hit-testing walk. */
    protected static List<UIElement> composed(UIElement scope) {
        List<UIElement> out = new ArrayList<>();
        for (UIElement node : scope.composedSubtree()) out.add(node);
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
    protected static <T extends UIEvent> void onTarget(UIElement node, Class<T> type,
                                                       UIEvent.Listener<UIElement, T> listener) {
        node.events.getGroup(type).attachListener(listener, false, false);
    }

    /** A bubble-phase listener — what an ancestor watching its subtree wants. */
    protected static <T extends UIEvent> void onBubble(UIElement node, Class<T> type,
                                                       UIEvent.Listener<UIElement, T> listener) {
        node.events.getGroup(type).attachListener(listener, false, true);
    }
}
