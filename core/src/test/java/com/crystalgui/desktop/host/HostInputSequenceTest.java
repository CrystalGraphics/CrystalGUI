package com.crystalgui.desktop.host;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.ui.dom.UIDocument;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The router posting to a document on a sequence: every key answered "ours" at once, the ones dispatch left handed back in order. */
public class HostInputSequenceTest {

    private final UiSequence sequence = UiSequence.create("input");

    @After
    public void close() {
        sequence.close();
    }

    private void flush() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        sequence.execute(done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void keysTheDocumentLeavesComeBackInOrder() throws Exception {
        UIDocument document = new UIDocument().runOn(sequence);
        CountDownLatch built = new CountDownLatch(1);
        sequence.execute(() -> {
            document.frame(0.016f, 200f, 100f);
            built.countDown();
        });
        assertTrue(built.await(10, TimeUnit.SECONDS));

        HostInput input = new HostInput(() -> document, true);
        int[] keys = {CgKeyCodes.KEY_E, CgKeyCodes.KEY_F, CgKeyCodes.KEY_G};
        for (int key : keys) {
            assertTrue("a key to a document on a sequence is answered ours",
                    input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event((char) 0, key, true, false, 0L)));
        }
        flush();

        List<Integer> back = new ArrayList<>();
        for (CgSystemInput.Keyboard.Event key; (key = input.pollUnhandledKey()) != null; ) back.add(key.key());
        assertEquals(List.of(CgKeyCodes.KEY_E, CgKeyCodes.KEY_F, CgKeyCodes.KEY_G), back);
        assertNull(input.pollUnhandledKey());
    }
}
