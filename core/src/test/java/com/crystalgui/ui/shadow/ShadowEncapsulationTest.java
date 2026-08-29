package com.crystalgui.ui.shadow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import org.junit.Test;

/**
 * <b>Spike S2</b> — the three measured answers. {@code plan_ui_rewrite.md} M0.
 *
 * <p>The engine audit's §4 finding is that this engine encapsulates a composite's parts with a
 * <em>boolean</em> ({@code markAsInternal}) which the cascade cannot see, so every widget's insides are
 * globally reachable by class name. S2 asks what it costs to replace that with a real scope. Three
 * questions, one test each, plus a control that shows today's engine failing the same check:</p>
 *
 * <ol>
 *   <li>can {@code ::part(name)} be expressed and matched here — {@link #aPartRuleStylesTheExposedPart()};</li>
 *   <li>does the scope actually hold outer rules out — {@link #anOuterRuleCannotReachIntoAShadowTree()},
 *       against {@link #theSameRuleReachesStraightIntoTodaysButton()};</li>
 *   <li>does focus retarget to the host — {@link #focusRetargetsToTheOutermostHost()}.</li>
 * </ol>
 *
 * <p><b>The control is the important half.</b> Each encapsulation assertion has a twin that runs the
 * identical stylesheet against a stock {@link Button} and asserts the rule <em>does</em> reach in.
 * Without it, an assertion that "the label is not red" would pass against a prototype where the rule
 * never matched anything for some unrelated reason — which is the shape of every vacuous test.</p>
 */
public class ShadowEncapsulationTest extends UiTestBase {

    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;

    private UIWindow window;

    /** Builds a window holding both buttons side by side and applies {@code css} to it. */
    private UIElement build(String css, UIElement... content) {
        ShadowRoot.invalidateCaches();
        UIElement root = new UIElement();
        root.layout(l -> l.width(400).height(200));
        root.addChildren(content);

        window = new UIWindow(Ui.of(root));
        window.init(800, 800);
        window.getStyleEngine().addStylesheet(StyleSheet.parse(css));
        settle();
        return root;
    }

    private void settle() {
        window.updateWithoutPainting();
    }

    private static Integer colourOf(UIElement element) {
        return element.getStyle().getComputed(StylePropertyRegistry.COLOR);
    }

    private static Float opacityOf(UIElement element) {
        return element.getStyle().getComputed(StylePropertyRegistry.OPACITY);
    }

    // ---------------------------------------------------------------- 1. ::part reaches in

    @Test
    public void aPartRuleStylesTheExposedPart() {
        ShadowButton button = new ShadowButton("Ok");
        build("shadowbutton::part(label) { color: #FF0000; }", button);

        assertEquals("::part(label) must reach the part it names",
                Integer.valueOf(RED), colourOf(button.label()));
    }

    @Test
    public void aPartRuleDoesNotReachAPartItDoesNotName() {
        ShadowButton button = new ShadowButton("Ok");
        button.preIcon();
        build("shadowbutton::part(label) { color: #FF0000; }", button);

        assertEquals("a part rule names ONE part; the icon slot is not it",
                null, colourOf(button.preIcon()));
    }

    @Test
    public void aPartRuleRespectsTheCompoundDescribingTheHost() {
        // The originating compound still has to match the host. `.primary::part(label)` must not
        // colour the label of a button that is not `.primary` -- otherwise ::part would be a global
        // name after all, which is the thing being replaced.
        ShadowButton primary = new ShadowButton("Ok");
        primary.addClass("primary");
        ShadowButton plain = new ShadowButton("Cancel");
        build(".primary::part(label) { color: #FF0000; }", primary, plain);

        assertEquals(Integer.valueOf(RED), colourOf(primary.label()));
        assertNull("the host compound must still be matched", colourOf(plain.label()));
    }

    // ---------------------------------------------------------------- 2. the scope holds

    /**
     * Asserted on a <b>non-inherited</b> property, and that is not incidental — see
     * {@link #inheritedPropertiesStillCrossTheBoundary()}. Encapsulation is about which rules
     * <em>match</em> an element, not about which values reach it.
     */
    @Test
    public void anOuterRuleCannotReachIntoAShadowTree() {
        ShadowButton button = new ShadowButton("Ok");
        // Three shapes, each of which reaches every widget in the engine today: the universal
        // selector, a descendant type rule, and a bare type rule.
        build("* { opacity: 0.5; } shadowbutton text { opacity: 0.5; } text { opacity: 0.5; }", button);

        assertNull("no outer rule may MATCH a shadow descendant -- that is the whole proposition",
                opacityOf(button.label()));
        assertEquals("...while the host, which is in the outer scope, is matched normally",
                Float.valueOf(0.5f), opacityOf(button));
    }

    /** The control: today's engine, same rule, and it walks straight in. */
    @Test
    public void theSameRuleReachesStraightIntoTodaysButton() {
        Button button = new Button("Ok");
        build("button text { opacity: 0.5; }", button);

        UIElement label = button.querySelector("text");
        assertNotNull("the stock Button's label is an ordinary descendant", label);
        assertEquals("today an outer rule reaches any widget's insides -- this is the defect",
                Float.valueOf(0.5f), opacityOf(label));
    }

    /**
     * <b>A finding, not a leak.</b> {@code color} is inheritable, so a rule matching the <em>host</em>
     * reaches the parts through inheritance even though no rule matches them. That is exactly what the
     * DOM does — inherited properties cross a shadow boundary, which is what lets a page set a font and
     * have every component follow it — and it is the reason the assertion above uses {@code opacity}.
     *
     * <p>Worth pinning because it decides how {@code ua/} rules port at M6: anything a widget wants to
     * inherit needs no part at all, and only what must be addressed <em>independently</em> of the host
     * does.</p>
     */
    @Test
    public void inheritedPropertiesStillCrossTheBoundary() {
        ShadowButton button = new ShadowButton("Ok");
        build("shadowbutton { color: #FF0000; }", button);

        assertEquals("an inheritable property reaches the parts through the host, as on the web",
                Integer.valueOf(RED), colourOf(button.label()));
    }

    @Test
    public void theHostItselfIsStillOrdinarilyStyleable() {
        // Encapsulation is about the tree BELOW the root. The host is a normal element in the outer
        // scope and must stay so, or a shadow widget could not be themed at all.
        ShadowButton button = new ShadowButton("Ok");
        build("shadowbutton { color: #0000FF; }", button);

        assertEquals(Integer.valueOf(BLUE), colourOf(button));
    }

    // ---------------------------------------------------------------- 3. focus retargets

    @Test
    public void focusRetargetsToTheOutermostHost() {
        ShadowButton button = new ShadowButton("Ok");
        build("", button);

        assertSame("a focused shadow descendant reports its host to the outside",
                button, ShadowRoot.retarget(button.label()));
        assertSame("an element in the light tree retargets to itself", button, ShadowRoot.retarget(button));
        assertNull(ShadowRoot.retarget(null));
    }

    @Test
    public void retargetingComposesThroughNesting() {
        // A shadow tree containing another host. The outside must see the outermost one, or a
        // DataContext walk started from focus would answer about a widget's internals.
        ShadowButton outer = new ShadowButton("Outer");
        ShadowButton inner = new ShadowButton("Inner");
        outer.shadowRoot().addChild(inner);
        build("", outer);

        assertSame("nesting must compose outward, exactly as the DOM does",
                outer, ShadowRoot.retarget(inner.label()));
    }

    // ---------------------------------------------------------------- what it costs

    @Test
    public void theShadowPathIsInertForOrdinaryElements() {
        // The cost claim in ShadowRoot's javadoc: an element outside any shadow tree pays one map
        // lookup. Asserted as behaviour rather than as a timing, which a test cannot hold: an
        // ordinary tree must cascade exactly as it did before any of this existed.
        Button plain = new Button("Ok");
        build("button { color: #0000FF; } button text { opacity: 0.5; }", plain);

        assertEquals(Integer.valueOf(BLUE), colourOf(plain));
        assertEquals("a light-tree descendant is matched exactly as before",
                Float.valueOf(0.5f), opacityOf(plain.querySelector("text")));
        assertTrue("nothing in the light tree has a host", !ShadowRoot.isInShadowTree(plain));
    }
}
