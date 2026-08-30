package com.crystalgui.ui.dom;

/**
 * <b>A layer's kinds, declared to the registry</b> — how {@link UINodeRegistry} learns what exists
 * without anything having touched the classes.
 *
 * <h3>The defect this exists for, which the old engine had already fixed once</h3>
 *
 * <p>A widget that registers itself from its own {@code static {}} block is registered only once
 * something has loaded that class — so the registry's contents are a function of what a given JVM
 * happened to touch. {@code ElementRegistry}'s javadoc states the consequence exactly: <i>"harmless
 * for a local UI and <b>actively wrong</b> for a serialized one: the same description would decode
 * to a real {@code Slider} on a client that had shown one earlier and to a bare element on one that
 * hadn't, with no error either way"</i>. M6.1 reintroduced the static block, and the porting guide
 * prescribed it — which would have put it in every one of the remaining widgets.</p>
 *
 * <h3>Why a service rather than the old engine's central list</h3>
 *
 * <p>{@code ElementRegistry.bootstrapBuiltins()} is one method importing twenty-three widget
 * classes, and it works because the old engine has no layering to violate. The new one does:
 * {@code ui.dom} is the ENGINE and {@code widget}/{@code chrome}/{@code desktop}/{@code workbench}
 * are above it, so a registry importing a {@code Button} is the upward reference {@code LayeringTest}
 * exists to refuse.</p>
 *
 * <p>A {@link java.util.ServiceLoader} service is the same explicitness pointing the other way: each
 * LAYER declares its own kinds, the engine discovers them by contract rather than by name, and a mod
 * shipping widgets gets exactly the door ours use rather than a list it cannot edit. The registry
 * runs them once, lazily, on the first question anybody asks it — the old engine's own arrangement,
 * where {@code create}, {@code isRegistered} and {@code tags} each begin by bootstrapping, so being
 * correct does not depend on a host remembering to call anything.</p>
 *
 * <h3>Writing one</h3>
 *
 * <pre>{@code
 * public final class Widgets implements NodeKinds {
 *     @Override public void register() {
 *         UINodeRegistry.register(Button.NAME, Button::new, Button.CONTRACT);
 *     }
 * }
 * }</pre>
 *
 * <p>and a line naming it in {@code META-INF/services/com.crystalgui.ui.dom.NodeKinds}. The
 * implementation needs a public no-argument constructor, which is {@code ServiceLoader}'s rule.</p>
 *
 * <p><b>Registering is idempotent and re-registering a name replaces it</b>, so the order services
 * are discovered in is not something a layer has to reason about — but two layers claiming one name
 * is a collision nobody would see, which is what {@code NodeKindsCoverageTest} checks.</p>
 */
public interface NodeKinds {

    /**
     * Registers this layer's kinds. Called at most once per process, from
     * {@link UINodeRegistry}'s bootstrap.
     *
     * <p>Do the registrations and nothing else: this runs from inside whatever first asked the
     * registry a question, which may be a decode on a network thread. Building anything, touching
     * a document or reading a file here would put that work on a caller that asked for a lookup.</p>
     */
    void register();
}
