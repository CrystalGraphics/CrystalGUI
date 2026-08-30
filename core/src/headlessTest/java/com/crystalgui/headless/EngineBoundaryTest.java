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
            "com/crystalgui/ui/service/");

    /**
     * The node tree's classes inside {@code ui/dom}, beside the seam. Listed by name because the seam
     * interfaces in the same directory are shared by design; a package prefix would forbid the wrong
     * thing.
     */
    private static final List<String> NEW_DOM_CLASSES = List.of(
            "com/crystalgui/ui/dom/Node",
            "com/crystalgui/ui/dom/Document",
            "com/crystalgui/ui/dom/ShadowTree",
            "com/crystalgui/ui/dom/Slot",
            "com/crystalgui/ui/dom/Name",
            "com/crystalgui/ui/dom/Attribute",
            "com/crystalgui/ui/dom/NodeTreeSource",
            "com/crystalgui/ui/dom/NodeRegistry",
            "com/crystalgui/ui/dom/Lifecycle");

    /** What the new engine must never name. */
    private static final List<String> OLD_ENGINE = List.of(
            "com/crystalgui/ui/UIElement",
            "com/crystalgui/ui/UIWindow",
            "com/crystalgui/ui/TopLayer",
            "com/crystalgui/ui/Ui",
            "com/crystalgui/ui/UIResizer",
            "com/crystalgui/ui/AnchoredPlacement",
            "com/crystalgui/ui/EventListenerGroup",
            "com/crystalgui/ui/ElementRegistry",
            "com/crystalgui/ui/input/",
            "com/crystalgui/ui/elements/",
            "com/crystalgui/ui/shadow/",
            "com/crystalgui/ui/dom/ElementTreeSource",
            "com/crystalgui/style/ElementStyle",
            "com/crystalgui/style/TaffyBridge");

    private static boolean isNewEngine(String relativeClassPath) {
        for (String prefix : NEW_PACKAGES) {
            if (relativeClassPath.startsWith(prefix)) return true;
        }
        for (String name : NEW_DOM_CLASSES) {
            if (relativeClassPath.equals(name + ".class") || relativeClassPath.startsWith(name + "$")) {
                return true;
            }
        }
        return false;
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
        forbidden.addAll(NEW_DOM_CLASSES);
        List<String> offences = ClassReferences.offences(
                root, root.resolve("com/crystalgui"), path -> !isNewEngine(path), forbidden);
        assertTrue("the old engine reaches into the new one:\n" + String.join("\n", offences),
                offences.isEmpty());
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
