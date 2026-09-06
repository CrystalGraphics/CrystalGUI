package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.crystalgui.core.async.UiThread;
import com.crystalgui.ui.dom.*;
import com.crystalgui.ui.dom.UIElement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * The node tree on its own terms — plan/engine-core.md 5.1's acceptance beyond the seam suite.
 *
 * <p>What the seam cannot see: the composed tree through slots, retargeting across nested shadow
 * roots, lifecycle order and timing, the two refusals (a mutation from inside an observer
 * notification, a mutation from the wrong thread), and the id index.</p>
 */
public class UIElementTreeTest {

    @After
    public void restoreThreadEnforcement() {
        UiThread.setEnforcing(true);
    }

    private static UIElement named(String id) {
        return new UIElement().setId(id);
    }

    private static List<String> ids(Iterable<UIElement> nodes) {
        List<String> out = new ArrayList<>();
        for (UIElement node : nodes) out.add(node.id().isEmpty() ? node.name().local() : node.id());
        return out;
    }

    // ── Light tree ───────────────────────────────────────────────────────────

    @Test
    public void lightChildrenAreWhatAuthorsSee() {
        UIElement root = named("root");
        UIElement a = named("a");
        UIElement b = named("b");
        root.append(a).append(b);
        root.insertAt(1, named("mid"));
        assertEquals(List.of("a", "mid", "b"), ids(root.children()));
        assertSame(root, a.parent());
        assertTrue(root.contains(b));
        assertTrue(root.remove(a));
        assertNull(a.parent());
        assertFalse("removing what is not a child is not an error, it is false", root.remove(a));
    }

    @Test
    public void aNodeCannotContainItself() {
        UIElement root = named("root");
        UIElement child = named("child");
        root.append(child);
        try {
            child.append(root);
            fail("a cycle must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("itself"));
        }
    }

    @Test
    public void anAttributeEqualToItsInitialIsNotStored() {
        UIElement node = new UIElement();
        assertTrue("the initial answers before anything is set", node.get(Attribute.ENABLED));
        assertFalse(node.has(Attribute.ENABLED));
        node.set(Attribute.ENABLED, false);
        assertTrue(node.has(Attribute.ENABLED));
        node.set(Attribute.ENABLED, true);
        assertFalse("back to the initial means back to unset, which is what the wire omits", node.has(Attribute.ENABLED));
    }

    @Test
    public void theIdIndexFollowsTheTree() {
        UIDocument document = new UIDocument();
        UIElement branch = named("branch");
        UIElement leaf = named("leaf");
        branch.append(leaf);
        document.append(branch);
        assertSame(leaf, document.getElementById("leaf"));
        document.remove(branch);
        assertNull("a detached node is not findable by id", document.getElementById("leaf"));
        document.append(branch);
        assertSame(leaf, document.getElementById("leaf"));
        leaf.setId("renamed");
        assertNull(document.getElementById("leaf"));
        assertSame(leaf, document.getElementById("renamed"));
    }

    // ── Shadow trees and slots ───────────────────────────────────────────────

    @Test
    public void aShadowTreeIsNotALightChild() {
        UIElement host = named("host");
        ShadowRoot shadow = host.attachShadow();
        UIElement part = named("part");
        shadow.append(part);
        assertTrue("shadow content is not a light child", host.children().isEmpty());
        assertNull("a shadow root has no parent; the way up is its host", shadow.parent());
        assertSame(host, shadow.host());
        assertSame(shadow, part.containingShadowRoot());
        assertTrue(part.isInShadowTree());
        assertFalse(host.isInShadowTree());
        try {
            host.attachShadow();
            fail("a second shadow root must be refused");
        } catch (IllegalStateException expected) {
            // one host, one shadow root
        }
    }

    @Test
    public void lightChildrenAppearWhereASlotTakesThem() {
        UIElement host = named("host");
        ShadowRoot shadow = host.attachShadow();
        UIElement frame = named("frame");
        UISlot slot = new UISlot();
        frame.append(slot);
        shadow.append(frame);
        UIElement a = named("a");
        UIElement b = named("b");
        host.append(a).append(b);

        assertEquals(List.of(a, b), slot.assignedNodes());
        assertSame(slot, a.assignedSlot());
        assertSame("the composed parent of slotted content is the slot", slot, a.composedParent());
        assertEquals("a host's composed children are its shadow tree's", List.of(frame), host.composedChildren());
        assertEquals(List.of(a, b), slot.composedChildren());
        assertSame("a shadow child's composed parent is the host", host, frame.composedParent());
    }

    @Test
    public void namedSlotsTakeTheChildrenThatAskForThem() {
        UIElement host = named("host");
        ShadowRoot shadow = host.attachShadow();
        UISlot icon = new UISlot("icon");
        UISlot body = new UISlot();
        shadow.append(icon).append(body);
        UIElement glyph = named("glyph").set(Attribute.SLOT, "icon");
        UIElement text = named("text");
        host.append(glyph).append(text);

        assertEquals(List.of(glyph), icon.assignedNodes());
        assertEquals(List.of(text), body.assignedNodes());
        assertSame(icon, shadow.slot("icon"));
        assertSame(body, shadow.slot(""));

        glyph.set(Attribute.SLOT, "");
        assertEquals("a changed slot attribute reassigns", List.of(), icon.assignedNodes());
        assertEquals(List.of(glyph, text), body.assignedNodes());
    }

    @Test
    public void anUnslottedChildIsNotInTheComposedTree() {
        UIElement host = named("host");
        host.attachShadow().append(named("part"));
        UIElement orphan = named("orphan");
        host.append(orphan);
        assertNull(orphan.assignedSlot());
        assertNull("nowhere to appear, so it is not rendered", orphan.composedParent());
        assertEquals(List.of("host", "part"), ids(host.composedSubtree()));
    }

    @Test
    public void fallbackShowsUntilSomethingIsAssigned() {
        UIElement host = named("host");
        UISlot slot = new UISlot();
        UIElement fallback = named("fallback");
        slot.append(fallback);
        host.attachShadow().append(slot);
        assertEquals(List.of(fallback), slot.composedChildren());

        UIElement content = named("content");
        host.append(content);
        assertEquals(List.of(content), slot.composedChildren());
        host.remove(content);
        assertEquals("and the fallback returns when the content goes", List.of(fallback), slot.composedChildren());
    }

    @Test
    public void composedDescendantsWalkLightAndShadowThroughSlots() {
        UIDocument document = new UIDocument();
        UIElement host = named("host");
        UIElement wrapper = named("wrapper");
        UISlot slot = new UISlot();
        wrapper.append(slot);
        host.attachShadow().append(wrapper);
        UIElement leaf = named("leaf");
        host.append(leaf);
        document.append(host);
        assertEquals(List.of("document", "host", "wrapper", "slot", "leaf"), ids(document.composedSubtree()));
    }

    private static final class RecordingSlot extends UISlot {
        final List<Boolean> mutatingWhenCalled = new ArrayList<>();

        @Override
        protected void slotChanged() {
            UIDocument doc = document();
            mutatingWhenCalled.add(doc != null && doc.isMutating());
        }
    }

    @Test
    public void slotChangeRunsAfterTheMutationThatCausedIt() {
        UIDocument document = new UIDocument();
        UIElement host = named("host");
        RecordingSlot slot = new RecordingSlot();
        host.attachShadow().append(slot);
        document.append(host);
        slot.mutatingWhenCalled.clear();

        host.append(named("content"));
        assertEquals("one change, one callback", 1, slot.mutatingWhenCalled.size());
        assertFalse("and it ran after the mutation had finished", slot.mutatingWhenCalled.get(0));
    }

    // ── Retargeting ──────────────────────────────────────────────────────────

    @Test
    public void retargetingCrossesEveryBoundaryTheObserverIsOutsideOf() {
        UIDocument document = new UIDocument();
        UIElement host = named("host");
        UIElement part = named("part");
        host.attachShadow().append(part);
        UIElement inner = named("inner");
        part.attachShadow().append(inner);
        document.append(host);

        assertSame("from the document, everything inside is the host", host, UIElement.retarget(inner, document));
        assertSame("from inside the host's tree, the part is visible but not the part's insides",
                part, UIElement.retarget(inner, part));
        assertSame("from inside the part's tree, the target is itself", inner, UIElement.retarget(inner, inner));
        assertSame("a light node retargets to itself", host, UIElement.retarget(host, document));
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    private static final class Recording extends UIElement {
        static final List<String> log = new ArrayList<>();
        private final String tag;

        Recording(String tag) {
            this.tag = tag;
            setId(tag);
        }

        @Override
        protected void connected() {
            UIDocument doc = document();
            log.add("connected " + tag + (doc != null && doc.isMutating() ? " DURING" : ""));
        }

        @Override
        protected void disconnected() {
            log.add("disconnected " + tag);
        }
    }

    @Test
    public void lifecycleRunsAfterTheMutationParentsFirstChildrenLast() {
        Recording.log.clear();
        UIDocument document = new UIDocument();
        Recording branch = new Recording("branch");
        Recording leaf = new Recording("leaf");
        branch.append(leaf);
        assertTrue("nothing is connected until a document is", Recording.log.isEmpty());

        document.append(branch);
        assertEquals(List.of("connected branch", "connected leaf"), Recording.log);

        Recording.log.clear();
        document.remove(branch);
        assertEquals(List.of("disconnected leaf", "disconnected branch"), Recording.log);
    }

    private static final class BuildsOnConnect extends UIElement {
        @Override
        protected void connected() {
            append(named("built"));
        }
    }

    @Test
    public void aMutationFromALifecycleCallbackIsAnOrdinaryMutation() {
        UIDocument document = new UIDocument();
        BuildsOnConnect builder = new BuildsOnConnect();
        document.append(builder);
        assertEquals("the callback ran after the mutation and could mutate", 1, builder.children().size());
        assertSame(document, builder.children().get(0).document());
        assertFalse(document.isMutating());
    }

    @Test
    public void aMutationFromInsideAnObserverNotificationIsRefused() {
        UIDocument document = new UIDocument();
        UIElementTreeSource source = new UIElementTreeSource(document);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        source.observe(new TreeObserver.Adapter<UIElement>() {
            @Override
            public void inserted(UIElement node, UIElement parent, int index) {
                try {
                    parent.append(new UIElement());
                } catch (RuntimeException refused) {
                    caught.set(refused);
                }
            }
        });
        document.append(new UIElement());
        assertNotNull("the observer is being told about a change still being made", caught.get());
        assertTrue(caught.get() instanceof IllegalStateException);
        assertEquals("and the refused insert left nothing behind", 1, document.children().size());
    }

    @Test
    public void aMutationFromAnotherThreadIsRefused() throws InterruptedException {
        UIDocument document = new UIDocument().markFrameThread();
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                document.append(new UIElement());
            } catch (RuntimeException refused) {
                caught.set(refused);
            }
        }, "not-the-frame-thread");
        other.start();
        other.join();
        assertNotNull("the frame thread owns the tree", caught.get());
        assertTrue(caught.get().getMessage(), caught.get().getMessage().contains("frames"));
        assertTrue("nothing was inserted", document.children().isEmpty());
        document.append(new UIElement());   // the owner is still free to
    }

    // ── The light tree as the observer sees it ───────────────────────────────

    @Test
    public void movingAcrossAShadowBoundaryIsARemovalOrAnInsertionToTheLightTree() {
        UIDocument document = new UIDocument();
        UIElement host = named("host");
        ShadowRoot shadow = host.attachShadow();
        UIElement content = named("content");
        host.append(content);
        document.append(host);
        List<String> lines = new ArrayList<>();
        new UIElementTreeSource(document).observe(new TreeObserver.Adapter<UIElement>() {
            @Override public void inserted(UIElement n, UIElement p, int i) { lines.add("inserted " + n.id()); }
            @Override public void removed(UIElement n, UIElement p) { lines.add("removed " + n.id()); }
            @Override public void moved(UIElement n, UIElement p, int i) { lines.add("moved " + n.id()); }
        });

        content.moveTo(shadow, 0);
        assertEquals("into a shadow tree is out of the light tree", List.of("removed content"), lines);
        assertTrue(content.isInShadowTree());

        content.moveTo(host, 0);
        assertEquals(List.of("removed content", "inserted content"), lines);
        assertFalse(content.isInShadowTree());
    }

    /**
     * {@code of(local)} is the default namespace and NOT {@link Name#parse} — the distinction that is
     * silent when it is got wrong.
     *
     * <p>Both take one string, and a qualified one is exactly what somebody reaches for {@code of}
     * with. {@code parse} is the reader and splits it; {@code of} takes the LOCAL half, so a colon
     * fails the character check and says so. If {@code of} ever started splitting, every mod naming
     * {@code mymod:machine} would land in the wrong namespace and collide with ours, which is the one
     * thing a namespace exists to prevent.</p>
     */
    @Test
    public void ofTakesTheLocalHalfAndParseTakesEither() {
        assertEquals(Name.of("crystalgui", "button"), Name.of("button"));
        assertEquals(Name.of("crystalgui", "button"), Name.parse("button"));
        assertEquals(Name.of("mymod", "machine"), Name.parse("mymod:machine"));

        try {
            Name.of("mymod:machine");
            fail("of() takes the local half; a qualified name must not silently split");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("local name"));
        }
    }

    /**
     * A kind's name is declared on the class it names, and the registry is asked by that name.
     *
     * <p>Pins the move off {@code Name}'s own constants: the four were a second place to look up what
     * a kind is called, and only one of the two could be extended by a widget outside this module.
     * Asserting the registry answers {@code UIElement.NAME} is what makes the constant load-bearing
     * rather than decorative.</p>
     */
    @Test
    public void aKindsNameIsDeclaredOnItsOwnClass() {
        assertEquals("crystalgui:element", UIElement.NAME.toString());
        assertEquals("crystalgui:document", UIDocument.NAME.toString());
        assertEquals("crystalgui:slot", UISlot.NAME.toString());
        assertEquals("crystalgui:shadow-root", ShadowRoot.NAME.toString());

        assertTrue(UIElementRegistry.create(UIElement.NAME) instanceof UIElement);
        assertTrue(UIElementRegistry.create(UISlot.NAME) instanceof UISlot);
        assertTrue("a shadow root is never described, so nothing builds one from a name",
                !UIElementRegistry.isRegistered(ShadowRoot.NAME));
    }
}
