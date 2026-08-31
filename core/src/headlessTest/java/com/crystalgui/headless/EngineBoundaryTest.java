package com.crystalgui.headless;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/**
 * <b>The strangler line.</b> The second engine and the first cannot reach each other, and this is
 * the test that says so — {@code plan_m5.md} §2, rule 1.
 *
 * <p>M5 builds a node tree ({@code ui.dom}), a box tree ({@code ui.box}) and the services over them
 * ({@code ui.service}) beside the old engine, which stays runnable and untouched until M6 ports the
 * widgets across (D2). Nothing under the new packages may name {@code UIElement}, {@code UIWindow},
 * {@code TopLayer}, {@code UIInputHandler}, a widget or the S2 prototype; and nothing outside them may
 * name the new tree. A convention would hold for a week. A bytecode scan holds: a class that names a
 * type at all can reach it, whatever its imports say.</p>
 *
 * <p>The seam is the one thing both sides share on purpose: {@code ui.dom.TreeSource},
 * {@code TreeObserver} and {@code NodeContract} are the contract the mirror is written against, and
 * {@code ElementTreeSource} is the old engine's implementation of it — so the old side may name those
 * four and nothing else in {@code ui.dom}.</p>
 *
 * <p>Written before any class it could catch, against a deliberately planted import that was then
 * removed — a scan that has never fired is not known to be a scan. The negative control below keeps
 * it honest without the plant: the same detector run over the old engine finds {@code UIElement}
 * everywhere.</p>
 */
public class EngineBoundaryTest {

    /** The new engine. */
    private static final List<String> NEW_PACKAGES = List.of(
            "com/crystalgui/ui/box/",
            "com/crystalgui/ui/service/",
            // THE PORT'S DESTINATIONS (plan_m6.md §2.6). Listed from 6.1, before three of the four
            // exist: they only ever hold ported code, so admitting them early costs nothing and
            // means the first class to land in one is not also the commit that discovers the list
            // needed updating. The OLD widget layer keeps its own prefix
            // (`com/crystalgui/ui/elements/`) in OLD_ENGINE below, so the two never overlap.
            "com/crystalgui/widget/",
            "com/crystalgui/chrome/",
            "com/crystalgui/desktop/",
            "com/crystalgui/workbench/",
            // THE APPLICATIONS (6.4). Without this the shader graph is classified as OLD engine, and
            // every reference it makes into `widget` reads as the old engine reaching into the new
            // one -- which is the opposite of what it is.
            "com/crystalgui/app/");

    /**
     * The classes in {@code ui/dom} that are the SEAM rather than the node tree — everything else in
     * that package is new engine.
     *
     * <p><b>Listed the other way round on purpose, and it was a list of the node tree's classes
     * until M6.0.</b> That list broke twice in one session and was silent both times: a rename does
     * not touch a string literal, so every renamed class fell out of it and was reclassified as OLD
     * engine, and the scan failed naming its own subjects; then adding one class to the package
     * failed it again. The seam is four types that exist to be stable — this is the half that does
     * not grow, so listing it is the half that does not rot.</p>
     */
    private static final List<String> DOM_SEAM_CLASSES = List.of(
            "com/crystalgui/ui/dom/TreeSource",
            "com/crystalgui/ui/dom/TreeObserver",
            "com/crystalgui/ui/dom/NodeContract",
            // The old engine's implementation of the seam, over UIElement. Deleted at M6.9.
            "com/crystalgui/ui/dom/ElementTreeSource");

    /**
     * New-engine classes OUTSIDE {@code ui/dom} that are not covered by a package prefix.
     *
     * <p>One entry: the mirror is written once against the seam and has one implementation per
     * engine, so the node tree's sits in {@code net/mirror} beside the old engine's.</p>
     */
    private static final List<String> NEW_CLASSES = List.of(
            "com/crystalgui/net/mirror/UINodeMirror");

    /** What the new engine must never name. */
    private static final List<String> OLD_ENGINE = List.of(
            "com/crystalgui/ui/UIElement",
            "com/crystalgui/ui/UIWindow",
            "com/crystalgui/ui/TopLayer",
            "com/crystalgui/ui/Ui",
            "com/crystalgui/ui/UIResizer",
            "com/crystalgui/ui/AnchoredPlacement",
            // NOT EventListenerGroup or ui.event: D5.6 made them generic in what a listener is
            // attached to, so both engines share one set of event types and one listener group.
            "com/crystalgui/ui/ElementRegistry",
            // NOT all of ui/input/: FocusPolicy and FocusSource are pure enums with no element in
            // them, and ButtonState is multi-click arithmetic. The new focus service is written
            // against the SAME FocusPolicy deliberately -- two copies of an enum whose four values
            // are documented at length is exactly how two definitions drift.
            "com/crystalgui/ui/input/UIInputHandler",
            "com/crystalgui/ui/input/UIDragController",
            "com/crystalgui/ui/input/DragScrub",
            // NOT ui/input/keymap ANY MORE. It was old-engine because Keymap and KeymapResolver
            // took a UIElement -- and M6.3 retyped the whole command layer onto CommandTarget and
            // KeymapScope, which is what unblocked ContextMenu, MenuBuilder and the inspector out of
            // 6.2. Nothing in the package names an old-engine type now, and both engines implement
            // the seam: exactly the carve-out FocusPolicy already has one line above, and for the
            // same reason -- two copies of a seam are how two definitions drift.
            "com/crystalgui/ui/elements/",
            "com/crystalgui/ui/shadow/",
            // NOT ElementStyle: D5.2 shares the cascade's store between engines behind Styleable.
            // NOT TaffyBridge: 5.3's BoxStyle reuses its VALUE CONVERSIONS. The old engine's listener
            // path into it (LayoutProperties.createSetter) is what stays old-engine, and that is a
            // method, not a type -- the box tree maps a ComputedStyle in one call and never listens.
            "com/crystalgui/ui/dom/ElementTreeSource");

    private static boolean isNewEngine(String relativeClassPath) {
        for (String prefix : NEW_PACKAGES) {
            if (relativeClassPath.startsWith(prefix)) return true;
        }
        for (String name : NEW_CLASSES) {
            if (named(relativeClassPath, name)) return true;
        }
        // Everything in ui/dom is the node tree EXCEPT the seam, which both engines share.
        if (relativeClassPath.startsWith("com/crystalgui/ui/dom/")) {
            for (String seam : DOM_SEAM_CLASSES) {
                if (named(relativeClassPath, seam)) return false;
            }
            return true;
        }
        return false;
    }

    /** Whether a class file is {@code name}, or one of its nested classes. */
    private static boolean named(String relativeClassPath, String name) {
        return relativeClassPath.equals(name + ".class") || relativeClassPath.startsWith(name + "$");
    }

    @Test
    public void theEngineIsReachableAtAll() {
        Path root = ClassReferences.mainClassesRoot(EngineBoundaryTest.class);
        assertTrue("cannot find the module's compiled classes at " + root, Files.isDirectory(root));
        assertTrue(Files.isDirectory(root.resolve("com/crystalgui/ui")));
    }

    @Test
    public void theNewEngineNamesNothingOfTheOld() throws IOException {
        Path root = ClassReferences.mainClassesRoot(EngineBoundaryTest.class);
        List<String> offences = ClassReferences.offences(
                root, root.resolve("com/crystalgui/ui"), EngineBoundaryTest::isNewEngine, OLD_ENGINE);
        assertTrue("the new engine reaches into the old one:\n" + String.join("\n", offences),
                offences.isEmpty());
    }

    @Test
    public void theOldEngineNamesNothingOfTheNew() throws IOException {
        Path root = ClassReferences.mainClassesRoot(EngineBoundaryTest.class);
        List<String> forbidden = new java.util.ArrayList<>(NEW_PACKAGES);
        forbidden.add("com/crystalgui/ui/dom/UI");
        forbidden.addAll(NEW_CLASSES);
        List<String> offences = ClassReferences.offences(
                root, root.resolve("com/crystalgui"), path -> !isNewEngine(path), forbidden);
        assertTrue("the old engine reaches into the new one:\n" + String.join("\n", offences),
                offences.isEmpty());
    }

    /** What a class may not DO with the cascade: write at the origin an author's {@code !important} lives at. */
    private static final List<String> ENGINE_WRITES = List.of(
            "com/crystalgui/style/StyleOrigin.IMPORTANT",
            "com/crystalgui/style/StyleGroup.importantPipeline");

    /**
     * <b>The engine writes nothing into the cascade</b> — plan_m5.md §2, rule 3. Placement, stacking,
     * visibility, opacity and animation are box properties; an {@code IMPORTANT} write from engine
     * code is how the old cascade became the only mutable box model it had (audit §3: 46 files).
     */
    @Test
    public void theNewEngineWritesNothingIntoTheCascade() throws IOException {
        Path root = ClassReferences.mainClassesRoot(EngineBoundaryTest.class);
        List<String> offences = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.walk(root.resolve("com/crystalgui"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toArray(Path[]::new)) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!isNewEngine(relative)) continue;
                for (String member : ClassReferences.memberReferencesOf(file)) {
                    if (ENGINE_WRITES.contains(member)) offences.add(relative + " uses " + member);
                }
            }
        }
        assertTrue("the new engine writes into the cascade:\n" + String.join("\n", offences), offences.isEmpty());
    }

    @Test
    public void andTheOldEngineDOESWriteAtImportant() throws IOException {
        // The negative control for the rule above: UIText's geometry feedback is an IMPORTANT write.
        Path root = ClassReferences.mainClassesRoot(EngineBoundaryTest.class);
        java.util.Set<String> members = ClassReferences.memberReferencesOf(
                root.resolve("com/crystalgui/ui/elements/UIText.class"));
        assertTrue("the member scan found no importantPipeline call in UIText -- it is not detecting anything",
                members.contains("com/crystalgui/style/StyleGroup.importantPipeline"));
    }

    @Test
    public void andTheOldEngineDOESNameUIElement() throws IOException {
        // The negative control: the widgets are written against UIElement, so the same detector run
        // there must fire. A scan that finds nothing anywhere is not a scan.
        Path root = ClassReferences.mainClassesRoot(EngineBoundaryTest.class);
        List<String> offences = ClassReferences.offences(
                root, "com/crystalgui/ui/elements/", List.of("com/crystalgui/ui/UIElement"));
        assertFalse("the scan found no UIElement reference even under ui/elements -- it is not "
                + "detecting anything", offences.isEmpty());
    }
}
