package com.crystalgui.desktop.app;

/**
 * <b>A layer's applications, declared to the desktop</b> — how {@link ApplicationRegistry} learns what
 * is installed without any host having named a product.
 *
 * <h3>The defect this exists for</h3>
 *
 * <p>An application that a HOST installs is installed only on the hosts that remembered to — so what a
 * desktop offers is a function of which loader you launched, not of what is on the classpath. That is
 * the same failure {@link com.crystalgui.ui.dom.NodeKinds} was written for one layer down, and the same
 * one {@code WorkbenchExtensions} records for file types: the Notes kind was registered by two harness
 * scenes and by no loader, so a type shipped in this repository opened in the harness and not in the
 * game. Three hosts each calling {@code install(...)} is that shape waiting to happen a fourth time,
 * and it also makes a mod's application strictly second-class — it would need a line in code it does
 * not own.</p>
 *
 * <p>A manifest is <b>data</b>, and discovering it is what makes that true: a jar on the classpath
 * offers its products, and the desktop lists them.</p>
 *
 * <h3>Writing one</h3>
 *
 * <pre>{@code
 * public final class Applications implements ApplicationKinds {
 *     @Override public void register(ApplicationRegistry applications) {
 *         applications.install(CrystalEditor.KIND);
 *     }
 * }
 * }</pre>
 *
 * <p>and a line naming it in {@code META-INF/services/com.crystalgui.desktop.app.ApplicationKinds}. The
 * implementation needs a public no-argument constructor, which is {@code ServiceLoader}'s rule.</p>
 *
 * <h3>Discovery is per process; installing is per DESKTOP</h3>
 *
 * <p>Which is why this takes the registry rather than answering a list. Two desktops in one
 * installation — a game client and a dedicated tool — are two shells, and each runs the services
 * against its own registry: same products, separate running instances, separate arrangement records.
 * {@link ApplicationRegistry#install} stays public beside it for the case a service cannot serve, which
 * is a manifest built at run time from something only the running process knows.</p>
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
