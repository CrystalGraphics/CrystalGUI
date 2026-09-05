package com.crystalgui.desktop.app;

/**
 * The seam a jar declares its applications through — implement it and the desktop lists your product.
 *
 * <p>Nothing installs applications by hand. Every {@code ApplicationKinds} on the classpath is found by
 * {@link ApplicationRegistry#bootstrap()} and run once against that desktop's registry, so
 * <b>shipping the jar is what offers the product</b>. It is the same arrangement
 * {@link com.crystalgui.ui.dom.NodeKinds} uses for widget tags one layer down.</p>
 *
 * <h3>Writing one</h3>
 *
 * <pre>{@code
 * public final class Applications implements ApplicationKinds {
 *     @Override public void register(ApplicationRegistry applications) {
 *         applications.install(MyEditor.KIND);
 *     }
 * }
 * }</pre>
 *
 * <p>then name it in {@code META-INF/services/com.crystalgui.desktop.app.ApplicationKinds}. It needs a
 * public no-argument constructor, which is {@code ServiceLoader}'s rule. A service that throws costs its
 * own products and nothing else — the desktop still comes up.</p>
 *
 * <h3>Why it takes the registry rather than returning a list</h3>
 *
 * <p>Discovery is per <em>process</em> and installing is per <em>desktop</em>. Two desktops in one
 * installation are two shells: each runs the services against its own registry, so they offer the same
 * products with separate running instances and separate arrangement records.
 * {@link ApplicationRegistry#install} stays public beside this for the one case a service cannot serve —
 * a manifest built at run time from something only the running process knows.</p>
 */
public interface ApplicationKinds {

    /**
     * Installs this layer's applications into {@code applications}. Called once per registry, from its
     * own bootstrap.
     *
     * <p>Install and nothing else: this runs from inside whatever first asked the registry a question,
     * which may be a launcher drawing a list. <b>Launching anything here would build a product to answer
     * "what products are there"</b> — the exact inversion the manifest exists to remove.</p>
     */
    void register(ApplicationRegistry applications);
}
