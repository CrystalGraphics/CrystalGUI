package com.crystalgui.ui.dom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import dev.vfyjxf.taffy.style.TaffyDimension;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Test;

/**
 * The cascade over the node tree — plan_m5.md 5.2's acceptance.
 *
 * <p>The same {@code StyleEngine}, {@code ElementStyle}, selectors and sheets the old engine runs
 * (D5.2: shared, not forked), reached through the {@code Styleable} seam. What is asserted here is
 * what the old engine could not do: a sheet scoped to a subtree with proximity in the cascade, a
 * shadow tree that outer rules cannot enter while inherited values still do, a font size that
 * inherits from {@code :root}, a bad selector that costs one rule, and a computed style that never
 * answers null and never moves under a reader.</p>
 */
public class UINodeStylePassTest extends UiTestBase {

    private static UIDocument document(String css) {
        UIDocument document = new UIDocument();
        if (css != null) document.styles().addStylesheet(StyleSheet.parse(css));
        return document;
    }

    private static UINode node(String id, String... classes) {
        UINode node = new UINode().setId(id);
        for (String c : classes) node.addClass(c);
        return node;
    }

    private static float opacity(UINode node) {
        return node.computedStyle().get(StylePropertyRegistry.OPACITY);
    }

    // ── The cascade, reached through the seam ────────────────────────────────

    @Test
    public void aRuleReachesANodeByTypeIdAndClass() {
        UIDocument document = document("element { opacity: 0.5 } #by-id { opacity: 0.25 } .by-class { opacity: 0.75 }");
        UINode plain = node("");
        UINode byId = node("by-id");
        UINode byClass = node("", "by-class");
        document.append(plain).append(byId).append(byClass);
        document.calculateStyle(0f);

        assertEquals("a bare type selector matches the default-namespace kind", 0.5f, opacity(plain), 0.001f);
        assertEquals(0.25f, opacity(byId), 0.001f);
        assertEquals(0.75f, opacity(byClass), 0.001f);
    }

    @Test
    public void originSpecificityAndOrderAreTheCascade() {
        UIDocument document = document(".a { opacity: 0.2 } .a { opacity: 0.3 } #x { opacity: 0.4 }");
        UINode node = node("x", "a");
        document.append(node);
        document.calculateStyle(0f);
        assertEquals("an id beats a class", 0.4f, opacity(node), 0.001f);

        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(), g -> g.opacity(0.9f));
        assertEquals("inline beats the sheet", 0.9f, opacity(node), 0.001f);

        UINode later = node("", "a");
        document.append(later);
        document.calculateStyle(0f);
        assertEquals("of two equal rules the later wins", 0.3f, opacity(later), 0.001f);
    }

    @Test
    public void aChangedClassIsRematchedOnTheNextPass() {
        UIDocument document = document(".lit { opacity: 0.25 }");
        UINode node = node("");
        document.append(node);
        document.calculateStyle(0f);
        assertEquals(1f, opacity(node), 0.001f);

        node.addClass("lit");
        assertEquals("nothing moves until the pass runs", 1f, opacity(node), 0.001f);
        document.calculateStyle(0f);
        assertEquals(0.25f, opacity(node), 0.001f);

        node.removeClass("lit");
        document.calculateStyle(0f);
        assertEquals("and a withdrawn rule is withdrawn", 1f, opacity(node), 0.001f);
    }

    // ── Shadow trees ─────────────────────────────────────────────────────────

    @Test
    public void anInheritedValueReachesIntoAShadowTreeAndARuleDoesNot() {
        UIDocument document = document("element { color: #FF0000; opacity: 0.5 }");
        UINode host = node("host");
        UINode part = node("part");
        host.attachShadow().append(part);
        document.append(host);
        document.calculateStyle(0f);

        assertEquals("the rule reaches the host", 0.5f, opacity(host), 0.001f);
        assertEquals("but not the part -- a shadow tree holds outer rules out", 1f, opacity(part), 0.001f);
        assertEquals("while an inherited property crosses the boundary, as on the web",
                (Integer) 0xFFFF0000, part.computedStyle().get(StylePropertyRegistry.COLOR));
    }

    @Test
    public void aSheetScopedToAShadowRootReachesItsPartsAndNothingOutside() {
        UIDocument document = document(null);
        UINode host = node("host");
        ShadowRoot shadow = host.attachShadow();
        UINode part = node("part", "inner");
        shadow.append(part);
        UINode stranger = node("stranger", "inner");
        document.append(host).append(stranger);
        document.styles().addStylesheet(StyleSheet.parse(".inner { opacity: 0.25 }"), shadow);
        document.calculateStyle(0f);

        assertEquals("the composite's own sheet styles its parts", 0.25f, opacity(part), 0.001f);
        assertEquals("and nothing outside them", 1f, opacity(stranger), 0.001f);
    }

    @Test
    public void aPartIsReachedThroughItsHostByName() {
        UIDocument document = document("element::part(label) { opacity: 0.25 } .label { opacity: 0.5 }");
        UINode host = node("host");
        UINode label = node("", "label").set(Attribute.PART, "label");
        UINode other = node("", "label");
        host.attachShadow().append(label).append(other);
        document.append(host);
        document.calculateStyle(0f);

        assertEquals("::part(label) reaches the exposed part", 0.25f, opacity(label), 0.001f);
        assertEquals("a class rule from outside reaches neither", 1f, opacity(other), 0.001f);
    }

    // ── Scopes ───────────────────────────────────────────────────────────────

    @Test
    public void scopeProximityRanksBetweenSpecificityAndOrder() {
        UIDocument document = document(null);
        UINode outer = node("outer");
        UINode inner = node("inner");
        UINode target = node("t", "x");
        inner.append(target);
        outer.append(inner);
        document.append(outer);
        // The closer scope is installed FIRST, so by order of appearance alone the farther one wins.
        document.styles().addStylesheet(StyleSheet.parse(".x { opacity: 0.75 }"), inner);
        document.styles().addStylesheet(StyleSheet.parse(".x { opacity: 0.25 }"), outer);
        document.calculateStyle(0f);
        assertEquals("the closer scope wins at equal specificity", 0.75f, opacity(target), 0.001f);

        document.styles().addStylesheet(StyleSheet.parse("#t.x { opacity: 0.5 }"), outer);
        document.calculateStyle(0f);
        assertEquals("but specificity still ranks above proximity", 0.5f, opacity(target), 0.001f);
    }

    @Test
    public void aScopedSheetStopsAtItsRoot() {
        UIDocument document = document(null);
        UINode scope = node("scope");
        UINode inside = node("", "x");
        UINode outside = node("", "x");
        scope.append(inside);
        document.append(scope).append(outside);
        document.styles().addStylesheet(StyleSheet.parse(".x { opacity: 0.25 }"), scope);
        document.calculateStyle(0f);

        assertEquals(0.25f, opacity(inside), 0.001f);
        assertEquals("a sibling of the scope root is not in scope", 1f, opacity(outside), 0.001f);
        assertEquals("the root itself is", 1f, opacity(scope), 0.001f);   // no .x on it; just not an error
    }

    // ── :root and em ─────────────────────────────────────────────────────────

    @Test
    public void fontSizeInheritsFromRootAndEmResolvesAgainstIt() {
        UIDocument document = document(":root { font-size: 20 } :root.large { font-size: 30 } .wide { width: 2em }");
        UINode child = node("child");
        UINode wide = node("wide", "wide");
        document.append(child).append(wide);
        document.calculateStyle(0f);

        assertEquals("nothing set font-size on the child, so it inherits from :root -- no universal rule in the way",
                20f, child.getStyle().getGeneralGroup().fontSize(), 0.001f);
        assertEquals("an em on a dimension resolves against the inherited size",
                TaffyDimension.length(40f), wide.computedStyle().get(LayoutProperties.WIDTH));

        document.addClass("large");
        document.calculateStyle(0f);
        assertEquals("a font-size change above re-resolves the em below, within the same pass",
                TaffyDimension.length(60f), wide.computedStyle().get(LayoutProperties.WIDTH));
    }

    // ── A bad selector ───────────────────────────────────────────────────────

    @Test
    public void aBadSelectorDropsItsRuleAndNothingElse() {
        StyleSheet sheet = StyleSheet.parse(".a { opacity: 0.25 } .b:no-such-state { opacity: 0.5 } .c { opacity: 0.75 }");
        assertEquals("one rule dropped, two kept", 2, sheet.getRules().size());

        UIDocument document = new UIDocument();
        document.styles().addStylesheet(sheet);
        UINode a = node("", "a");
        UINode c = node("", "c");
        document.append(a).append(c);
        document.calculateStyle(0f);
        assertEquals(0.25f, opacity(a), 0.001f);
        assertEquals(0.75f, opacity(c), 0.001f);
    }

    // ── ComputedStyle ────────────────────────────────────────────────────────

    @Test
    public void computedStyleAnswersEveryPropertyAndDoesNotMove() {
        UIDocument document = document(".dim { opacity: 0.5 }");
        UINode node = node("");
        document.append(node);
        document.calculateStyle(0f);

        ComputedStyle before = node.computedStyle();
        assertEquals("a property nothing wrote answers its initial, never null", 1f, before.get(StylePropertyRegistry.OPACITY), 0.001f);
        assertFalse(before.isSet(StylePropertyRegistry.OPACITY));
        assertSame("cached until something changes", before, node.computedStyle());

        node.addClass("dim");
        document.calculateStyle(0f);
        ComputedStyle after = node.computedStyle();
        assertNotSame("a change is a new value", before, after);
        assertEquals(0.5f, after.get(StylePropertyRegistry.OPACITY), 0.001f);
        assertTrue(after.isSet(StylePropertyRegistry.OPACITY));
        assertEquals("and the old one did not move under whoever held it", 1f, before.get(StylePropertyRegistry.OPACITY), 0.001f);
    }

    @Test
    public void aParentsChangeIsSeenBelowWithoutAnythingWalkingDown() {
        UIDocument document = document(".red { color: #FF0000 }");
        UINode parent = node("p");
        UINode child = node("c");
        parent.append(child);
        document.append(parent);
        document.calculateStyle(0f);
        ComputedStyle before = child.computedStyle();

        parent.addClass("red");
        document.calculateStyle(0f);
        assertNotSame(before, child.computedStyle());
        assertEquals((Integer) 0xFFFF0000, child.computedStyle().get(StylePropertyRegistry.COLOR));
    }

    /**
     * <b>A host's change re-matches its own shadow parts</b>, because a {@code ::part} rule's every
     * selectable input belongs to the host.
     *
     * <p>Nothing about the part itself moved, so nothing marked it dirty and it kept the styles it
     * matched under the host's previous state. It presents as a widget being unstyled in exactly one
     * state — the failure the old engine records once each for {@code :checked}, {@code :disabled}
     * and {@code :hover}, repaired each time by having the widget flip a class of its own. Asserted
     * here rather than per widget because it is the engine's business: every ported widget with a
     * state-dependent part rule depends on it, and none of them should have to know.</p>
     *
     * <p>Driven through a CLASS rather than a pseudo-class, so it holds for any reason a host
     * re-matches and not just for state.</p>
     */
    @Test
    public void aHostsOwnChangeRematchesItsShadowParts() {
        UIDocument document = document("#h.on::part(inner) { opacity: 0.25 }");
        UINode host = node("h");
        UINode part = node("");
        part.set(Attribute.PART, "inner");
        host.attachShadow().append(part);
        document.append(host);
        document.calculateStyle(0f);
        assertEquals("nothing matches it yet", 1f, opacity(part), 0.001f);

        host.addClass("on");
        document.calculateStyle(0f);

        assertEquals("the host's class decided what matches the PART", 0.25f, opacity(part), 0.001f);
    }

    /**
     * And an <b>unexposed</b> node in a shadow tree is not marked — it cannot be reached from outside,
     * so nothing about the host can have changed what matches it.
     *
     * <p>The counter-assertion to the one above: an invalidation written as "mark the whole shadow
     * subtree" satisfies that test and does needless work on every hover of every composite. This one
     * cannot see the work directly, so it asserts the observable consequence — a rule that would only
     * match if the engine treated an unexposed node as a part.</p>
     */
    @Test
    public void anUnexposedShadowNodeIsNotReachableFromOutside() {
        UIDocument document = document("#h.on::part(inner) { opacity: 0.25 }");
        UINode host = node("h");
        UINode hidden = node("");
        host.attachShadow().append(hidden);
        document.append(host);
        document.calculateStyle(0f);

        host.addClass("on");
        document.calculateStyle(0f);

        assertEquals("no part name, no rule", 1f, opacity(hidden), 0.001f);
    }
}
