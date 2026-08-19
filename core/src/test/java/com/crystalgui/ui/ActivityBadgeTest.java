package com.crystalgui.ui;

import com.crystalgui.ui.elements.workbench.ViewContainerRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The activity bar's badge — VS Code's, with the style class M9.5 §9.5.7 needed.
 *
 * <p>Registry-only, so no window: what is pinned here is the bookkeeping that decides whether the rail is
 * ever told, which is where the silent failures are.</p>
 */
public class ActivityBadgeTest {

    private static List<String> record(ViewContainerRegistry registry) {
        List<String> seen = new ArrayList<>();
        registry.onDidChangeBadge.connect((id, text) -> seen.add(id + "=" + text));
        return seen;
    }

    /** The plain form still works and carries no style, so no existing caller changes meaning. */
    @Test
    public void aPlainBadgeHasNoStyle() {
        ViewContainerRegistry registry = new ViewContainerRegistry();
        registry.setBadge("problems", "3");

        assertEquals("3", registry.badgeOf("problems"));
        assertNull(registry.badgeStyleOf("problems"));
    }

    /** A styled badge keeps both, which is what lets the rail colour one container and not the rest. */
    @Test
    public void aStyledBadgeKeepsItsClass() {
        ViewContainerRegistry registry = new ViewContainerRegistry();
        registry.setBadge("run", ViewContainerRegistry.DOT, "__running__");

        assertEquals(ViewContainerRegistry.DOT, registry.badgeOf("run"));
        assertEquals("__running__", registry.badgeStyleOf("run"));
    }

    /**
     * <b>Setting the same thing twice says nothing</b> — the rail rebuilds elements on every change, so an
     * unguarded setter would do that once per script transition, twenty times a second under a tick script.
     */
    @Test
    public void anUnchangedBadgeIsNotAnnounced() {
        ViewContainerRegistry registry = new ViewContainerRegistry();
        List<String> seen = record(registry);

        registry.setBadge("run", ViewContainerRegistry.DOT, "__running__");
        registry.setBadge("run", ViewContainerRegistry.DOT, "__running__");

        assertEquals(1, seen.size());
    }

    /**
     * <b>A change of KIND with the same text is still a change.</b>
     *
     * <p>The trap in comparing only the text: a container that is both busy and has news would keep the
     * first colour forever, because {@link ViewContainerRegistry#DOT} is the text in both cases and the
     * setter would discard the second as a no-op. Nothing about the rail would look broken — it would just
     * be the wrong colour, permanently.</p>
     */
    @Test
    public void aChangeOfStyleAloneIsStillAChange() {
        ViewContainerRegistry registry = new ViewContainerRegistry();
        registry.setBadge("run", ViewContainerRegistry.DOT, "__running__");
        List<String> seen = record(registry);

        registry.setBadge("run", ViewContainerRegistry.DOT, "__attention__");

        assertEquals("the rail has to be told", 1, seen.size());
        assertEquals("__attention__", registry.badgeStyleOf("run"));
    }

    /**
     * <b>Clearing drops the style too.</b>
     *
     * <p>The badge element is pooled rather than discarded, so a style left behind in the registry is a
     * style the next badge would inherit — a Problems count coming back wearing the last run's green.</p>
     */
    @Test
    public void clearingDropsTheStyleAsWell() {
        ViewContainerRegistry registry = new ViewContainerRegistry();
        registry.setBadge("run", ViewContainerRegistry.DOT, "__running__");
        registry.setBadge("run", null);

        assertNull(registry.badgeOf("run"));
        assertNull("a cleared badge must not keep its class", registry.badgeStyleOf("run"));
    }

    /** An empty string clears, exactly as null does — a caller computing a count should not have to check. */
    @Test
    public void anEmptyBadgeClears() {
        ViewContainerRegistry registry = new ViewContainerRegistry();
        registry.setBadge("run", ViewContainerRegistry.DOT, "__running__");
        registry.setBadge("run", "", "__running__");

        assertNull(registry.badgeOf("run"));
        assertNull(registry.badgeStyleOf("run"));
    }
}
