package com.crystalgui.ui.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.dom.UIElement;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * <b>M6.0's machinery</b> — the engine gaps the census found, each pinned by the failure it exists
 * to prevent rather than by its own signature.
 *
 * <p>Every one of these is a thing a widget port would otherwise have to invent on the spot, and a
 * thing invented at widget #12 is a thing widgets #1–11 did differently. What is asserted here is
 * the BEHAVIOUR the old engine had, because that is what the ports are being held to.</p>
 */
public class PortMachineryTest {

    private static UIDocument document(float w, float h) {
        UIDocument document = new UIDocument().markFrameThread();
        document.layout(w, h);
        return document;
    }

    private static UIElement sized(String id, float width, float height) {
        UIElement node = new UIElement().setId(id);
        node.getStyle().getLayoutGroup().width(width).height(height);
        return node;
    }

    // ── hidden ───────────────────────────────────────────────────────────────

    /**
     * A hidden node has NO BOX, which is what {@code display: none} means and what
     * {@code setDisplayed(false)} meant on the old engine.
     *
     * <p>Structural rather than a {@code [hidden]} stylesheet rule — plan_m6.md D5 assumed HTML's
     * own, and this selector engine has no attribute selectors to write it with. The old engine's
     * {@code display} at IMPORTANT origin was equally un-overridable, so nothing is lost.</p>
     */
    @Test
    public void aHiddenNodeHasNoBoxAndTakesNoSpace() {
        UIDocument document = document(200f, 200f);
        UIElement a = sized("a", 50f, 20f);
        UIElement b = sized("b", 50f, 20f);
        document.append(a).append(b);
        document.layout(200f, 200f);

        assertNotNull(document.boxes().boxOf(a));
        float bTopWhileShown = document.boxes().boxOf(b).y();

        a.setDisplayed(false);
        document.layout(200f, 200f);

        assertNull("a hidden node has no box at all", document.boxes().boxOf(a));
        assertTrue("and takes no space: its sibling moved up into it",
                document.boxes().boxOf(b).y() < bTopWhileShown);

        a.setDisplayed(true);
        document.layout(200f, 200f);
        assertNotNull("and comes back", document.boxes().boxOf(a));
    }

    /**
     * Hiding is a STRUCTURE change, not merely an attribute change.
     *
     * <p>The box tree walks the composed tree only on frames the node tree reported one — so an
     * attribute that decides whether a box exists must say so, or the hidden node keeps its box until
     * something unrelated dirties the structure. Silent, and it presents as hiding working
     * "sometimes".</p>
     */
    @Test
    public void hidingReportsAStructureChange() {
        UIDocument document = document(100f, 100f);
        UIElement a = sized("a", 10f, 10f);
        document.append(a);
        document.layout(100f, 100f);
        int syncsBefore = document.boxes().syncPasses();

        a.setDisplayed(false);
        document.layout(100f, 100f);

        assertTrue("hiding must dirty the structure, or the box outlives the hide",
                document.boxes().syncPasses() > syncsBefore);
    }

    // ── scroll-exempt ────────────────────────────────────────────────────────

    /**
     * A scroll-exempt child does not move with its host's scroll — the 5.4 gap.
     *
     * <p>Without it a scroller's own bars scroll away with the content they are for, an editor's
     * gutter slides off the top, and a find bar pinned to a viewport travels with the document. Every
     * one of those is a child of the thing that scrolls, which is why the exemption cannot be
     * expressed by parenting it elsewhere.</p>
     */
    @Test
    public void aScrollExemptChildDoesNotMoveWithItsHostsScroll() {
        UIDocument document = document(100f, 100f);
        UIElement viewport = sized("viewport", 100f, 50f);
        UIElement content = sized("content", 100f, 500f);
        UIElement bar = sized("bar", 8f, 50f);
        bar.setScrollExempt(true);
        viewport.append(content).append(bar);
        // Scrolling is driven by `overflow`, never implied by oversized content: without it
        // `setScroll` returns early and BOTH children stay put, which passes the exempt half of
        // this test for entirely the wrong reason.
        viewport.getStyle().getGeneralGroup().overflow(Overflow.SCROLL);
        document.append(viewport);
        document.layout(100f, 100f);

        float contentBefore = document.boxes().boxOf(content).worldY();
        float barBefore = document.boxes().boxOf(bar).worldY();

        document.boxes().boxOf(viewport).setScroll(0f, 30f);
        document.layout(100f, 100f);

        assertEquals("the content scrolled", contentBefore - 30f,
                document.boxes().boxOf(content).worldY(), 0.01f);
        assertEquals("the exempt child did not", barBefore,
                document.boxes().boxOf(bar).worldY(), 0.01f);
    }

    // ── scroll extents ───────────────────────────────────────────────────────

    /**
     * A virtualised view's scroll extent comes from its MODEL, not from the rows it realised.
     *
     * <p>The whole reason {@link UIElement#scrollExtent} exists: a list showing a dozen rows of ten
     * thousand has a dozen boxes, so reading the laid-out content would size its scrollbar thumb for
     * what is on screen and cap its travel at one screenful. The old engine spelled it as a
     * {@code getScrollHeight} override for exactly this.</p>
     */
    @Test
    public void aVirtualisedViewsScrollExtentComesFromTheModel() {
        UIDocument document = document(100f, 100f);
        UIElement list = new UIElement() {
            @Override
            public float scrollExtent(boolean horizontal) {
                return horizontal ? -1f : 10_000f;
            }
        };
        list.getStyle().getLayoutGroup().width(100f).height(50f);
        UIElement realised = sized("realised", 100f, 60f);
        list.append(realised);
        document.append(list);
        document.layout(100f, 100f);

        Box box = document.boxes().boxOf(list);
        assertEquals("the model's height, not the realised rows'", 10_000f, box.scrollHeight(), 0.01f);
        assertTrue("so the view can scroll the whole document", box.maxScrollTop() > 9_000f);
        assertEquals("and the width still falls back to the box", box.contentWidth(), box.scrollWidth(), 0.01f);
    }

    /**
     * A scroll extent is never NaN, and {@code Math.max} would not have made that true.
     *
     * <p>{@code Math.max(0, NaN)} is NaN, so the obvious spelling of "never negative" propagates it:
     * an extent from an unmeasured box is stored as the offset and poisons every position that
     * subtracts it — a document stacking every row at one y, with nothing thrown. The old engine's
     * {@code atLeastZero} exists for the same reason and its row records the same failure.</p>
     */
    @Test
    public void aNonFiniteExtentClampsToZeroRatherThanPropagating() {
        UIDocument document = document(100f, 100f);
        UIElement list = new UIElement() {
            @Override
            public float scrollExtent(boolean horizontal) {
                return Float.NaN;
            }
        };
        list.getStyle().getLayoutGroup().width(100f).height(50f);
        document.append(list);
        document.layout(100f, 100f);

        Box box = document.boxes().boxOf(list);
        assertEquals("a NaN extent must not become a NaN maximum", 0f, box.maxScrollTop(), 0f);
        assertEquals(0f, box.maxScrollLeft(), 0f);
    }

    /**
     * Shrinking content CLAMPS the offset; it never sends the view home.
     *
     * <p>Collapsing a folder that made a tree scrollable leaves the offset past the new end — a strip
     * of the last rows against a screenful of nothing, with the scrollbar gone. Scrolling to the top
     * would fix the picture and lose the reader's place, which is why every tree and every browser
     * clamps instead.</p>
     */
    @Test
    public void shrinkingContentClampsTheOffsetRatherThanResettingIt() {
        UIDocument document = document(100f, 100f);
        UIElement viewport = sized("viewport", 100f, 50f);
        UIElement content = sized("content", 100f, 500f);
        viewport.append(content);
        document.append(viewport);
        document.layout(100f, 100f);

        // OVERFLOW, or none of this happens: `Box.setScroll` returns early for a box that is not a
        // scroll container, so a viewport that never declares one silently stays at zero and the
        // assertion below reads as clamping having reset the offset. Scrolling is an ordinary
        // element capability driven by `overflow` -- it is not implied by content being too big.
        viewport.getStyle().getGeneralGroup().overflow(Overflow.SCROLL);
        document.layout(100f, 100f);
        document.boxes().boxOf(viewport).setScroll(0f, 400f);
        document.layout(100f, 100f);
        assertEquals(400f, viewport.scrollTop(), 0.01f);

        content.getStyle().getLayoutGroup().height(120f);
        document.layout(100f, 100f);

        assertEquals("clamped to the new end", 70f, viewport.scrollTop(), 0.01f);
        assertTrue("never sent home", viewport.scrollTop() > 0f);
    }

    // ── the top layer ────────────────────────────────────────────────────────

    /**
     * The top layer stacks by INSERTION and ignores {@code z-index} — CSS Position 4's own rule.
     *
     * <p>"The last element in the top layer is rendered on top of everything else", and {@code
     * z-index} is irrelevant between two promoted elements. That is what makes "raise this popup" one
     * idempotent re-host rather than a number every caller picks without knowing what else is open.</p>
     */
    private static List<String> topLayerOrder(UIDocument document) {
        List<String> order = new ArrayList<>();
        for (Box child : document.topLayer().children()) order.add(child.node().id());
        return order;
    }

    @Test
    public void theTopLayerStacksByInsertionAndIgnoresZIndex() {
        UIDocument document = document(100f, 100f);
        UIElement first = sized("first", 10f, 10f);
        UIElement second = sized("second", 10f, 10f);
        // A z-index that would win everywhere else.
        first.getStyle().getGeneralGroup().zIndex(999);
        document.append(first).append(second);
        document.promote(first);
        document.promote(second);
        document.layout(100f, 100f);

        assertEquals("promoted in this order, and z-index does not enter into it",
                List.of("first", "second"), topLayerOrder(document));
    }

    /** Re-promoting RAISES, which is the spec's own add algorithm and what re-showing should do. */
    @Test
    public void promotingSomethingAlreadyPromotedRaisesIt() {
        UIDocument document = document(100f, 100f);
        UIElement a = sized("a", 10f, 10f);
        UIElement b = sized("b", 10f, 10f);
        document.append(a).append(b);
        document.promote(a);
        document.promote(b);
        document.layout(100f, 100f);
        assertEquals(List.of("a", "b"), topLayerOrder(document));

        document.promote(a);
        document.layout(100f, 100f);
        assertEquals("re-promoting is remove-then-append", List.of("b", "a"), topLayerOrder(document));
    }

    /**
     * Promotion SURVIVES the box being rebuilt, which is why it is recorded on the node.
     *
     * <p>A box is destroyed and recreated whenever its subtree is hidden, frozen or restructured, so
     * a host written onto one is lost on the next sync. Written as {@code box.setHost(topLayer)}, a
     * popup hidden and reshown would come back UNPROMOTED — clipped by its scroller again, and only
     * ever for a popup that had been closed once, which is the shape of bug that reads as
     * intermittent.</p>
     */
    @Test
    public void promotionSurvivesTheBoxBeingRebuilt() {
        UIDocument document = document(100f, 100f);
        UIElement popup = sized("popup", 10f, 10f);
        document.append(popup);
        document.promote(popup);
        document.layout(100f, 100f);
        assertEquals(List.of("popup"), topLayerOrder(document));

        // Hidden and shown again: the box is destroyed and a new one built.
        popup.setDisplayed(false);
        document.layout(100f, 100f);
        assertNull(document.boxes().boxOf(popup));

        popup.setDisplayed(true);
        document.layout(100f, 100f);
        assertEquals("still promoted, on a box that did not exist a frame ago",
                List.of("popup"), topLayerOrder(document));
    }

    /** Demoting returns it to ordinary layout under its own parent. */
    @Test
    public void demotingReturnsItToItsOwnParent() {
        UIDocument document = document(100f, 100f);
        UIElement holder = sized("holder", 100f, 100f);
        UIElement popup = sized("popup", 10f, 10f);
        holder.append(popup);
        document.append(holder);
        document.promote(popup);
        document.layout(100f, 100f);
        assertSame(document.topLayer(), document.boxes().boxOf(popup).host());

        document.demote(popup);
        document.layout(100f, 100f);
        assertSame(document.boxes().boxOf(holder), document.boxes().boxOf(popup).host());
    }

    /**
     * An empty top layer takes NO SPACE, which is what stops it eating every click.
     *
     * <p>The compositor's own rule one level up, and this codebase's most-repeated failure: a
     * transparent full-size element over the document hit-tests across the whole surface, so a UI that
     * never promoted anything simply stops responding, and nothing on screen points at the cause.</p>
     */
    @Test
    public void anEmptyTopLayerTakesNoSpaceAndCannotSwallowAClick() {
        UIDocument document = document(200f, 200f);
        UIElement content = sized("content", 200f, 200f);
        document.append(content);
        document.topLayerNode();
        document.layout(200f, 200f);

        Box hit = document.boxes().hitTest(100f, 100f);
        assertNotNull(hit);
        assertSame("the click reached the content, not the layer over it", content, hit.node());
        assertFalse(document.hasTopLayerContent());
    }

    // ── attributes ───────────────────────────────────────────────────────────

    /** The convenience setters are the attribute, under the names the widget layer already uses. */
    @Test
    public void theConvenienceSettersAreTheAttribute() {
        UIElement node = new UIElement();

        node.setEnabled(false);
        assertFalse(node.isEnabled());
        assertFalse(node.get(Attribute.ENABLED));

        node.setHitTest(false);
        assertFalse(node.isHitTest());

        node.setInert(true);
        assertTrue(node.isInertAttribute());

        node.setDisplayed(false);
        assertFalse(node.isDisplayed());
        assertTrue(node.get(Attribute.HIDDEN));

        node.setScrollExempt(true);
        assertTrue(node.isScrollExempt());
    }

    /**
     * A recycled row SWAPS its data-driven class rather than adding to it.
     *
     * <p>A template is a different row every time a view reuses it, so adding {@code filetype-java}
     * without removing {@code filetype-md} leaves both on the node and the cascade resolves whichever
     * wins — which reads as a random colour rather than a stale class.</p>
     */
    @Test
    public void swappingAPrefixedClassRemovesTheOneItReplaces() {
        UIElement row = new UIElement();
        row.addClass("filetype-md").addClass("selected");

        row.swapPrefixedClass("filetype-", "filetype-java");
        assertTrue(row.hasClass("filetype-java"));
        assertFalse("the previous one went with it", row.hasClass("filetype-md"));
        assertTrue("and an unrelated class is untouched", row.hasClass("selected"));

        row.swapPrefixedClass("filetype-", null);
        assertFalse(row.hasClass("filetype-java"));
        assertTrue(row.hasClass("selected"));
    }

    /** {@code setOnlyChild} is a no-op when it already is — which is the point of having it. */
    @Test
    public void setOnlyChildDoesNothingWhenItAlreadyIs() {
        UIDocument document = document(100f, 100f);
        UIElement host = new UIElement();
        UIElement child = new UIElement();
        document.append(host);
        host.setOnlyChild(child);

        List<String> ops = new ArrayList<>();
        new com.crystalgui.ui.dom.UIElementTreeSource(document).observe(
                new com.crystalgui.ui.dom.TreeObserver.Adapter<UIElement>() {
                    @Override public void inserted(UIElement n, UIElement p, int i) { ops.add("inserted"); }
                    @Override public void removed(UIElement n, UIElement p) { ops.add("removed"); }
                });

        host.setOnlyChild(child);
        assertEquals("a rebuild that changes nothing must not be a removal and an insertion on the wire",
                List.of(), ops);

        UIElement other = new UIElement();
        host.setOnlyChild(other);
        assertEquals(List.of("removed", "inserted"), ops);
    }

    // ── querying ─────────────────────────────────────────────────────────────

    /**
     * A query walks the LIGHT tree and cannot see into a shadow root — the web's own rule.
     *
     * <p>A shadow tree is the widget's private business, and a caller reaching into one has coupled
     * itself to an implementation detail. The old engine had no boundary to respect, so its
     * {@code querySelector} reached every internal child of every widget.</p>
     */
    @Test
    public void aQueryDoesNotSeeIntoAShadowTree() {
        UIDocument document = document(100f, 100f);
        UIElement host = new UIElement().setId("host");
        UIElement lightChild = new UIElement().setId("light");
        UIElement part = new UIElement().setId("part");
        part.addClass("target");
        lightChild.addClass("target");
        host.attachShadow().append(part);
        host.append(lightChild);
        document.append(host);

        assertSame(lightChild, host.querySelector(".target"));
        assertEquals(1, host.querySelectorAll(".target").size());
        assertNull("the shadow part is not reachable by id either", host.getElementById("part"));
        assertSame(lightChild, host.getElementById("light"));
    }

    /** {@code require} is a miss stated as a programming error rather than a null to carry around. */
    @Test
    public void requireThrowsWhereFindAnswersNull() {
        UIElement host = new UIElement();
        UIElement child = new UIElement();
        child.addClass("row");
        host.append(child);

        assertSame(child, host.require(".row", UIElement.class));
        assertNull(host.find(".missing", UIElement.class));
        try {
            host.require(".missing", UIElement.class);
            org.junit.Assert.fail("a required miss is a programming error");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(".missing"));
        }
    }

    /** The document answers by its id INDEX, which reaches what a light-tree walk deliberately does not. */
    @Test
    public void theDocumentAnswersByIndexAndAWalkDoesNot() {
        UIDocument document = document(100f, 100f);
        UIElement host = new UIElement();
        UIElement part = new UIElement().setId("part");
        host.attachShadow().append(part);
        document.append(host);

        assertSame("the index is the engine's own bookkeeping", part, document.getElementById("part"));
        assertNull("the walk respects the boundary", host.getElementById("part"));
    }

    // ── commands ─────────────────────────────────────────────────────────────

    /**
     * The command hooks run on the first ATTACH, and once per class for {@code registerCommands}.
     *
     * <p>The old engine ran {@code registerCommands} from {@code UIElement}'s instance initialiser,
     * where fields are not assigned yet — so a widget contributing a per-instance thing passed null
     * and the whole feature was dead on arrival with nothing logged, because "no provider" and "a
     * provider that knows nothing" look identical from outside.</p>
     */
    @Test
    public void theCommandHooksRunOnAttachAndRegisterCommandsRunsOncePerClass() {
        UIDocument document = document(100f, 100f);
        CountingNode.registrations = 0;

        CountingNode first = new CountingNode();
        CountingNode second = new CountingNode();
        document.append(first);
        document.append(second);

        assertEquals("once for the class", 1, CountingNode.registrations);
        assertEquals("but per instance for the chords", 1, first.binds);
        assertEquals(1, second.binds);
        assertTrue("and the node is BUILT by then, unlike an instance initialiser", first.fieldWasSet);
    }

    private static final class CountingNode extends UIElement {
        static int registrations;
        int binds;
        /** Assigned in the constructor: an instance initialiser would see this false. */
        final boolean fieldWasSet = true;

        @Override
        protected void registerCommands(com.crystalgui.core.command.CommandRegistry registry) {
            registrations++;
        }

        @Override
        protected void bindKeys() {
            binds++;
        }
    }
}
