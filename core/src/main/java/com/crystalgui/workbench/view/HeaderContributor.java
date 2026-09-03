package com.crystalgui.workbench.view;

import com.crystalgui.ui.dom.UIElement;

/**
 * A view that puts its own controls in its container's header — IntelliJ's tool window title actions.
 *
 * <h3>Why the header and not the view's own body</h3>
 *
 * <p>IntelliJ's Problems window reads {@code Problems  [File 65]  [Project Errors]} on one line, and that
 * is not decoration: the scope tabs are <b>about the whole view</b>, so putting them inside it costs a
 * strip of the content they describe and reads as another row of the list. Every tool window that has
 * controls does this — filters, scopes and settings live on the title line beside the name.</p>
 *
 * <p>A view cannot reach its container to do that itself, and should not: it does not know whether it is
 * alone in one, sharing it with a sibling, or in a container at all. So it <em>offers</em> an element and
 * the container decides where it goes — the same direction of dependency {@code FileDocument} uses to
 * answer what it has to say without knowing what shows it.</p>
 */
public interface HeaderContributor {

    /**
     * The controls to place on the title line, or null for none.
     *
     * <p>Asked once, when the view is mounted, and the element is kept — so it must be a live element the
     * view owns rather than one built per call. A view that rebuilt it would find the container holding
     * the previous one.</p>
     */
    UIElement headerContent();
}
