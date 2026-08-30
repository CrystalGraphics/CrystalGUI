package com.crystalgui.widget;

import com.crystalgui.ui.dom.NodeKinds;
import com.crystalgui.ui.dom.UINodeRegistry;
import com.crystalgui.widget.control.Button;

/**
 * <b>The widget library's kinds</b> — every {@code widget.*} node a description can decode into.
 *
 * <p>{@link NodeKinds} says why this exists rather than a {@code static {}} block on each widget:
 * a class registering itself is registered only once something has loaded it, so the registry's
 * contents become a function of what a given JVM happened to touch — which is fine for a UI built
 * in-process and wrong for one that arrives over a wire.</p>
 *
 * <p><b>One entry per widget, added in the same commit that ports it.</b> The list is the thing that
 * goes stale — the old engine's equivalent shipped saying "eighteen" while twenty were registered —
 * so {@code NodeKindsCoverageTest} fails on any class declaring a {@code NAME} that nothing here
 * names. That is the same anti-rot shape {@code WidgetContractCoverageTest} and
 * {@code StyleGovernanceTest} already use, and it is what makes a central list safe to keep.</p>
 *
 * <p>The other layers get their own — {@code chrome}, {@code desktop} and {@code workbench} each
 * declare theirs — because the point of the service is that a LAYER speaks for itself.</p>
 */
public final class Widgets implements NodeKinds {

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public Widgets() {
    }

    @Override
    public void register() {
        UINodeRegistry.register(Button.NAME, Button::new, Button.CONTRACT);
    }
}
