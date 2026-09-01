package com.crystalgui.workbench.view;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UINode;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Which views a container holds, and what a stripe button should show for it.
 *
 * <h3>Membership belongs to the container</h3>
 *
 * <p>A view never names its container — the rule {@link com.crystalgui.ui.elements.dock.ViewId} exists to
 * enforce. Both references arrange it this way, and the reason is that moving a view must be <b>one</b>
 * write: put the container on the view and two containers can disagree about who holds it, with nothing
 * authoritative.</p>
 *
 * <h3>A container with no registered views is its own single view</h3>
 *
 * <p>Which is what makes this land without churn. Every existing tool window — Project, Problems, the
 * Inspector — is a container holding one view: itself. Registering a second view for Problems is then one
 * call, and the tab strip appears because there is finally something to choose between.</p>
 */
public final class ViewContainerRegistry {

    /** One view inside a container: a stable id, what its tab says, and how to build it. */
    public record ViewEntry(String viewId, String title, Supplier<UINode> factory) {
        public UINode build() {
            UINode built = factory.get();
            return built == null ? new UINode() : built;
        }
    }

    private final Map<String, List<ViewEntry>> views = new LinkedHashMap<>();
    private final Map<String, String> badges = new LinkedHashMap<>();

    /**
     * The style class a container's badge carries, if it wants one — VS Code's {@code IBadge} type, which
     * exists for the same reason.
     *
     * <p><b>Kept beside the text rather than folded into it</b>, because the badge's own rules are shared
     * by every container on the rail: a caller that wanted a green dot and could only send a string would
     * have to be styled by writing the colour into {@code .__dot__}, which is the Problems count's rule
     * too. One green run would turn every count on the rail green.</p>
     *
     * <p>Not on {@link #onDidChangeBadge} either — it is a {@code Pair}, and widening it would touch both
     * rails' subscriptions to carry something they can already ask for. The style is read at the moment
     * the signal is handled, so the two cannot disagree.</p>
     */
    private final Map<String, String> badgeStyles = new LinkedHashMap<>();

    /**
     * A container's badge changed — {@code (containerId, text)}, with a null text meaning cleared.
     *
     * <p>VS Code's {@code IActivityService.showViewContainerActivity} is on the <em>container</em> for the
     * same reason: a badge answers "is there something here", and "here" is the thing the rail button
     * points at. It could not exist before containers did.</p>
     */
    public final Signal.Pair<String, String> onDidChangeBadge = new Signal.Pair<>();

    /** Adds a view to a container. Order is registration order. */
    public ViewContainerRegistry addView(String containerId, ViewEntry view) {
        if (containerId == null || view == null) return this;
        views.computeIfAbsent(containerId, id -> new ArrayList<>()).add(view);
        return this;
    }

    /** @see #addView */
    public ViewContainerRegistry addView(String containerId, String viewId, String title,
                                         Supplier<UINode> factory) {
        return addView(containerId, new ViewEntry(viewId, title, factory));
    }

    /**
     * The views of {@code containerId}, or a single view built by {@code fallback} when none were
     * registered — see the class note on why that default is what makes this incremental.
     */
    public List<ViewEntry> viewsOf(String containerId, String title, Supplier<UINode> fallback) {
        List<ViewEntry> registered = views.get(containerId);
        if (registered != null && !registered.isEmpty()) return List.copyOf(registered);
        return List.of(new ViewEntry(containerId, title, fallback));
    }

    public boolean hasViews(String containerId) {
        List<ViewEntry> registered = views.get(containerId);
        return registered != null && !registered.isEmpty();
    }

    /**
     * The badge meaning "there is something here, and the count is not worth saying".
     *
     * <h3>A value rather than a second channel</h3>
     *
     * <p>A dot is not a different kind of thing from a count — this registry's own note says a badge
     * answers <em>"is there something here"</em>, which makes the dot the purest form of one. A parallel
     * {@code setDot}/{@code onDidChangeDot} pair would duplicate the map, the signal and both rails'
     * subscriptions to express that.</p>
     *
     * <p>It is {@code "•"} rather than a private marker so that it degrades into something sensible: a
     * stripe whose sheet has no dot rule draws a bullet over the icon, which is approximately right,
     * instead of the literal word "dot".</p>
     */
    public static final String DOT = "•";

    /** What the stripe button should show — a count, {@link #DOT}, anything short. Null clears it. */
    public void setBadge(String containerId, @Nullable String text) {
        setBadge(containerId, text, null);
    }

    /**
     * The same, with a style class the rail puts on the badge — see {@link #badgeStyles}.
     *
     * <p>The class is compared as part of "did anything change", not only the text. Without that, moving a
     * badge from one kind to another while the text stays {@link #DOT} — a container that is both busy and
     * has news — would be discarded as a no-op and the rail would keep the first colour forever.</p>
     */
    public void setBadge(String containerId, @Nullable String text, @Nullable String styleClass) {
        if (containerId == null) return;
        boolean clearing = text == null || text.isEmpty();
        String wantedStyle = clearing ? null : styleClass;
        if (java.util.Objects.equals(badges.get(containerId), text)
                && java.util.Objects.equals(badgeStyles.get(containerId), wantedStyle)) {
            return;
        }
        if (clearing) {
            badges.remove(containerId);
            badgeStyles.remove(containerId);
        } else {
            badges.put(containerId, text);
            if (wantedStyle == null) badgeStyles.remove(containerId);
            else badgeStyles.put(containerId, wantedStyle);
        }
        onDidChangeBadge.emit(containerId, text);
    }

    @Nullable
    public String badgeOf(String containerId) {
        return badges.get(containerId);
    }

    /** The style class {@link #setBadge(String, String, String)} was given, or null. */
    @Nullable
    public String badgeStyleOf(String containerId) {
        return badgeStyles.get(containerId);
    }
}
