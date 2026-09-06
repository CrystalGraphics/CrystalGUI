package com.crystalgui.ui.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>A freeze pauses a hook; it does not end it.</b>
 *
 * <p>{@code Animation.tick} distinguishes the two itself — <em>"GONE is gone; FROZEN is coming back"</em>
 * — and skips a frozen owner rather than dropping it. {@code Lifecycle.freeze} dropped the hooks anyway,
 * which made that skip unreachable and the freeze permanent: a hook is registered from
 * {@code connected()}, and a frozen node was never disconnected, so nothing re-registers on thaw.</p>
 *
 * <p>The dock freezes a tab it hides. So switching away from a document and back left every per-frame and
 * post-layout hook in that subtree gone for the rest of the session — a canvas whose overlays are placed
 * by an {@code afterLayout} hook came back with its handles stranded at their layer's origin and no hover
 * outline, with nothing anywhere reporting a problem.</p>
 */
public class FrozenHooksComeBackTest extends UiDocumentTestBase {

    /** Ticks per frame while it is alive. */
    private static final class Counter {
        int frames;
        int afterLayout;
    }

    private Counter install(UIElement owner) {
        Counter counter = new Counter();
        document.animation().every(owner, delta -> {
            counter.frames++;
            return true;
        });
        document.animation().afterLayout(owner, delta -> {
            counter.afterLayout++;
            return true;
        });
        return counter;
    }

    @Test
    public void aHookSurvivesAFreezeAndRunsAgainOnThaw() {
        UIElement node = new UIElement().layout(l -> l.width(40).height(20));
        document.append(node);
        Counter counter = install(node);

        frame();
        assertTrue("the hook never ran at all", counter.frames > 0);
        assertTrue(counter.afterLayout > 0);

        document.lifecycle().freeze(node);
        int frozenAt = counter.frames;
        int frozenAfterLayout = counter.afterLayout;
        frame();
        frame();
        assertEquals("a frozen owner must not tick", frozenAt, counter.frames);
        assertEquals(frozenAfterLayout, counter.afterLayout);

        document.lifecycle().thaw(node);
        frame();

        assertTrue("the freeze was permanent: nothing re-registers on thaw, because a hook is "
                        + "registered from connected() and a frozen node was never disconnected",
                counter.frames > frozenAt);
        assertTrue("the post-layout hook did not come back", counter.afterLayout > frozenAfterLayout);
    }

    /** Destroying still ends them — that is the case {@code Animation.forget} exists for. */
    @Test
    public void destroyingEndsThemForGood() {
        UIElement node = new UIElement().layout(l -> l.width(40).height(20));
        document.append(node);
        Counter counter = install(node);
        frame();
        assertTrue(counter.frames > 0);

        document.lifecycle().destroy(node);
        int destroyedAt = counter.frames;
        frame();
        frame();

        assertEquals("a destroyed subtree's hooks must not run", destroyedAt, counter.frames);
    }
}
