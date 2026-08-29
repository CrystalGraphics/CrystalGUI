package com.crystalgui.ui.shadow;

import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.crystalgui.ui.UIElement;

/**
 * <b>Spike S2</b> — a shadow root, as far as one is needed to answer whether the three-tree engine's
 * encapsulation model is the right shape. {@code plan_ui_rewrite.md} M0.
 *
 * <h3>What this is measuring</h3>
 *
 * <p>Today a composite hides its parts behind {@link UIElement#markAsInternal()} — a boolean, checked
 * by public traversal and by the description codec, and <b>invisible to the cascade</b>. So
 * {@code default.css} styles all 54 composites through {@code __double-underscore__} class names, and
 * a rule anywhere can reach any widget's insides. The engine audit calls that "encapsulation by flag"
 * and it is the source of a documented family of bugs: {@code .__content__} named by three unrelated
 * widgets, {@code .__view-container__ .__content__} zeroing every {@code ConfiguratorGroup} in the
 * application, an adopted header coming home still carrying its host's padding.</p>
 *
 * <p>A shadow root is the web's answer: the subtree is a <b>separate scope</b>, outer rules do not
 * reach it, and a widget exposes exactly the pieces it chooses to, by name, through {@code ::part}.
 * The question S2 exists to answer is not whether that is desirable — it is what it costs here, in a
 * cascade whose rule index is keyed on the element being styled.</p>
 *
 * <h3>What it deliberately is not</h3>
 *
 * <p>Not the web's shadow DOM. There are no slots, no flat tree, no composed events, no
 * {@code mode: open/closed}, no {@code :host} or {@code ::slotted}. Those belong to {@code ui.dom}
 * at M5 if this shape survives. This is the smallest thing that can carry a real {@code Button}
 * through a real cascade and a real focus walk, so the three questions get measured answers instead
 * of estimates.</p>
 *
 * <h3>Why a side table rather than fields on {@code UIElement}</h3>
 *
 * <p>{@code UIElement} is 3,308 lines across 22 concerns and the rewrite exists to reverse that; a
 * spike that adds a 23rd would be arguing against its own conclusion. The lookups here are on the
 * cascade's hot path, which is exactly what makes their cost worth reporting — see
 * {@link #hostOf(UIElement)}.</p>
 */
public final class ShadowRoot extends UIElement {

    /**
     * element → its {@code part} name. A {@link WeakHashMap} so a discarded subtree is collectable;
     * identity-keyed by {@code UIElement}'s inherited identity equality.
     */
    private static final Map<UIElement, String> PARTS = new WeakHashMap<>();

    /** Cache of {@link #hostOf(UIElement)}, which would otherwise walk to the root for every miss. */
    private static final Map<UIElement, UIElement> HOST_CACHE = new WeakHashMap<>();

    /** The element this shadow root is attached to. Never null — a root without a host is meaningless. */
    private final UIElement host;

    private ShadowRoot(UIElement host) {
        this.host = host;
    }

    /**
     * Attaches a shadow root to {@code host} and returns it. The web's
     * {@code Element.attachShadow({mode: "open"})}.
     *
     * <p>The root is an <em>internal</em> child, which is what keeps it out of public traversal and out
     * of the description codec today. That is the one place this prototype leans on the mechanism it is
     * proposing to replace, and it is deliberate: changing both at once would leave the measurement
     * unattributable.</p>
     */
    public static ShadowRoot attachTo(UIElement host) {
        ShadowRoot root = new ShadowRoot(host);
        host.addInternalChild(root);
        HOST_CACHE.clear();
        return root;
    }

    public UIElement host() {
        return host;
    }

    /**
     * Exposes {@code element} to the host's stylesheet under {@code name}, so {@code host::part(name)}
     * reaches it. The web's {@code part="name"} content attribute.
     *
     * @return {@code element}, so this reads as a decoration at the point of construction
     */
    public static <E extends UIElement> E part(E element, String name) {
        PARTS.put(element, name);
        return element;
    }

    /** The {@code part} name {@code element} was exposed under, or null. */
    @Nullable
    public static String partOf(UIElement element) {
        return PARTS.get(element);
    }

    /**
     * The shadow host {@code element} lives under, or null when it is in the light tree.
     *
     * <p><b>This is the cost S2 is measuring.</b> The cascade's rule index is keyed on the element being
     * styled, so a {@code ::part} rule — indexed under the <em>host's</em> type and classes — cannot be
     * found from the element it applies to without first finding that host. Answering it means walking
     * ancestors, and the cascade asks once per element per rematch.</p>
     *
     * <p>Cached, because the walk is O(depth) and the answer changes only on a reparent. The cache is
     * cleared wholesale on any attach; a real implementation stores the root on the node, which is what
     * {@code ui.dom} will do — the walk exists here only to avoid touching {@code UIElement}.</p>
     */
    @Nullable
    public static UIElement hostOf(UIElement element) {
        UIElement cached = HOST_CACHE.get(element);
        if (cached != null) return cached;

        for (UIElement at = element; at != null; at = at.getParent()) {
            if (at instanceof ShadowRoot) {
                UIElement found = ((ShadowRoot) at).host;
                HOST_CACHE.put(element, found);
                return found;
            }
        }
        return null;
    }

    /**
     * Whether {@code element} is inside a shadow tree at all — the encapsulation predicate.
     *
     * <p>Separate from {@link #hostOf} having a null answer only in intent: a caller asking this is
     * asking "may an outer rule reach here", and one asking {@code hostOf} is asking "whose parts index
     * should I consult". They happen to coincide; the two questions do not.</p>
     */
    public static boolean isInShadowTree(UIElement element) {
        return hostOf(element) != null;
    }

    /**
     * <b>Focus retargeting</b> — the answer an outside observer should get for a focused element.
     *
     * <p>The web's rule: {@code document.activeElement} reports the host, not what is focused inside it,
     * so a shadow tree cannot leak its internals through the focus API. Here that matters for a reason
     * more concrete than encapsulation, and it is the third thing S2 measures: this engine resolves
     * <b>commands</b> outward from the focused element, and a {@code DataProvider} walk that starts
     * inside a widget's shadow tree would answer about the widget's internals rather than about the
     * widget.</p>
     *
     * <p>Retargets to the <em>outermost</em> host, so nesting composes — a button inside a toolbar
     * inside a dock reports the dock, exactly as the DOM does.</p>
     */
    @Nullable
    public static UIElement retarget(@Nullable UIElement focused) {
        if (focused == null) return null;
        UIElement outermost = focused;
        for (UIElement host = hostOf(outermost); host != null; host = hostOf(outermost)) {
            outermost = host;
        }
        return outermost;
    }

    /** Test seam: the walk cache is keyed on tree shape, which a test builds many of. */
    public static void invalidateCaches() {
        HOST_CACHE.clear();
    }
}
