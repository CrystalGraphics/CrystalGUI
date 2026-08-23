package com.crystalgui.ui.elements.desktop;

import com.crystalgui.fs.InMemoryConfigStorage;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * CrystalOS <b>W12</b> — the desktop as it was left ({@code plan_windowing.md}).
 *
 * <p>Every test here is about something the plan's persistence table names as a distinct thing to get
 * right, because each fails in its own way and none of them fails loudly.</p>
 */
public class DesktopSessionTest extends UiTestBase {

    private static final String ID = "harness";

    private InMemoryConfigStorage storage;

    @Before
    public void freshStorage() {
        storage = new InMemoryConfigStorage();
    }

    /**
     * One desktop, with its window and session — a "launch".
     *
     * <p>{@link #restore} models what a real host does and is the whole point of the design: it does not
     * hand the compositor a factory, it asks for persistence and then <b>opens its windows normally</b>.
     * Whichever of them carries a key the record names is placed where it was; the rest are untouched.</p>
     */
    private final class Launch {
        final UIWindow window;
        final Desktop desktop;
        final DesktopSession session;

        Launch(float width, float height) {
            UIElement root = new UIElement().layout(l -> l.width(width).height(height));
            window = new UIWindow(Ui.of(root));
            window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
            window.init(Math.round(width * 2f), Math.round(height * 2f));
            desktop = window.desktop();
            session = new DesktopSession(desktop, storage);
            settle();
        }

        WindowFrame open(String key, float left, float top, float width, float height) {
            WindowFrame frame = window.openWindow(new WindowFrame(key).setKey(key));
            frame.resizeTo(width, height);
            frame.moveTo(left, top);
            settle();
            return frame;
        }

        void settle() {
            for (int pass = 0; pass < 3; pass++) window.updateWithoutPainting();
        }

        /** Asks for persistence, then reopens {@code keys} the way a host reopens its own windows. */
        List<WindowFrame> restore(String... keys) {
            desktop.persistTo(storage, ID);
            List<WindowFrame> made = new ArrayList<>();
            for (String key : keys) {
                made.add(window.openWindow(new WindowFrame(key).setKey(key)));
            }
            settle();
            return made;
        }

        void save() {
            desktop.persistTo(storage, ID);
            desktop.savePersistedState();
        }
    }

    private WindowFrame byKey(Desktop desktop, String key) {
        WindowFrame found = desktop.registry().byKey(key);
        assertNotNull("no window called " + key, found);
        return found;
    }

    /**
     * <b>A window comes back where it was.</b>
     *
     * <p>The spine: geometry survives a restart at all.</p>
     */
    @Test
    public void aWindowComesBackWhereItWas() {
        Launch first = new Launch(600, 400);
        first.open("editor", 40f, 30f, 200f, 150f);
        first.save();

        Launch second = new Launch(600, 400);
        second.restore("editor");

        WindowFrame back = byKey(second.desktop, "editor");
        assertEquals(40f, back.getWantedLeft(), 0.5f);
        assertEquals(30f, back.getWantedTop(), 0.5f);
        assertEquals(200f, back.getRuntimeCache().getWidth(), 1f);
        assertEquals(150f, back.getRuntimeCache().getHeight(), 1f);
    }

    /**
     * <b>The record keeps what was ASKED for, so a clamp cannot compound.</b>
     *
     * <p>The rule the plan states and {@code getWantedLeft}'s own javadoc was written for. A window
     * dragged past the edge is placed back inside it, so recording the PLACEMENT and restoring that
     * writes the clamp into the record — and each launch pulls the window a little further in, with
     * nothing on screen to attribute the drift to. Two round trips, because one cannot tell a clamp that
     * compounds from one that merely happened.</p>
     */
    @Test
    public void aClampIsNotWrittenIntoTheRecord() {
        Launch first = new Launch(600, 400);
        WindowFrame frame = first.open("far", 5000f, 4000f, 200f, 150f);
        assertTrue("the fixture never clamped anything", frame.left() < frame.getWantedLeft());
        first.save();

        Launch second = new Launch(600, 400);
        second.restore("far");
        second.save();

        Launch third = new Launch(600, 400);
        third.restore("far");

        assertEquals("the intent drifted between launches",
                5000f, byKey(third.desktop, "far").getWantedLeft(), 0.5f);
    }

    /**
     * <b>A maximised window comes back maximised, and knows what to go back to.</b>
     *
     * <p>The recorded geometry is the <em>un</em>-maximised rect, because a maximised window's own box is
     * the work area — whatever the screen happens to be next launch. Restoring the flag without the rect
     * leaves a window that cannot be un-maximised to anywhere sensible.</p>
     */
    @Test
    public void aMaximisedWindowComesBackWithSomewhereToRestoreTo() {
        Launch first = new Launch(600, 400);
        WindowFrame frame = first.open("big", 40f, 30f, 200f, 150f);
        frame.maximize();
        first.settle();
        assertTrue(frame.isMaximized());
        first.save();

        Launch second = new Launch(600, 400);
        second.restore("big");

        WindowFrame back = byKey(second.desktop, "big");
        assertTrue("it did not come back maximised", back.isMaximized());

        back.restore();
        second.settle();
        assertFalse(back.isMaximized());
        assertEquals("un-maximising went somewhere other than where it came from",
                200f, back.getRuntimeCache().getWidth(), 2f);
        assertEquals(150f, back.getRuntimeCache().getHeight(), 2f);
    }

    /**
     * <b>A window that was put away comes back put away.</b>
     *
     * <p>Not cosmetic: a hidden window with no record comes back as a fresh one, and the whole point of
     * retention is that it does not. Restoring it visible would also reorder the desktop — the thing you
     * minimised before quitting would be in front of you on the next launch.</p>
     */
    @Test
    public void aMinimisedWindowComesBackMinimised() {
        Launch first = new Launch(600, 400);
        first.open("shown", 10f, 10f, 160f, 120f);
        first.open("putAway", 60f, 60f, 160f, 120f).hide();
        first.settle();
        first.save();

        Launch second = new Launch(600, 400);
        second.restore("shown", "putAway");

        assertEquals(WindowState.HIDDEN, byKey(second.desktop, "putAway").state());
        assertEquals(WindowState.VISIBLE, byKey(second.desktop, "shown").state());
    }

    /**
     * <b>Both orders survive, and the front window is the one that was in front.</b>
     *
     * <p>The taskbar reads open order and the switcher reads MRU, and neither is derivable from the
     * other. The activation replay runs BACKWARDS through the MRU list so the most recent window is
     * activated last and therefore ends in front; forwards would leave the desktop showing whatever you
     * had looked at least recently.</p>
     */
    @Test
    public void openOrderAndMruBothSurvive() {
        Launch first = new Launch(600, 400);
        WindowFrame a = first.open("a", 10f, 10f, 160f, 120f);
        first.open("b", 30f, 30f, 160f, 120f);
        first.open("c", 50f, 50f, 160f, 120f);
        // Activated out of open order, so the two orders genuinely differ.
        first.desktop.activate(a);
        first.settle();
        assertSame(a, first.desktop.activeWindow());
        first.save();

        Launch second = new Launch(600, 400);
        second.restore("a", "b", "c");

        List<String> openOrder = new ArrayList<>();
        for (WindowFrame frame : second.desktop.registry().windows()) openOrder.add(frame.key());
        assertEquals("open order is what the taskbar draws", List.of("a", "b", "c"), openOrder);

        assertSame("the window that was in front is not in front",
                byKey(second.desktop, "a"), second.desktop.activeWindow());
    }

    /**
     * <b>A recorded window the host does not reopen is simply absent, and takes nothing with it.</b>
     *
     * <p>A record outlives the code that wrote it, so it will name windows a later version no longer has.
     * Under the apply-on-open design that needs no handling at all: the placement is never claimed and
     * nothing is constructed from it. Which is the point of inverting it — the factory version had to
     * decline explicitly, and a host that forgot to would either crash or lose the window silently.</p>
     *
     * <p>Asserted from the OTHER side as well: the window that <em>was</em> reopened must still be placed
     * where it was. An unclaimed placement that consumed the pass would be invisible in a one-window
     * test.</p>
     */
    @Test
    public void aRecordedWindowTheHostDoesNotReopenIsAbsent() {
        Launch first = new Launch(600, 400);
        first.open("kept", 10f, 10f, 160f, 120f);
        first.open("retired", 40f, 40f, 160f, 120f);
        first.save();

        Launch second = new Launch(600, 400);
        second.restore("kept");

        assertNull("a window nothing reopened came back anyway",
                second.desktop.registry().byKey("retired"));
        assertEquals("the window that WAS reopened lost its place",
                10f, byKey(second.desktop, "kept").getWantedLeft(), 0.5f);
    }

    /**
     * <b>A window nobody keyed cannot be restored by anyone, so it is never written down.</b>
     *
     * <p>Asserted on the RECORD, not on what reads it back. The reader drops a keyless entry too, so a
     * test that only round-tripped would pass against a writer that filled the file with rectangles
     * nothing could ever be applied to — which is a growing file describing windows that do not exist.</p>
     */
    @Test
    public void anUnkeyedWindowIsNotRecorded() {
        Launch first = new Launch(600, 400);
        first.open("keyed", 10f, 10f, 160f, 120f);
        // A title, not a key: openWindow gives it no identity anything could restore it by.
        first.window.openWindow(new WindowFrame("anonymous"));
        first.settle();

        // COUNTED, not searched for. The record writes a window's KEY, and an unkeyed window's is null --
        // so its title never appears whether or not it was written, and a `contains` assertion would pass
        // against a record with an empty-keyed entry in it.
        JsonArray windows = new JsonParser().parse(first.session.toJson())
                .getAsJsonObject().getAsJsonArray("windows");

        assertEquals("an unrestorable window was written into the record anyway", 1, windows.size());
        assertEquals("keyed",
                windows.get(0).getAsJsonObject().get("key").getAsString());
    }

    /**
     * <b>A window that was never laid out is dropped rather than restored at nothing.</b>
     *
     * <p>Reachable for real: a window opened and put away in the same frame has no measured box to
     * remember, so its record is a 0x0 rect at the origin — a legal encoding that four floats cannot tell
     * from "never placed", and an unusable window if applied. W8's second rule, generalised.</p>
     */
    @Test
    public void aWindowWithNoUsableRectIsNotRestored() {
        Launch first = new Launch(600, 400);
        // HIDDEN BEFORE ANY LAYOUT RAN, so there is nothing to have captured on the way out.
        first.window.openWindow(new WindowFrame("ghost").setKey("ghost")).hide();
        first.save();

        Launch second = new Launch(600, 400);

        assertTrue("a 0x0 placement was handed to the desktop to apply",
                second.session.read(ID).isEmpty());

        // ...and the window opens at its own size rather than at nothing, which is what the drop is FOR.
        WindowFrame reopened = second.restore("ghost").get(0);
        assertTrue("the window came back with no size at all",
                reopened.getRuntimeCache().getWidth() > 0f);
    }

    /**
     * <b>A record from another version is discarded, never half-applied.</b>
     *
     * <p>The {@code WorkbenchSession} policy: an arrangement invented by a migration is one nobody chose.
     * Discarding costs a re-arrange; guessing produces a desktop that looks broken.</p>
     */
    @Test
    public void aRecordFromAnotherVersionIsIgnored() {
        Launch first = new Launch(600, 400);
        first.open("editor", 40f, 30f, 200f, 150f);
        first.save();
        storage.write(DesktopSession.fileNameFor(ID),
                storage.read(DesktopSession.fileNameFor(ID))
                        .replace("\"version\": " + DesktopSession.VERSION, "\"version\": 999"));

        Launch second = new Launch(600, 400);

        // ASKED OF THE RECORD, not of a restore that opened no windows -- which is what this used to do
        // and is a test that passes against anything at all. The version gate lives in read(), and a
        // desktop can only apply what read() hands it.
        assertTrue("a record from the future was read anyway", second.session.read(ID).isEmpty());
    }

    /** A malformed record describes nothing, and must not be an exception on the way in. */
    @Test
    public void aMalformedRecordIsIgnored() {
        storage.write(DesktopSession.fileNameFor(ID), "{ this is not json");

        Launch launch = new Launch(600, 400);

        assertTrue(launch.session.read(ID).isEmpty());
    }
}
