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
public class NodeStylePassTest extends UiTestBase {

    private static Document document(String css) {
        Document document = new Document();
        if (css != null) document.styles().addStylesheet(StyleSheet.parse(css));
        return document;
    }

    private static Node node(String id, String... classes) {
        Node node = new Node().setId(id);
        for (String c : classes) node.addClass(c);
        return node;
    }

    private static float opacity(Node node) {
        return node.computedStyle().get(StylePropertyRegistry.OPACITY);
    }

    // ── The cascade, reached through the seam ────────────────────────────────

    @Test
    public void aRuleReachesANodeByTypeIdAndClass() {
        Document document = document("element { opacity: 0.5 } #by-id { opacity: 0.25 } .by-class { opacity: 0.75 }");
        Node plain = node("");
        Node byId = node("by-id");
        Node byClass = node("", "by-class");
        document.append(plain).append(byId).append(byClass);
        document.calculateStyle(0f);

        assertEquals("a bare type selector matches the default-namespace kind", 0.5f, opacity(plain), 0.001f);
        assertEquals(0.25f, opacity(byId), 0.001f);
        assertEquals(0.75f, opacity(byClass), 0.001f);
    }

    @Test
    public void originSpecificityAndOrderAreTheCascade() {
        Document document = document(".a { opacity: 0.2 } .a { opacity: 0.3 } #x { opacity: 0.4 }");
        Node node = node("x", "a");
        document.append(node);
        document.calculateStyle(0f);
        assertEquals("an id beats a class", 0.4f, opacity(node), 0.001f);

        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(), g -> g.opacity(0.9f));
        assertEquals("inline beats the sheet", 0.9f, opacity(node), 0.001f);

        Node later = node("", "a");
        document.append(later);
        document.calculateStyle(0f);
        assertEquals("of two equal rules the later wins", 0.3f, opacity(later), 0.001f);
    }

    @Test
    public void aChangedClassIsRematchedOnTheNextPass() {
        Document document = document(".lit { opacity: 0.25 }");
        Node node = node("");
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
        Document document = document("element { color: #FF0000; opacity: 0.5 }");
        Node host = node("host");
        Node part = node("part");
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
        Document document = document(null);
        Node host = node("host");
        ShadowRoot shadow = host.attachShadow();
        Node part = node("part", "inner");
        shadow.append(part);
        Node stranger = node("stranger", "inner");
        document.append(host).append(stranger);
        document.styles().addStylesheet(StyleSheet.parse(".inner { opacity: 0.25 }"), shadow);
        document.calculateStyle(0f);

        assertEquals("the composite's own sheet styles its parts", 0.25f, opacity(part), 0.001f);
        assertEquals("and nothing outside them", 1f, opacity(stranger), 0.001f);
    }

    @Test
    public void aPartIsReachedThroughItsHostByName() {
        Document document = document("element::part(label) { opacity: 0.25 } .label { opacity: 0.5 }");
        Node host = node("host");
        Node label = node("", "label").set(Attribute.PART, "label");
        Node other = node("", "label");
        host.attachShadow().append(label).append(other);
        document.append(host);
        document.calculateStyle(0f);

        assertEquals("::part(label) reaches the exposed part", 0.25f, opacity(label), 0.001f);
        assertEquals("a class rule from outside reaches neither", 1f, opacity(other), 0.001f);
    }

    // ── Scopes ───────────────────────────────────────────────────────────────

    @Test
    public void scopeProximityRanksBetweenSpecificityAndOrder() {
        Document document = document(null);
        Node outer = node("outer");
        Node inner = node("inner");
        Node target = node("t", "x");
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
        Document document = document(null);
        Node scope = node("scope");
        Node inside = node("", "x");
        Node outside = node("", "x");
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
        Document document = document(":root { font-size: 20 } :root.large { font-size: 30 } .wide { width: 2em }");
        Node child = node("child");
        Node wide = node("wide", "wide");
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

        Document document = new Document();
        document.styles().addStylesheet(sheet);
        Node a = node("", "a");
        Node c = node("", "c");
        document.append(a).append(c);
        document.calculateStyle(0f);
        assertEquals(0.25f, opacity(a), 0.001f);
        assertEquals(0.75f, opacity(c), 0.001f);
    }

    // ── ComputedStyle ────────────────────────────────────────────────────────

    @Test
    public void computedStyleAnswersEveryPropertyAndDoesNotMove() {
        Document document = document(".dim { opacity: 0.5 }");
        Node node = node("");
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
        Document document = document(".red { color: #FF0000 }");
        Node parent = node("p");
        Node child = node("c");
        parent.append(child);
        document.append(parent);
        document.calculateStyle(0f);
        ComputedStyle before = child.computedStyle();

        parent.addClass("red");
        document.calculateStyle(0f);
        assertNotSame(before, child.computedStyle());
        assertEquals((Integer) 0xFFFF0000, child.computedStyle().get(StylePropertyRegistry.COLOR));
    }
}
