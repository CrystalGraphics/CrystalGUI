package com.crystalgui.viewport;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.dom.UIDocument;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** One document on screen: input, the document's thread, and a commit with its readouts in every threading. */
public class CgUiViewportTest {

    private static final Readout<String> NOTE = Readout.named("note");

    /** Frames the document and paints nothing: a frame with no GL. */
    private static CgUiViewport.Content framesOnly(UIDocument document) {
        return new CgUiViewport.Content() {
            @Override
            public boolean frame(float deltaSeconds, int width, int height) {
                document.frame(deltaSeconds, width, height);
                return false;
            }

            @Override
            public void paint(CgUiPaintContext ctx) {
            }

            @Override
            public void readouts(Readouts out) {
                out.put(NOTE, "framed");
            }
        };
    }

    private static CgSystemInput.Keyboard.Event key(int key, boolean pressed) {
        return new CgSystemInput.Keyboard.Event((char) 0, key, pressed, false, 0L);
    }

    @Test
    public void sequencedKeysTheDocumentLeavesComeBackInOrder() {
        UIDocument document = new UIDocument();
        CgUiViewport viewport = CgUiViewport.open(document, CgUiViewport.Threading.SEQUENCED, "input");
        try {
            viewport.execute(() -> document.frame(0.016f, 200f, 100f));
            int[] keys = {CgKeyCodes.KEY_E, CgKeyCodes.KEY_F, CgKeyCodes.KEY_G};
            for (int key : keys) {
                assertTrue("a key to a sequenced document is answered ours", viewport.consumeKeyboardEvent(key(key, true)));
            }
            viewport.run(() -> { });

            List<Integer> back = new ArrayList<>();
            for (CgSystemInput.Keyboard.Event left; (left = viewport.pollUnhandledKey()) != null; ) back.add(left.key());
            assertEquals(List.of(CgKeyCodes.KEY_E, CgKeyCodes.KEY_F, CgKeyCodes.KEY_G), back);
        } finally {
            viewport.close();
        }
    }

    @Test
    public void sequencedHeldKeysAreTheEventsPosted() {
        UIDocument document = new UIDocument();
        CgUiViewport viewport = CgUiViewport.open(document, CgUiViewport.Threading.SEQUENCED, "held");
        try {
            viewport.consumeKeyboardEvent(key(CgKeyCodes.KEY_LCONTROL, true));
            assertTrue(CgModifiers.hasCtrl(viewport.ask(() -> document.input().modifiers())));
            viewport.consumeKeyboardEvent(key(CgKeyCodes.KEY_LCONTROL, false));
            assertEquals(CgModifiers.NONE, (int) viewport.ask(() -> document.input().modifiers()));
        } finally {
            viewport.close();
        }
    }

    @Test
    public void theDocumentIsTheViewportsThreadsAndAskedThere() {
        UIDocument document = new UIDocument();
        CgUiViewport viewport = CgUiViewport.open(document, CgUiViewport.Threading.LOCKSTEP, "owner");
        try {
            assertFalse(document.isCurrent());
            assertTrue(viewport.ask(document::isCurrent));
            assertNotNull(viewport.ask(UiSequence::current));
        } finally {
            viewport.close();
        }
        assertTrue("a closed viewport gives the document back", document.isCurrent());
    }

    @Test(expected = IllegalStateException.class)
    public void aDocumentHasOneViewport() {
        UIDocument document = new UIDocument();
        CgUiViewport.open(document, CgUiViewport.Threading.INLINE, "first");
        CgUiViewport.open(document, CgUiViewport.Threading.INLINE, "second");
    }

    /** Every threading commits, so a host reads the same readouts in each. */
    @Test
    public void everyThreadingCommitsItsReadouts() {
        for (CgUiViewport.Threading threading : CgUiViewport.Threading.values()) {
            UIDocument document = new UIDocument();
            CgUiViewport viewport = CgUiViewport.open(document, threading, "readouts");
            try {
                assertNull(threading + ": a readout before any frame", viewport.readout(NOTE));
                viewport.frame(null, 0.016f, 100, 100, framesOnly(document));
                viewport.run(() -> { });
                assertTrue(threading + ": nothing committed", viewport.hasCommitted());
                assertEquals(threading.name(), "framed", viewport.readout(NOTE));
                assertEquals(threading.name(), Boolean.FALSE, viewport.readout(CgUiViewport.POINTER_CAPTURED));
                assertEquals(threading.name(), 1L, viewport.committedFrames());
            } finally {
                viewport.close();
            }
        }
    }

    /** A frame that throws is logged and handed to its content; the next frame runs. */
    @Test
    public void aFailedFrameReachesItsContentAndTheNextRuns() {
        UIDocument document = new UIDocument();
        CgUiViewport viewport = CgUiViewport.open(document, CgUiViewport.Threading.INLINE, "failing");
        List<Throwable> failures = new ArrayList<>();
        boolean[] fail = {true};
        CgUiViewport.Content content = new CgUiViewport.Content() {
            @Override
            public boolean frame(float deltaSeconds, int width, int height) {
                if (fail[0]) throw new IllegalStateException("broken frame");
                return false;
            }

            @Override
            public void paint(CgUiPaintContext ctx) {
            }

            @Override
            public void failed(Throwable cause) {
                failures.add(cause);
            }
        };
        try {
            viewport.frame(null, 0.016f, 100, 100, content);
            assertEquals(1, failures.size());
            fail[0] = false;
            viewport.frame(null, 0.016f, 100, 100, content);
            assertEquals(1, failures.size());
            assertEquals(2L, viewport.committedFrames());
        } finally {
            viewport.close();
        }
    }

    /** An extract's read is the host's, its use the document's. */
    @Test
    public void onASequenceAnExtractIsReadByTheHostAndUsedByTheDocument() {
        UIDocument document = new UIDocument();
        CgUiViewport viewport = CgUiViewport.open(document, CgUiViewport.Threading.LOCKSTEP, "extract");
        try {
            UiSequence sequence = viewport.ask(UiSequence::current);
            List<Boolean> readOnSequence = new ArrayList<>();
            List<Boolean> usedOnSequence = new ArrayList<>();
            viewport.run(() -> document.extract(
                    () -> {
                        readOnSequence.add(UiSequence.current() == sequence);
                        return "state";
                    },
                    value -> usedOnSequence.add(sequence.isCurrent())));

            viewport.frame(null, 0.016f, 100, 100, framesOnly(document));

            assertEquals(List.of(false), readOnSequence);
            assertEquals(List.of(true), usedOnSequence);
        } finally {
            viewport.close();
        }
    }
}
