package com.crystalgui.ui;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.decoration.TrackedRange;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.ui.elements.editor.TextEditor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What §17.1's tracked ranges buy the editor: a mark that is still on its word after you type.
 *
 * <p>{@code TrackedRangeTest} pins the primitive headlessly. This is the seam above it — that the editor
 * actually installs one per diagnostic, that the squiggle is drawn from it, that "go to problem" asks it
 * rather than the reported row, and that a list describing an older document is refused.</p>
 *
 * <p>In {@code test} rather than {@code headlessTest} because it needs a laid-out widget and the user-agent
 * sheet: {@code StyleSheet.DEFAULT} class-inits through {@code CgIO}, so it cannot load headlessly at all.</p>
 */
public class DiagnosticTrackingTest extends UiTestBase {

    private UIWindow window;
    private TextEditor editor;

    private TextEditor build(String text) {
        editor = new TextEditor(text);
        editor.layout(l -> l.width(320).height(200));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));

        UIElement root = new UIElement().layout(l -> l.width(320).height(240));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(640, 480);
        settle();
        return editor;
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    private static Diagnostic errorOn(int row, int fromColumn, int toColumn) {
        return Diagnostic.error(new TextPoint(row, fromColumn), new TextPoint(row, toColumn), "bad");
    }

    /** The text a range currently covers — the only honest way to ask "is it still on the right word". */
    private String covered(TrackedRange range) {
        return editor.buffer().document().slice(range.from(), range.to()).toString();
    }

    // ── Installation ────────────────────────────────────────────────────────────────────────────

    @Test
    public void everyDiagnosticGetsATrackedRange() {
        build("int a = 1;\nundefinedName();\n");
        Diagnostic problem = errorOn(1, 0, 13);
        editor.diagnostics().setAll(List.of(problem));

        TrackedRange range = editor.trackedRangeFor(problem);
        assertNotNull("no range was installed for the diagnostic", range);
        assertEquals("undefinedName", covered(range));
    }

    /**
     * A producer that writes the set directly is tracked too.
     *
     * <p>The reason the tracking hangs off {@code DiagnosticSet.onChanged} rather than off the engine's
     * push: the shader graph writes four owners of its own on every compile and has no version to offer.
     * Wiring it to the engine instead would have left every non-engine producer silently untracked, which
     * is the failure mode that looks like the feature working.</p>
     */
    @Test
    public void aProducerThatNeverGoesThroughAnEngineIsTrackedAsWell() {
        build("alpha\nbeta\ngamma");
        Diagnostic fromElsewhere = errorOn(2, 0, 5);
        editor.diagnostics().changeOne("shader-graph", List.of(fromElsewhere));

        TrackedRange range = editor.trackedRangeFor(fromElsewhere);
        assertNotNull(range);
        assertEquals("gamma", covered(range));
    }

    // ── The defect this exists to fix ───────────────────────────────────────────────────────────

    /**
     * The whole point, stated as the bug report it would have arrived as.
     *
     * <p>A squiggle under {@code undefinedName}; a line is added above it; the squiggle stays where it was
     * — which is now the middle of a different line. It corrects itself when the recompile lands 300ms
     * later, which is exactly what made it read as the analyser lagging rather than as a broken mark.</p>
     */
    @Test
    public void aMarkFollowsItsWordWhenTextIsInsertedAboveIt() {
        build("int a = 1;\nundefinedName();\n");
        Diagnostic problem = errorOn(1, 0, 13);
        editor.diagnostics().setAll(List.of(problem));

        editor.buffer().insert(0, "// added above\n");
        settle();

        assertEquals("undefinedName", covered(editor.trackedRangeFor(problem)));
    }

    @Test
    public void aMarkGrowsWhenTheWordUnderItIsExtended() {
        build("undefinedName();\n");
        Diagnostic problem = errorOn(0, 0, 13);
        editor.diagnostics().setAll(List.of(problem));

        // Typing at the very end of the underlined token. ALWAYS_GROWS is what makes the new character part
        // of the same mistake instead of leaving the squiggle one character short of the word it is about.
        editor.buffer().insert(13, "X");
        settle();

        assertEquals("undefinedNameX", covered(editor.trackedRangeFor(problem)));
    }

    @Test
    public void goToProblemLandsOnTheWordAsItIsNowNotAsItWasReported() {
        build("int a = 1;\nundefinedName();\n");
        Diagnostic problem = errorOn(1, 0, 13);
        editor.diagnostics().setAll(List.of(problem));

        editor.buffer().insert(0, "// added above\n");
        settle();

        assertTrue(editor.goToNextProblem());
        int caret = editor.getCaret();
        assertEquals("the caret must land on the word, not on the reported row",
                editor.buffer().toString().indexOf("undefinedName"), caret);
    }

    // ── The version gate ────────────────────────────────────────────────────────────────────────

    /**
     * A list computed against an older document is refused outright.
     *
     * <p>Not merely mis-positioned — <em>refused</em>, because the problems themselves may no longer exist.
     * The gate is at the point of entry so one rule covers the squiggles and the Problems panel together;
     * the debounced, keyed job behind it brings a fresh list, so nothing is starved by the refusal.</p>
     */
    @Test
    public void aStaleAnnouncementIsDiscardedRatherThanShown() {
        build("int a = 1;\n");
        StubServices services = new StubServices();
        editor.setLanguageServices(services);

        // Analysed at the version the document had a moment ago...
        long staleVersion = editor.buffer().version();
        editor.buffer().insert(0, "// the user kept typing\n");
        settle();

        services.announce(Versioned.of(staleVersion, List.of(errorOn(0, 0, 3))));
        settle();

        assertTrue("a list about an older document must not be shown",
                editor.diagnostics().all().isEmpty());
    }

    @Test
    public void aCurrentAnnouncementIsShown() {
        build("int a = 1;\n");
        StubServices services = new StubServices();
        editor.setLanguageServices(services);

        services.announce(Versioned.of(editor.buffer().version(), List.of(errorOn(0, 0, 3))));
        settle();

        assertEquals(1, editor.diagnostics().all().size());
        assertEquals("stub", editor.diagnostics().owners().iterator().next());
    }

    /** An engine that reports whatever it is told to, so the gate can be exercised without a compiler. */
    private static final class StubServices implements LanguageServices {

        private final List<Consumer<Versioned<List<Diagnostic>>>> listeners = new ArrayList<>();

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public Connection onDiagnostics(Consumer<Versioned<List<Diagnostic>>> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        void announce(Versioned<List<Diagnostic>> announcement) {
            for (Consumer<Versioned<List<Diagnostic>>> listener : new ArrayList<>(listeners)) {
                listener.accept(announcement);
            }
        }

        @Override
        public void close() {
        }
    }
}
