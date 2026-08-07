package com.crystalgui.ui.elements.inspector;

import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UiDataKeys;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>The inspector knows no types — contributions decide what it shows.</b>
 *
 * <p>Blender's Properties editor works for a mesh, a light, a camera, a material and an add-on's own
 * datablock without knowing what any of them are: a {@code Panel} is a registered class whose
 * {@code poll(context)} decides whether it applies, and the editor draws every panel that said yes. A
 * light shows no Modifiers tab not because the editor knows what a light is, but because those panels'
 * poll returns false.</p>
 *
 * <p>These pin that property. There was a {@code ShaderGraphInspector} — a general tool with a graph in
 * its name — and the test that it is gone for good is that a subject can be made inspectable here
 * without touching {@link Inspector}.</p>
 */
public class InspectorContributionTest {

    /** Two unrelated subjects, standing in for "a graph" and "a keyframe". */
    private static final DataKey<String> ALPHA = DataKey.create("test.alpha", String.class);
    private static final DataKey<String> BETA = DataKey.create("test.beta", String.class);

    @Before
    @After
    public void resetRegistry() {
        InspectorRegistry.resetForTesting();
    }

    /** An element that answers one key — the shape every inspectable widget has. */
    private static UIElement subject(DataKey<String> key, String value) {
        return new UIElement() {
            @Override
            public Object getData(DataKey<?> asked) {
                return asked == key ? value : super.getData(asked);
            }
        };
    }

    private static InspectorSection section(String tab, DataKey<String> key, int order, List<String> built) {
        return new InspectorSection() {
            @Override public String tab() { return tab; }
            @Override public boolean accepts(DataContext context) { return context.has(key); }
            @Override public int order() { return order; }
            @Override public void build(InspectorForm form, DataContext context) {
                built.add(tab + ":" + context.get(key));
                form.header(tab);
            }
        };
    }

    // ── Resolution ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Only what applies is asked to build — Blender's {@code poll()}.</b>
     *
     * <p>The extensibility hinge. Everything shown is the union of what answered yes, so a feature adds a
     * section and nothing else changes.</p>
     */
    @Test
    public void onlySectionsThatAcceptAreBuilt() {
        List<String> built = new ArrayList<>();
        InspectorRegistry.register(section("Alpha", ALPHA, 0, built));
        InspectorRegistry.register(section("Beta", BETA, 0, built));

        Inspector inspector = new Inspector();
        inspector.inspect(subject(ALPHA, "a"));

        assertEquals(List.of("Alpha:a"), built);
        assertEquals(java.util.Set.of("Alpha"), inspector.tabNames());
    }

    /**
     * <b>Tabs come from the sections that applied, never from a fixed list.</b>
     *
     * <p>A hardcoded {@code Node}/{@code Graph} pair is the old per-type design wearing a registry.</p>
     */
    @Test
    public void tabsAreWhateverContributed() {
        List<String> built = new ArrayList<>();
        InspectorRegistry.register(section("Node", ALPHA, 0, built));
        InspectorRegistry.register(section("Graph", ALPHA, 0, built));
        InspectorRegistry.register(section("Physics", BETA, 0, built));

        Inspector inspector = new Inspector();
        inspector.inspect(subject(ALPHA, "x"));
        assertEquals(java.util.Set.of("Graph", "Node"), inspector.tabNames());

        // A different subject entirely -- and the inspector needed no change to show it.
        inspector.inspect(subject(BETA, "y"));
        assertEquals(java.util.Set.of("Physics"), inspector.tabNames());
    }

    /**
     * <b>A second, unrelated feature contributes without the inspector or the first knowing.</b>
     *
     * <p>The actual test of the design, and what "any object can hook in once focused" means.</p>
     */
    @Test
    public void twoFeaturesShareOneTabWithoutKnowingEachOther() {
        List<String> built = new ArrayList<>();
        InspectorRegistry.register(section("Item", ALPHA, 20, built));
        InspectorRegistry.register(section("Item", ALPHA, 10, built));

        new Inspector().inspect(subject(ALPHA, "z"));

        assertEquals("declared order must decide, not registration order",
                List.of("Item:z", "Item:z"), built);
        assertEquals(2, built.size());
    }

    /** Ordering is declared, so class-loading order cannot interleave two features differently. */
    @Test
    public void orderIsDeclaredNotRegistrationOrder() {
        List<InspectorSection> resolved = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        InspectorSection late = section("T", ALPHA, 1, ignored);
        InspectorSection early = section("T", ALPHA, 0, ignored);
        InspectorRegistry.register(late);
        InspectorRegistry.register(early);

        resolved.addAll(InspectorRegistry.sectionsFor(DataContext.from(subject(ALPHA, "v"))));
        assertEquals(List.of(early, late), resolved);
    }

    // ── Empty is a state, not a failure ─────────────────────────────────────────────────────────

    /**
     * <b>Nothing inspectable renders as empty, not as a framed panel with nothing in it.</b>
     *
     * <p>Blender hides a panel entirely when its poll fails. An empty framed box reads as broken.</p>
     */
    @Test
    public void aSubjectNothingDescribesIsAnEmptyState() {
        InspectorRegistry.register(section("Alpha", ALPHA, 0, new ArrayList<>()));

        Inspector inspector = new Inspector();
        inspector.inspect(subject(BETA, "unknown"));

        assertTrue(inspector.hasClass(Inspector.EMPTY_CLASS));
        assertTrue(inspector.tabNames().isEmpty());
    }

    @Test
    public void inspectingNothingIsAlsoAnEmptyState() {
        Inspector inspector = new Inspector();
        inspector.inspect((UIElement) null);
        assertTrue(inspector.hasClass(Inspector.EMPTY_CLASS));
    }

    /** A section may still decline after accepting — {@code accepts} answers about a KIND of subject. */
    @Test
    public void aSectionThatBuildsNothingContributesNoTab() {
        InspectorRegistry.register(new InspectorSection() {
            @Override public String tab() { return "Maybe"; }
            @Override public boolean accepts(DataContext context) { return true; }
            // Accepts, then contributes nothing -- accepts() answers about a KIND of subject.
            @Override public void build(InspectorForm form, DataContext context) { }
        });

        Inspector inspector = new Inspector();
        inspector.inspect(new UIElement());
        assertTrue(inspector.tabNames().isEmpty());
    }

    // ── Rebuilding ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The selected tab survives a subject change where the tab still exists.</b>
     *
     * <p>Switching between two nodes must not throw you back to the first tab — the one thing the old
     * per-graph inspector swap also got wrong.</p>
     */
    @Test
    public void theSelectedTabSurvivesASubjectChange() {
        List<String> built = new ArrayList<>();
        InspectorRegistry.register(section("Node", ALPHA, 0, built));
        InspectorRegistry.register(section("Graph", ALPHA, 1, built));

        Inspector inspector = new Inspector();
        inspector.inspect(subject(ALPHA, "first"));
        inspector.tabs().selectTab(inspector.tabs().getTabs().get(1));   // the Graph tab
        String wanted = inspector.tabs().getSelectedTab().getText();

        inspector.inspect(subject(ALPHA, "second"));

        assertEquals("switching subjects threw the user back to the first tab",
                wanted, inspector.tabs().getSelectedTab().getText());
    }

    /** Registration is idempotent, so a contribution that runs twice does not double every form. */
    @Test
    public void registeringTheSameSectionTwiceIsOnce() {
        List<String> built = new ArrayList<>();
        InspectorSection only = section("T", ALPHA, 0, built);
        InspectorRegistry.register(only);
        InspectorRegistry.register(only);

        new Inspector().inspect(subject(ALPHA, "q"));
        assertEquals(1, built.size());
    }

    /** The engine's own keys work as subjects too — nothing here is special-cased. */
    @Test
    public void anyDataKeyCanBeASubject() {
        List<String> seen = new ArrayList<>();
        InspectorRegistry.register(new InspectorSection() {
            @Override public String tab() { return "Element"; }
            @Override public boolean accepts(DataContext context) { return context.has(UiDataKeys.ELEMENT); }
            @Override public void build(InspectorForm form, DataContext context) {
                seen.add("element");
                form.header("Element");
            }
        });

        new Inspector().inspect(new UIElement());
        assertEquals(List.of("element"), seen);
    }
}
