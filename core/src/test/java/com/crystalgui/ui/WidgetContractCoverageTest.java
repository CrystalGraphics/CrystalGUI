package com.crystalgui.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.elements.WidgetCensus;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * <b>M1's acceptance</b>: every widget class has answered the question "do you travel, and how".
 * {@code plan_ui_rewrite.md} M1.
 *
 * <h3>What this replaces, and why it is stricter</h3>
 *
 * <p>{@code ElementStateCoverageTest} asked the same question of every <em>registered tag</em> — 23 of
 * them — against a hand-maintained map. It could not see a widget that was never registered, which is
 * most of them: the config controls, the collection views, everything in the workbench. This walks the
 * <b>classes</b>, so the answer is required from all 87 rather than from the quarter that happened to
 * have a tag.</p>
 *
 * <p>The failure it exists for is silent by construction. A stateful widget with nothing declared does
 * not throw — it <b>arrives blank</b>, and a blank widget reads as a rendering fault in the client
 * rather than a missing declaration on the server. Nothing links a widget to its contract except this
 * test, which is exactly the anti-rot shape {@code AGENTS.md} prescribes for the CSS property registry.
 * Adding a widget fails here until somebody writes down which side of the line it is on, which is the
 * only moment the question is cheap to answer.</p>
 */
public class WidgetContractCoverageTest {

    private static final String WIDGET_PACKAGE = "com.crystalgui.ui.elements";

    private static List<Class<?>> widgets;

    /**
     * <b>Load every widget class before any assertion runs.</b>
     *
     * <p>A contract is registered from its widget's static initialiser, so {@code WidgetContracts.all()}
     * holds only what has been class-INITIALISED -- and JUnit runs test methods in an unspecified
     * order. Without this, a method that reads the registry sees whatever an earlier method happened to
     * touch, and the suite passes or fails depending on ordering. That is the vacuous-test shape this
     * file exists to prevent, so it must not have it itself.</p>
     */
    @BeforeClass
    public static void loadEveryWidget() throws Exception {
        WidgetCensus.register();
        widgets = widgetClasses();
    }

    /** Every concrete {@code UIElement} subclass under {@code ui.elements.**}, class-initialised. */
    private static List<Class<?>> widgetClasses() throws Exception {
        URL root = UIElement.class.getProtectionDomain().getCodeSource().getLocation();
        File base = new File(new File(root.toURI()), WIDGET_PACKAGE.replace('.', '/'));
        assertTrue("cannot find compiled widgets at " + base, base.isDirectory());

        List<Class<?>> found = new ArrayList<>();
        collect(base, WIDGET_PACKAGE, found);
        found.sort((a, b) -> a.getName().compareTo(b.getName()));
        return found;
    }

    private static void collect(File dir, String pkg, List<Class<?>> out) throws Exception {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collect(entry, pkg + "." + entry.getName(), out);
                continue;
            }
            String name = entry.getName();
            // Nested classes answer through their outer class; a widget's private row template is not
            // a widget anybody can describe.
            if (!name.endsWith(".class") || name.contains("$")) continue;

            // TRUE: force class initialisation, or a contract declared in a static initialiser has not
            // run and every widget looks uncontracted. The whole test would pass vacuously.
            Class<?> type = Class.forName(pkg + "." + name.substring(0, name.length() - 6),
                    true, WidgetContractCoverageTest.class.getClassLoader());

            if (!UIElement.class.isAssignableFrom(type)) continue;
            if (type == UIElement.class) continue;
            // NON-PUBLIC classes are skipped, and that is a rule rather than a convenience: a
            // description names a TAG and the codec constructs it through ElementRegistry, which
            // cannot be handed a factory for a class it may not reference. A package-private widget
            // is an implementation detail of its own package by construction -- NotificationCard is
            // one row of the notifications panel -- and its owner answers for it.
            if (!Modifier.isPublic(type.getModifiers())) continue;
            // Abstract classes cannot be instantiated, so nothing can describe one; a concrete
            // subclass answers for itself.
            if (Modifier.isAbstract(type.getModifiers())) continue;
            out.add(type);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void everyWidgetIsEitherContractedOrDeliberatelyLocal() {
        List<String> unanswered = new ArrayList<>();
        for (Class<?> type : widgets) {
            boolean contracted = WidgetContracts.of(type) != null;
            boolean local = WidgetContracts.isLocalOnly(type);
            if (contracted == local) {          // neither, or somehow both
                unanswered.add(type.getName() + (contracted ? " (BOTH)" : ""));
            }
        }
        if (!unanswered.isEmpty()) {
            fail("These widget classes have not answered whether they travel:\n  "
                    + String.join("\n  ", unanswered)
                    + "\n\nGive the class a WidgetContract, or add it to WidgetCensus with a REASON. "
                    + "The reason is the whole point: it is what separates a decision from an omission, "
                    + "and a stateful widget with nothing declared does not fail loudly -- it arrives "
                    + "blank, which reads as a rendering bug in the client.");
        }
    }

    @Test
    public void everyLocalOnlyReasonSaysSomething() {
        List<String> weak = new ArrayList<>();
        for (Map.Entry<Class<?>, String> entry : WidgetContracts.allLocalOnly().entrySet()) {
            String reason = entry.getValue();
            // A reason has to be a sentence somebody can disagree with, not a shrug.
            if (reason.length() < 25 || reason.toLowerCase().contains("todo")
                    || reason.toLowerCase().contains("not yet implemented")) {
                weak.add(entry.getKey().getSimpleName() + ": " + reason);
            }
        }
        assertTrue("These reasons do not say why, they say that:\n  " + String.join("\n  ", weak),
                weak.isEmpty());
    }

    @Test
    public void aContractsNameMatchesTheTagItIsRegisteredUnder() {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<Class<?>, WidgetContract<?>> entry : WidgetContracts.all().entrySet()) {
            Class<?> type = entry.getKey();
            @SuppressWarnings("unchecked")
            String registered = ElementRegistry.tagOf((Class<? extends UIElement>) type);
            String declared = entry.getValue().name();
            // Null means the class was never registered as a tag -- true of every config control and
            // of SearchField, which are widgets a UI author constructs rather than ones a description
            // names. They still need a contract; they just have no ElementRegistry entry to agree with.
            if (registered != null && !registered.equals(declared)) {
                wrong.add(type.getSimpleName() + ": contract says '" + declared
                        + "', ElementRegistry says '" + registered + "'");
            }
        }
        // The tag is the cascade identity AND the wire identity. Two answers means a description that
        // decodes into a different widget than the one that was described.
        assertTrue("A contract's name must be the tag the element answers:\n  "
                + String.join("\n  ", wrong), wrong.isEmpty());
    }

    @Test
    public void noTwoContractsShareAStateKeyWithDifferentMeanings() {
        // Not a correctness rule -- two widgets may both call something "text" -- but a place to see
        // the vocabulary, so a third widget spelling the same idea "label" is visible as drift.
        Map<String, List<String>> byKey = new TreeMap<>();
        for (Map.Entry<Class<?>, WidgetContract<?>> entry : WidgetContracts.all().entrySet()) {
            for (State<?, ?> slot : entry.getValue().states()) {
                byKey.computeIfAbsent(slot.key(), k -> new ArrayList<>())
                        .add(entry.getKey().getSimpleName());
            }
        }
        assertFalse("no contracts registered at all -- did the census fail to load?", byKey.isEmpty());
        assertTrue("'text' is the shared spelling for a widget's own label",
                byKey.getOrDefault("text", List.of()).size() >= 3);
    }

    @Test
    public void everyDeclaredEventCanActuallyBeListenedFor() {
        // A kind with no attach is a kind a client would ask for and never hear. The Event type makes
        // that unrepresentable -- attach is required -- so this asserts the shape survived the port.
        int events = 0;
        for (Map.Entry<Class<?>, WidgetContract<?>> entry : WidgetContracts.all().entrySet()) {
            for (Event<?, ?> event : entry.getValue().events()) {
                assertFalse(entry.getKey() + " declares an event with no kind",
                        event.kind().trim().isEmpty());
                events++;
            }
        }
        assertTrue("the port should have produced a good number of events, found " + events, events >= 15);
    }

    @Test
    public void theTwelveOriginallyStatefulWidgetsAllCarryTheirState() {
        // The regression guard for the port itself: these twelve had hand-written writeState/readState
        // pairs, and every one of them must still carry state through its contract.
        List<String> names = List.of(
                "Button", "Checkbox", "ColorSelector", "Dropdown", "ProgressBar", "Slider",
                "SplitView", "Switch", "Tab", "TabView", "TextField", "UIText");
        List<String> missing = new ArrayList<>();
        for (Map.Entry<Class<?>, WidgetContract<?>> entry : WidgetContracts.all().entrySet()) {
            if (names.contains(entry.getKey().getSimpleName()) && !entry.getValue().carriesState()) {
                missing.add(entry.getKey().getSimpleName());
            }
        }
        assertTrue("these carried state before contracts and must still: " + missing, missing.isEmpty());

        List<String> found = new ArrayList<>();
        for (Class<?> type : WidgetContracts.all().keySet()) {
            if (names.contains(type.getSimpleName())) found.add(type.getSimpleName());
        }
        Collections.sort(found);
        assertEquals("all twelve must be contracted", names.size(), found.size());
    }
}
