package com.crystalgui.ui.service;

import static com.crystalgui.ui.service.ServiceFixtures.at;
import static com.crystalgui.ui.service.ServiceFixtures.frame;
import static com.crystalgui.ui.service.ServiceFixtures.press;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.dom.Document;
import com.crystalgui.ui.dom.Node;
import com.crystalgui.ui.input.FocusPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Freezing: the subtree stops being live and keeps everything it holds — which is what makes
 * hide-as-detach unnecessary, and with it the eight invariant rows that were its bill.
 */
public class FreezeTest {

    private static Node scroller(Document document) {
        Node clip = at("clip", 0, 0, 100, 100);
        StyleGroup.inlinePipeline(clip.getStyle().getGeneralGroup(), g -> g.overflow(Overflow.HIDDEN));
        Node tall = at("tall", 0, 0, 100, 400);
        clip.append(tall);
        document.append(clip);
        return clip;
    }

    @Test
    public void aFrozenSubtreeHasNoBoxesAndComesBackWithThem() {
        Document document = new Document();
        Node panel = at("panel", 0, 0, 200, 200);
        Node child = at("child", 10, 10, 50, 50);
        panel.append(child);
        document.append(panel);
        frame(document);
        assertNotNull(panel.box());

        document.lifecycle().freeze(panel);
        frame(document);
        assertNull("it lays out nothing", panel.box());
        assertNull("and neither does anything under it", child.box());
        assertTrue("but it is still in the tree, with everything it holds", panel.isConnected());
        assertSame(document, panel.getParent());

        document.lifecycle().thaw(panel);
        frame(document);
        assertNotNull(panel.box());
        assertNotNull(child.box());
    }

    @Test
    public void aFrozenSubtreeIsUnreachableByThePointer() {
        Document document = new Document();
        Node behind = at("behind", 0, 0, 300, 300);
        Node panel = at("panel", 0, 0, 200, 200);
        document.append(behind).append(panel);
        frame(document);
        press(document, 50, 50);
        assertSame(panel, document.input().hoverTarget());

        document.lifecycle().freeze(panel);
        frame(document);
        assertSame("no box, so nothing to hit", behind, document.input().hoverTarget());
        assertFalse("and the input service dropped every reference it held", panel.isHovered());
    }

    @Test
    public void aFrozenSubtreeKeepsItsScrollWithNothingCaptured() {
        Document document = new Document();
        Node clip = scroller(document);
        frame(document);
        clip.box().setScroll(0f, 150f);
        assertEquals(150f, clip.box().scrollTop(), 0.001f);

        document.lifecycle().freeze(clip);
        frame(document);
        assertNull(clip.box());
        assertEquals("the offset is the NODE's, so nothing had to be read out on the way down",
                150f, clip.scrollTop(), 0.001f);

        document.lifecycle().thaw(clip);
        frame(document);
        assertEquals("and nothing had to be re-applied on the way back",
                150f, clip.box().scrollTop(), 0.001f);
    }

    @Test
    public void aFrozenSubtreeCostsNoTicks() {
        Document document = new Document();
        Node panel = at("panel", 0, 0, 200, 200);
        document.append(panel);
        frame(document);

        List<String> ticks = new ArrayList<>();
        document.animation().every(panel, delta -> {
            ticks.add("tick");
            return true;
        });
        frame(document);
        assertEquals(1, ticks.size());

        document.lifecycle().freeze(panel);
        frame(document);
        frame(document);
        assertEquals("a ticker was the ONE thing that carried on in a hidden window, invisibly, "
                + "because registration was one-way and only the ticker could stop it",
                1, ticks.size());
        assertEquals(0, document.animation().hookCount());
    }

    @Test
    public void freezingTakesFocusAndThawingDoesNotGiveItBack() {
        Document document = new Document();
        Node panel = at("panel", 0, 0, 200, 200);
        Node control = at("control", 10, 10, 50, 30).setFocusPolicy(FocusPolicy.CLICK);
        panel.append(control);
        document.append(panel);
        frame(document);
        document.focus().requestFocus(control);
        assertSame(control, document.focus().focused());

        document.lifecycle().freeze(panel);
        assertNull("focus cannot linger on something that is not live", document.focus().focused());

        document.lifecycle().thaw(panel);
        frame(document);
        assertNull("what had focus before is a question for whoever is bringing it back",
                document.focus().focused());
    }

    @Test
    public void freezingAndThawingRunTheHooksAndAreIdempotent() {
        Document document = new Document();
        List<String> log = new ArrayList<>();
        Node panel = new Node() {
            @Override
            protected void frozen() {
                log.add("frozen");
            }

            @Override
            protected void thawed() {
                log.add("thawed");
            }
        };
        panel.setId("panel");
        ServiceFixtures.layout(panel, l -> l.width(100f).height(100f));
        document.append(panel);
        frame(document);

        document.lifecycle().freeze(panel);
        document.lifecycle().freeze(panel);
        document.lifecycle().thaw(panel);
        document.lifecycle().thaw(panel);
        assertEquals(List.of("frozen", "thawed"), log);
    }

    @Test
    public void aDestroyedSubtreeIsForgottenEverywhere() {
        Document document = new Document();
        Node panel = at("panel", 0, 0, 200, 200).setFocusPolicy(FocusPolicy.CLICK);
        document.append(panel);
        frame(document);
        press(document, 50, 50);
        document.animation().every(panel, delta -> true);
        assertSame(panel, document.focus().focused());

        document.lifecycle().destroy(panel);
        frame(document);
        assertNull(document.focus().focused());
        assertSame("the document is what is left under the pointer", document, document.input().hoverTarget());
        assertEquals(0, document.animation().hookCount());
        assertFalse(panel.isConnected());
    }
}
