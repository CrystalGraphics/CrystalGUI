package com.crystalgui.workbench.view;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.chrome.problems.ProblemsPanel;

/**
 * <b>A view's own controls land on its container's title line.</b>
 *
 * <p>The Problems panel's scope tabs — {@code File} and {@code Project Errors} — are about the whole
 * view, so they belong beside its name rather than inside it. The panel builds them and offers them
 * through {@link HeaderContributor}; the container decides where they go.</p>
 *
 * <p>Reported as the buttons simply being absent. The panel had the method and not the interface: it was
 * written during the port with a note that {@code HeaderContributor} would arrive with the container
 * that reads it, and when it did, nothing came back to declare it. A method that matches an interface it
 * does not implement is invisible to the compiler, and the container's {@code instanceof} quietly
 * answered no — so the tabs were built, held, and never placed.</p>
 */
public class HeaderControlsReachTheTitleLineTest extends UiDocumentTestBase {

    private static ViewContainer containerShowing(UIElement view) {
        ViewContainer container = new ViewContainer("problems", "Problems");
        container.setViews(List.of(new ViewContainerRegistry.ViewEntry(
                "problems", "Problems", () -> view)));
        return container;
    }

    /** The header, found the way a stylesheet finds it. */
    private static UIElement headerOf(ViewContainer container) {
        for (UIElement child : container.children()) {
            if (child.hasClass(ViewContainer.HEADER_CLASS)) return child;
        }
        return null;
    }

    /** The panel is a contributor at all — the half the compiler could not check. */
    @Test
    public void theProblemsPanelOffersItsScopeTabs() {
        ProblemsPanel panel = new ProblemsPanel();
        assertTrue("the Problems panel does not implement HeaderContributor, so its container never "
                + "asks for its tabs and the header is bare", panel instanceof HeaderContributor);
        assertNotNull("the panel offered no header controls", panel.headerContent());
    }

    /** ...and mounting it alone in a container puts them in the header. */
    @Test
    public void aLoneViewsControlsAreInTheHeader() {
        ProblemsPanel panel = new ProblemsPanel();
        ViewContainer container = containerShowing(panel);
        document.append(container);
        frame();

        UIElement tabs = panel.headerContent();
        assertNotNull("the panel offered nothing to place", tabs);
        assertNotNull("the scope tabs were never attached to anything", tabs.parentElement());
        assertSame("the scope tabs are not on the container's title line, so they are somewhere the "
                        + "user cannot see them", headerOf(container), tabs.parentElement());
    }

    /**
     * The counter-control: a view that contributes nothing puts nothing there.
     *
     * <p>Without it a container that dropped <em>any</em> element into its header would satisfy the case
     * above, and every panel would grow controls it never offered.</p>
     */
    @Test
    public void aPlainViewContributesNothing() {
        UIElement plain = new UIElement();
        ViewContainer container = containerShowing(plain);
        document.append(container);
        frame();

        UIElement header = headerOf(container);
        assertNotNull("the container has no header at all", header);
        for (UIElement child : header.children()) {
            assertNull("a view that is not a HeaderContributor had something placed for it",
                    child == plain ? "the view itself was put in the header" : null);
        }
    }
}
