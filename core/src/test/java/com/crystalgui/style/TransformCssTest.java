package com.crystalgui.style;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.UIWindow;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@code transform} and {@code transform-origin} through the real cascade.
 *
 * <p>Here rather than in {@code headlessTest} because {@link StyleSheet} cannot be class-loaded without
 * CrystalGraphics — its {@code DEFAULT} field reads {@code default.css} through {@code CgIO} at
 * class-init, so even {@code parse()} needs it. Value-level and codec coverage lives in
 * {@code TransformStylePropertiesTest} in the headless set, and the parser has its own unit test.</p>
 */
public class TransformCssTest {

    /** {@code UIWindow}'s constructor builds a {@code UIInputHandler}, which asks the adapter how many
     * mouse buttons exist — a window cannot be constructed without one. */
    @Before
    public void registerStubAdapter() {
        CrystalGuiCore.setAdapter(new CgUiInputAdapter() {
            @Override public int getCurrentModifiers() { return 0; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
        });
    }

    private UIElement child;

    /**
     * Applies {@code css} to a root holding one plain child and returns the child's style.
     *
     * <p>{@code init()} is required, not incidental: it attaches the tree to the window, and
     * {@code invalidateStyleMatch()} early-returns on a detached element — without it nothing is marked
     * dirty and {@code calculateStyle} silently matches nothing.</p>
     */
    private GeneralGroup styled(String css) {
        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        child = new UIElement().layout(l -> l.width(100).height(100));
        child.getClasses().add("target");
        root.addChild(child);
        UIWindow window = new UIWindow(Ui.of(root));
        window.setUiScale(1f);
        window.getStyleEngine().addStylesheet(StyleSheet.parse(css));
        window.init(400, 400);
        window.getStyleEngine().calculateStyle(0f);
        return child.getStyle().getGeneralGroup();
    }

    @Test
    public void aTransformParsesFromCss() {
        UITransform t = styled(".target { transform: translate(10px, 5px) scale(2); }").transform();
        assertEquals(2, t.ops().size());
        assertEquals(UITransform.Kind.TRANSLATE, t.ops().get(0).kind());
        assertEquals(UITransform.Kind.SCALE, t.ops().get(1).kind());
    }

    /**
     * The point of the whole value type, asserted end to end rather than on the parsed ops: the two
     * orders produce genuinely different geometry, which hit-testing can see.
     *
     * <p>With the origin at the corner, {@code translate(10px) scale(2)} puts local 0 at screen 10;
     * scaling first doubles the translate, putting it at screen 20.</p>
     */
    @Test
    public void declarationOrderChangesTheGeometry() {
        styled(".target { transform-origin: 0 0; transform: translate(10px) scale(2); }");
        assertEquals(0f, child.screenToLocal(10f, 0f).x(), 0.001f);

        styled(".target { transform-origin: 0 0; transform: scale(2) translate(10px); }");
        assertEquals(0f, child.screenToLocal(20f, 0f).x(), 0.001f);
    }

    @Test
    public void transformOriginDefaultsToTheCentre() {
        GeneralGroup style = styled(".target { transform: scale(2); }");
        assertEquals(LengthPercent.percent(0.5f), style.transformOriginX());
        assertEquals(LengthPercent.percent(0.5f), style.transformOriginY());
    }

    @Test
    public void transformOriginExpandsToBothLonghands() {
        GeneralGroup style = styled(".target { transform-origin: 10px 25%; }");
        assertEquals(LengthPercent.px(10f), style.transformOriginX());
        assertEquals(LengthPercent.percent(0.25f), style.transformOriginY());
    }

    /** A one-value form sets X only, leaving a Y from an earlier rule alone. */
    @Test
    public void aOneValueOriginLeavesTheOtherAxisAlone() {
        GeneralGroup style = styled(".target { transform-origin-y: 0; } .target { transform-origin: 10px; }");
        assertEquals(LengthPercent.px(10f), style.transformOriginX());
        assertEquals(LengthPercent.px(0f), style.transformOriginY());
    }

    @Test
    public void originKeywordsResolveToPercentages() {
        GeneralGroup style = styled(".target { transform-origin: left top; }");
        assertEquals(LengthPercent.percent(0f), style.transformOriginX());
        assertEquals(LengthPercent.percent(0f), style.transformOriginY());

        GeneralGroup bottomRight = styled(".target { transform-origin: right bottom; }");
        assertEquals(LengthPercent.percent(1f), bottomRight.transformOriginX());
        assertEquals(LengthPercent.percent(1f), bottomRight.transformOriginY());
    }

    /** CSS allows the keyword pair in either order, so `top left` must mean the same as `left top`. */
    @Test
    public void reversedKeywordOriginIsAccepted() {
        GeneralGroup style = styled(".target { transform-origin: top right; }");
        assertEquals(LengthPercent.percent(1f), style.transformOriginX());
        assertEquals(LengthPercent.percent(0f), style.transformOriginY());
    }

    @Test
    public void centreKeywordWorksOnBothAxes() {
        GeneralGroup style = styled(".target { transform-origin: center center; }");
        assertEquals(LengthPercent.percent(0.5f), style.transformOriginX());
        assertEquals(LengthPercent.percent(0.5f), style.transformOriginY());
    }

    @Test
    public void aMoreSpecificRuleWins() {
        UITransform t = styled("* { transform: scale(2); } .target { transform: scale(3); }").transform();
        assertEquals(3f, t.ops().get(0).fx(), 0.001f);
    }

    @Test
    public void importantBeatsAPlainRule() {
        UITransform t = styled(".target { transform: scale(3) !important; } .target { transform: scale(2); }")
                .transform();
        assertEquals(3f, t.ops().get(0).fx(), 0.001f);
    }

    /** Matches CSS, and is required: inheritance here is pull-based and would not fire the change
     * listener that dirties the subtree's matrices. A transform already reaches descendants through the
     * matrix chain, so inheriting it would double-apply it anyway. */
    @Test
    public void transformIsNotInherited() {
        assertFalse(StylePropertyRegistry.TRANSFORM.isInheritable());
        assertFalse(StylePropertyRegistry.TRANSFORM_ORIGIN_X.isInheritable());
        assertFalse(StylePropertyRegistry.TRANSFORM_ORIGIN_Y.isInheritable());

        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        UIElement parent = new UIElement().layout(l -> l.width(200).height(200));
        UIElement leaf = new UIElement().layout(l -> l.width(50).height(50));
        parent.addChild(leaf);
        root.addChild(parent);
        UIWindow window = new UIWindow(Ui.of(root));
        window.init(400, 400);
        parent.setTransform(UITransform.scale(2f));
        window.getStyleEngine().calculateStyle(0f);

        assertTrue("the child's own transform stays identity", leaf.getTransform().isIdentity());
    }

    /** An unparseable value must be dropped, leaving the previous cascade winner in place. */
    @Test
    public void aMalformedTransformIsIgnored() {
        UITransform t = styled(".target { transform: scale(2); } .target { transform: wobble(3); }").transform();
        assertEquals("the bad rule contributes nothing", 2f, t.ops().get(0).fx(), 0.001f);
    }

    // ── The user-agent focus ring ────────────────────────────────────────────

    /**
     * {@code default.css}'s {@code :focus} rule. A bare pseudo-class is a complete selector here, so
     * this also pins that the selector engine keeps matching one.
     */
    @Test
    public void theUserAgentSheetRingsWhateverHasFocus() {
        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        UIElement target = new UIElement().layout(l -> l.width(100).height(100));
        root.addChild(target);
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(400, 400);

        window.getStyleEngine().calculateStyle(0f);
        assertEquals("nothing is drawn until something is focused",
                LengthPercent.ZERO, target.getStyle().getGeneralGroup().outlineWidth());

        target.setFocused(true);
        window.getStyleEngine().calculateStyle(0f);

        GeneralGroup style = target.getStyle().getGeneralGroup();
        assertEquals(LengthPercent.px(1f), style.outlineWidth());
        assertEquals("Firefox's accent", 0xFF0060DF, style.outlineColor());
        // Inward, from `* { outline-offset: -1px }` in default.css — NOT zero, and deliberately not
        // positive. An outward offset draws beyond the border box, where any ancestor with
        // `overflow: hidden` scissors it away (a focused row inside a ScrollerView would lose the
        // edge of its own ring); it also let adjacent checkboxes' rings collide. Pulling the stroke
        // inside the border box instead means it always survives the clip.
        assertEquals("must not be outward — an ancestor's scissor would clip it",
                LengthPercent.px(-1f), style.outlineOffsetTop());
    }

    /** A theme turns the ring off with `outline: none`, which outranks the UA sheet by origin alone. */
    @Test
    public void anAuthorSheetCanOptOutOfTheRing() {
        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        UIElement target = new UIElement().layout(l -> l.width(100).height(100));
        target.getClasses().add("target");
        root.addChild(target);
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(StyleSheet.parse(".target:focus { outline: none; }"));
        window.init(400, 400);

        target.setFocused(true);
        window.getStyleEngine().calculateStyle(0f);

        assertEquals(LengthPercent.px(0f), target.getStyle().getGeneralGroup().outlineWidth());
    }
}
