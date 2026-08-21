package com.crystalgui.text.syntax;

import com.crystalgui.core.signal.Connection;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * M12 / P6.1.13 D22's stated limit — a language that gains services after documents are already open.
 *
 * <h3>What the limit was</h3>
 *
 * <p>D22 made {@code JavaLanguage} retry its engine resolve per document instead of caching the first
 * failure, which is what lets an engine band that arrives by download become usable at all. What it could
 * not do is reach a document that was <b>already open</b>: services are attached once, when a document is
 * created, so an editor on screen when the band landed stayed dark until it was closed and reopened. The
 * progress plan named it a known limit rather than leaving it to be reported as a bug.</p>
 *
 * <p>The announcement is the missing half. This pins the signal itself; the workbench's response to it —
 * fill the nulls, touch nothing already attached — is asserted where a workbench can be built.</p>
 *
 * <p>In {@code headlessTest} deliberately: {@link LanguageRegistry} is the engineless tier and this is a
 * signal, not a widget. If it ever needs a font or a GL context to be tested, something has moved into
 * the wrong module.</p>
 */
public class LateCapabilityTest {

    @Test
    public void aCapabilityChangeIsAnnouncedToWhoeverIsListening() {
        AtomicInteger heard = new AtomicInteger();
        Connection listening = LanguageRegistry.onCapabilityChanged.connect(heard::incrementAndGet);
        try {
            assertEquals("nothing until something changes", 0, heard.get());
            LanguageRegistry.capabilityChanged();
            assertEquals(1, heard.get());
            LanguageRegistry.capabilityChanged();
            assertEquals("each change is its own announcement", 2, heard.get());
        } finally {
            listening.disconnect();
        }
    }

    /**
     * <b>A disconnected listener hears nothing</b>, which is what makes it safe for a workbench to
     * subscribe.
     *
     * <p>The signal is on a class that lives for the process, so a workbench that stayed subscribed after
     * being disposed would keep an entire editor tree reachable behind it — the reason the subscription is
     * made when the workbench attaches to a window and released when it detaches, rather than in its
     * constructor. The same mistake this file's neighbour records for the problem count.</p>
     */
    @Test
    public void aDisconnectedListenerStopsHearing() {
        AtomicInteger heard = new AtomicInteger();
        Connection listening = LanguageRegistry.onCapabilityChanged.connect(heard::incrementAndGet);
        LanguageRegistry.capabilityChanged();
        listening.disconnect();
        LanguageRegistry.capabilityChanged();
        assertEquals("a released workbench must not still be reacting", 1, heard.get());
    }
}
