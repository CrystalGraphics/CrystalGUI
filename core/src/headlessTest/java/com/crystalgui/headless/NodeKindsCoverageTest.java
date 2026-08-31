package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UINodeRegistry;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * <b>Every kind a class declares is one the registry knows about</b> — the anti-rot half of
 * {@link com.crystalgui.ui.dom.NodeKinds}.
 *
 * <h3>The defect, which this codebase has now had twice</h3>
 *
 * <p>A widget that registers itself from its own {@code static {}} block is registered only once
 * something has loaded that class — so the registry's contents are a function of what a given JVM
 * happened to touch. {@code ElementRegistry}'s javadoc states the consequence exactly: <i>"harmless
 * for a local UI and <b>actively wrong</b> for a serialized one: the same description would decode to
 * a real {@code Slider} on a client that had shown one earlier and to a bare element on one that
 * hadn't, with no error either way"</i>. The old engine found it, fixed it, and wrote it down; M6.1
 * reintroduced it, and the porting guide prescribed it — so it would have reached every remaining
 * widget rather than one.</p>
 *
 * <p>The fix is a per-LAYER {@code NodeKinds} service, and the fix's own weakness is that a list is
 * a thing to forget to add to. Which is what this is for: it walks the ported tree for classes
 * declaring a {@code NAME}, and fails on any the bootstrapped registry does not answer for. Same
 * shape as {@code WidgetContractCoverageTest} and {@code StyleGovernanceTest}, and for the same
 * reason — a central list is safe exactly as long as something checks it.</p>
 */
public class NodeKindsCoverageTest {

    /** Where ported widgets live. A class outside these is not a kind and declares no {@code NAME}. */
    private static final List<String> PORTED_LAYERS = List.of(
            "com/crystalgui/widget",
            "com/crystalgui/chrome",
            "com/crystalgui/desktop",
            "com/crystalgui/workbench");

    /**
     * Every {@link UINode} subclass under a ported layer that declares a {@code public static final
     * Name NAME}.
     */
    private static Map<Class<?>, Name> declaredKinds() throws IOException {
        Path root = ClassReferences.mainClassesRoot(NodeKindsCoverageTest.class);
        Map<Class<?>, Name> kinds = new LinkedHashMap<>();
        for (String layer : PORTED_LAYERS) {
            Path dir = root.resolve(layer);
            if (!Files.isDirectory(dir)) continue;
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.toList()) {
                    String file = p.getFileName().toString();
                    if (!file.endsWith(".class") || file.contains("$")) continue;
                    String binary = root.relativize(p).toString()
                            .replace('\\', '/').replaceAll("\\.class$", "").replace('/', '.');
                    Class<?> type;
                    try {
                        type = Class.forName(binary, false, NodeKindsCoverageTest.class.getClassLoader());
                    } catch (ClassNotFoundException | LinkageError e) {
                        continue;
                    }
                    if (!UINode.class.isAssignableFrom(type)) continue;
                    Name name = nameOf(type);
                    if (name != null) kinds.put(type, name);
                }
            }
        }
        return kinds;
    }

    /**
     * This class's {@code NAME}, or null if it declares none — read through <b>one</b> field
     * resolution, never {@code getDeclaredFields()}.
     *
     * <p>The distinction is the difference between this walk working and not. {@code
     * getDeclaredFields()} builds a {@code Field} for every declared field, which resolves every
     * field's <em>type</em> — so a widget retaining a {@code CgShapedParagraph} or a {@code
     * CgFontFamily} throws {@code NoClassDefFoundError} here, on a classpath where CrystalGraphics
     * core is absent <b>by design</b>. That is the same field-descriptor rule {@code AGENTS.md}
     * records for {@code UIElement}'s Taffy and JOML fields, met from the reflection end: a method
     * body's reference is lazy, a field's is not.</p>
     *
     * <p>{@code findStaticGetter} resolves the one field it is asked for, so a text widget is
     * discovered by this walk without its paragraph ever being named. Swapping it back for the
     * obvious loop does not fail loudly — it drops every such widget out of the coverage set, which
     * is precisely the class of widget most likely to be forgotten.</p>
     */
    private static Name nameOf(Class<?> type) {
        try {
            return (Name) MethodHandles.lookup()
                    .findStaticGetter(type, "NAME", Name.class)
                    .invoke();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A kind a class declares but no layer's service registers.
     *
     * <p>What it prevents is not a crash: an unregistered kind decodes to nothing at all, so a
     * networked panel arrives missing one widget and everything around it looks correct.</p>
     */
    @Test
    public void everyDeclaredKindIsRegistered() throws IOException {
        Map<Class<?>, Name> declared = declaredKinds();
        if (declared.isEmpty()) return;

        List<String> missing = new ArrayList<>();
        for (Map.Entry<Class<?>, Name> entry : declared.entrySet()) {
            // isRegistered() bootstraps, which is the point: nothing here has touched the widget
            // classes on purpose -- if the answer depended on that, the test would be asserting
            // its own imports rather than the classpath.
            if (!UINodeRegistry.isRegistered(entry.getValue())) {
                missing.add(entry.getKey().getName() + " declares " + entry.getValue()
                        + " -- add it to its layer's NodeKinds service");
            }
        }
        assertTrue(String.join("\n", missing), missing.isEmpty());
    }

    /** And the registered factory builds the class that declared the name, not some other one. */
    @Test
    public void aRegisteredKindBuildsTheClassThatDeclaredIt() throws IOException {
        Map<Class<?>, Name> declared = declaredKinds();
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<Class<?>, Name> entry : declared.entrySet()) {
            if (!UINodeRegistry.isRegistered(entry.getValue())) continue;
            UINode built = UINodeRegistry.create(entry.getValue());
            if (!entry.getKey().isInstance(built)) {
                wrong.add(entry.getValue() + " builds a " + built.getClass().getSimpleName()
                        + ", but " + entry.getKey().getSimpleName() + " declares that name");
            }
        }
        assertTrue(String.join("\n", wrong), wrong.isEmpty());
    }

    /**
     * The bootstrap runs without anything having touched a widget class — which is the whole claim.
     *
     * <p>If this passes only because another test in the same JVM constructed a {@code Button}, it is
     * asserting nothing. It cannot be made airtight in a shared JVM, so it asks the narrowest thing
     * that is still meaningful: the registry answers for a kind whose class is NOT named anywhere in
     * this file.</p>
     */
    @Test
    public void theRegistryAnswersForAKindThisTestNeverMentions() {
        Set<String> locals = new LinkedHashSet<>();
        for (Name name : UINodeRegistry.names()) locals.add(name.local());
        assertTrue("the three built-ins are always there", locals.containsAll(
                List.of("element", "slot", "document")));
        assertTrue("and a LAYER's kinds arrived through the service, with nothing having "
                + "constructed one: " + locals, locals.size() > 3);
    }

    /** The service file exists and names something loadable — the one non-code half of the wiring. */
    @Test
    public void theServiceFileNamesLoadableImplementations() throws IOException {
        Path root = ClassReferences.mainClassesRoot(NodeKindsCoverageTest.class);
        Path services = root.getParent().getParent().getParent()
                .resolve("resources/main/META-INF/services/com.crystalgui.ui.dom.NodeKinds");
        if (!Files.isRegularFile(services)) {
            // Built by a task that has not run, or a layout this test does not know. The registry
            // half is covered above; this one is about the file being wrong rather than absent.
            return;
        }
        List<String> bad = new ArrayList<>();
        int named = 0;
        for (String line : Files.readAllLines(services)) {
            String name = line.trim();
            if (name.isEmpty() || name.startsWith("#")) continue;
            named++;
            try {
                Class<?> type = Class.forName(name, false, getClass().getClassLoader());
                if (!com.crystalgui.ui.dom.NodeKinds.class.isAssignableFrom(type)) {
                    bad.add(name + " does not implement NodeKinds");
                }
            } catch (ClassNotFoundException e) {
                bad.add(name + " is not on the classpath");
            }
        }
        assertTrue(String.join("\n", bad), bad.isEmpty());
        assertEquals("a service file naming nothing is the same as no service file", true, named > 0);
    }
}
