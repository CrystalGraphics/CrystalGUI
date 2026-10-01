package com.crystalgui.ui.dom;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.async.UiSequence;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A document driven asynchronously: every key answered "ours" at once, the ones dispatch left handed back in order. */
public class DocumentDriverTest {

    private final UIDocument document = new UIDocument();
    private final DocumentDriver<Void> driver = DocumentDriver.attach(document, DocumentDriver.Mode.ASYNC, "input");

    @After
    public void close() {
        driver.close();
    }

    private void flush() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        driver.sequence().execute(done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void keysTheDocumentLeavesComeBackInOrder() throws Exception {
        driver.post(() -> document.frame(0.016f, 200f, 100f));

        int[] keys = {CgKeyCodes.KEY_E, CgKeyCodes.KEY_F, CgKeyCodes.KEY_G};
        for (int key : keys) {
            assertTrue("a key to a document on a sequence is answered ours",
                    driver.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event((char) 0, key, true, false, 0L)));
        }
        flush();

        List<Integer> back = new ArrayList<>();
        for (CgSystemInput.Keyboard.Event key; (key = driver.pollUnhandledKey()) != null; ) back.add(key.key());
        assertEquals(List.of(CgKeyCodes.KEY_E, CgKeyCodes.KEY_F, CgKeyCodes.KEY_G), back);
        assertNull(driver.pollUnhandledKey());
    }

    @Test
    public void theDocumentIsTheSequencesAndAskedThere() {
        UiSequence sequence = driver.sequence();
        assertSame(sequence, document.sequence());
        assertSame(driver, document.driver());
        assertFalse(sequence.isCurrent());
        assertTrue(driver.ask(sequence::isCurrent));
    }

    @Test(expected = IllegalStateException.class)
    public void aDocumentHasOneDriver() {
        DocumentDriver.attach(document, DocumentDriver.Mode.INLINE, "second");
    }
}
