package com.crystalgui.workbench.dock.panel;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.core.signal.Signal;

import com.crystalgui.workbench.dock.layout.DockPanelRef;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code typeId} → what it is, and how to build one.
 *
 * <p>This is the seam every docking system has and calls something different: VS Code's
 * {@code IViewDeserializer.fromJSON}, Golden Layout's {@code componentType} lookup, ImGui's window name.
 * The layout stores an id; the registry turns it back into a thing.</p>
 *
 * <p>It is also {@link DockLayoutCodec}'s degradation point. A saved layout naming a panel type nobody
 * registers any more — a mod was uninstalled — must lose that leaf and keep the rest, never the reverse.
 * Refusing the whole restore because one panel is missing throws away the user's entire arrangement over
 * somebody else's uninstall.</p>
 *
 * <h3>Generic in what a factory builds</h3>
 *
 * <p>{@code C} is the content type — {@code UIElement} for the widget layer, anything at all for a
 * headless test. Keeping it a type parameter rather than hardcoding {@code UIElement} is what lets the
 * whole layout half of this package stay free of the widget half, which is the same boundary
 * {@link DockLayout} is drawn on.</p>
 */
public final class DockPanelRegistry<C> {

    /** Builds the content for one panel instance. */
    @FunctionalInterface
    public interface Factory<C> {
        C create(DockPanelRef ref);
    }

    private final Map<String, DockPanelDescriptor> descriptors = new LinkedHashMap<>();
    private final Map<String, Factory<C>> factories = new LinkedHashMap<>();

    /**
     * A panel type became available.
     *
     * <h3>What it replaced</h3>
     *
     * <p>{@code ActivityBar.sync()}, called <b>every frame</b> from {@code Workbench.tick}, walking every
     * registered descriptor to find the ones that had no button yet. Its own comment explained why:
     * <i>"a host registers its own panels AFTER the workbench is built"</i> — {@code CrystalEditor} adds
     * its inspector and emitted source that way — so a one-shot pass at construction would miss them.</p>
     *
     * <p>That is a real requirement and the loop was the wrong answer to it: late registration is an
     * <em>event</em>, and this is it. The rail now adds one button when one type appears, rather than
     * asking about all of them sixty times a second forever in case a late one shows up.</p>
     */
    public final Signal.Value<DockPanelDescriptor> onDidRegister = new Signal.Value<>();

    /**
     * Providers, highest {@link DockPaneProvider#priority()} first.
     *
     * <p>Separate from {@link #factories} rather than replacing it: a factory builds one element per
     * panel and is exactly right for a tool window, which is one instance showing one thing forever. A
     * provider builds a pane that can be <b>retargeted</b>, which is what a document view wants. Both
     * arrangements are legitimate and the dock asks for a pane first, falling back to a factory.</p>
     */
    private final List<DockPaneProvider> paneProviders = new ArrayList<>();

    public DockPanelRegistry<C> registerPane(DockPaneProvider provider) {
        Objects.requireNonNull(provider, "provider");
        paneProviders.add(provider);
        // Sorted on insert rather than at lookup: a menu opening is not the moment to sort, and the list
        // is single digits and changes at startup.
        paneProviders.sort(Comparator.comparingInt(DockPaneProvider::priority).reversed());
        return this;
    }

    /** The highest-priority provider that accepts {@code input}, or null when none does. */
    @Nullable
    public DockPaneProvider paneProviderFor(DockInput input) {
        for (DockPaneProvider provider : paneProviders) {
            if (provider.accepts(input)) return provider;
        }
        return null;
    }

    public DockPanelRegistry<C> register(DockPanelDescriptor descriptor, Factory<C> factory) {
        descriptors.put(descriptor.typeId(), descriptor);
        factories.put(descriptor.typeId(), factory);
        onDidRegister.emit(descriptor);
        return this;
    }

    /**
     * Registers a type the layout may reference but that nothing can build yet.
     *
     * <p>Useful while a panel is being written, and honest about it: {@link #create} returns {@code null}
     * rather than a placeholder that looks like a working panel.</p>
     */
    public DockPanelRegistry<C> declare(DockPanelDescriptor descriptor) {
        descriptors.put(descriptor.typeId(), descriptor);
        return this;
    }

    public boolean isRegistered(String typeId) {
        return descriptors.containsKey(typeId);
    }

    public DockPanelDescriptor descriptor(String typeId) {
        return descriptors.get(typeId);
    }

    public Collection<DockPanelDescriptor> descriptors() {
        return descriptors.values();
    }

    /** The content for one panel, or {@code null} when the type is unknown or has no factory. */
    public C create(DockPanelRef ref) {
        Factory<C> factory = factories.get(ref.typeId());
        return factory == null ? null : factory.create(ref);
    }

    /**
     * Decorates a tab label with whatever the owner knows and the ref cannot — a dirty marker, most of
     * all. Null from the provider means "nothing to add", not an error.
     *
     * <p>This is IntelliJ's {@code EditorTabTitleProvider}, and it exists because a panel's title is
     * <b>partly</b> a function of its ref and partly not. The ref half is immutable and known at build
     * time; the other half changes as a document is typed into, and putting it in the ref is not an
     * option — a ref's identity <em>includes</em> its state, so editing a title would silently make it a
     * different panel and orphan its own tab.</p>
     *
     * <p>A provider rather than a setter on the tab, because the strip is rebuilt on every dock
     * rearrangement: anything pushed in has to be pushed again after each rebuild by someone who noticed
     * it happened, and nobody notices. Pulling means a tab is correct the moment it is built, whoever
     * built it and whenever.</p>
     */
    @Nullable
    private Function<DockPanelRef, String> titleProvider;

    /**
     * Names a panel's own WINDOW, when one is torn out around it — W9.
     *
     * <h3>A different question from the tab label</h3>
     *
     * <p>A tab says as little as it can get away with, because it sits beside a dozen others and the
     * strip is read by shape: {@code JarFile.java}. A window's caption is read on its own, from across a
     * desktop, and is the only place a document can say WHICH {@code JarFile.java} it is. Every editor
     * makes the same split — IntelliJ's tab says the file name and its frame says
     * {@code Project [path] - file}.</p>
     *
     * <p>Here rather than in {@code DockArea} because the answer needs a workspace: a project's display
     * name and a file's path within it are things {@code Workbench} knows and the dock deliberately does
     * not. Falls back to {@link #titleOf}, so a dock with no workspace behind it still names its
     * windows.</p>
     */
    public DockPanelRegistry<C> setWindowTitleProvider(@Nullable Function<DockPanelRef, String> provider) {
        this.windowTitleProvider = provider;
        return this;
    }

    @Nullable
    private Function<DockPanelRef, String> windowTitleProvider;

    /** The caption for a window torn out around {@code panel}. @see #setWindowTitleProvider */
    public String windowTitleOf(DockPanelRef panel) {
        if (windowTitleProvider != null) {
            String named = windowTitleProvider.apply(panel);
            if (named != null && !named.isEmpty()) return named;
        }
        return titleOf(panel);
    }

    public DockPanelRegistry<C> setTitleProvider(@Nullable Function<DockPanelRef, String> provider) {
        this.titleProvider = provider;
        return this;
    }

    /**
     * The tab label for a panel: the {@linkplain #setTitleProvider provider}'s answer if it has one, else
     * its own {@code title} state, else the type's.
     */
    public String titleOf(DockPanelRef ref) {
        if (titleProvider != null) {
            String decorated = titleProvider.apply(ref);
            if (decorated != null) return decorated;
        }
        DockPanelDescriptor descriptor = descriptors.get(ref.typeId());
        String fallback = descriptor != null ? descriptor.title() : ref.typeId();
        return ref.state(DockPanelRef.TITLE, fallback);
    }

    /**
     * A style class for a panel's tab — how a workbench says "this file will not compile".
     *
     * <p>Pulled rather than pushed, for the reason {@link #setTitleProvider} gives at length: the strip is
     * rebuilt on every dock rearrangement, so anything pushed onto a tab has to be pushed again by
     * somebody who noticed the rebuild, and nobody notices. A provider makes a tab correct the moment it
     * is built, whoever built it and whenever.</p>
     *
     * <p>A <b>class</b>, not a colour: the answer comes from {@code FileDecorations}, which merges
     * independent contributors — problems, dirty state, VCS — and names the winner as a
     * {@code decoration-*} class the stylesheet already draws. The dock stays ignorant of what any of them
     * mean, exactly as it stays ignorant of what a {@code .java} is.</p>
     */
    @Nullable
    private Function<DockPanelRef, String> decorationProvider;

    public DockPanelRegistry<C> setDecorationProvider(@Nullable Function<DockPanelRef, String> provider) {
        this.decorationProvider = provider;
        return this;
    }

    /** The {@code decoration-*} class for a panel's tab, or null. @see #setDecorationProvider */
    @Nullable
    public String decorationOf(DockPanelRef ref) {
        return decorationProvider == null ? null : decorationProvider.apply(ref);
    }

    /**
     * Supplies a tab icon for panels that do not name one themselves — how a workbench says "a panel on a
     * {@code .java} file gets the java glyph" without the dock learning what a file type is.
     */
    @Nullable
    private Function<DockPanelRef, String> iconProvider;

    public DockPanelRegistry<C> setIconProvider(@Nullable Function<DockPanelRef, String> provider) {
        this.iconProvider = provider;
        return this;
    }

    /**
     * The tab icon name for a panel, or null when it has none.
     *
     * <p>Beside {@link #titleOf} because it answers the same question about the same thing, and a panel's
     * presentation being reachable from one place is what lets the strip build a tab completely in one
     * pass. Null is a real answer, not a failure: a tool window has no file and therefore no icon, and it
     * must get no icon <em>element</em> rather than an empty one — an empty slot still takes its width and
     * would step that tab's label out of line with its neighbours.</p>
     *
     * <p><b>Provider first, state second, and the ordering is not arbitrary.</b> An explicit
     * {@link DockPanelRef#ICON} is a panel naming its own icon and is the more specific statement, so it
     * would normally win — but it is also the value a <em>saved layout</em> carries, which means it is
     * potentially stale in a way a provider never is. Asking the live workbench first is what lets a
     * changed icon theme reach a restored layout.</p>
     */
    /**
     * Whether this panel may be closed — the type's answer, and the tab's close affordance.
     *
     * <p>Unknown types are closable. A ref whose descriptor is gone is a panel restored from a layout
     * whose plugin is no longer installed, and refusing to let it be closed would leave the user with a
     * tab they can neither use nor be rid of.</p>
     */
    public boolean isClosable(DockPanelRef ref) {
        DockPanelDescriptor descriptor = descriptors.get(ref.typeId());
        return descriptor == null || descriptor.isClosable();
    }

    @Nullable
    public String iconOf(DockPanelRef ref) {
        if (iconProvider != null) {
            String provided = iconProvider.apply(ref);
            if (provided != null && !provided.isEmpty()) return provided;
        }
        String icon = ref.state(DockPanelRef.ICON, "");
        return icon.isEmpty() ? null : icon;
    }

    /** An ELEMENT for a tab's icon slot, when a name cannot say enough. @see #iconElementOf */
    @Nullable
    private Function<DockPanelRef, UIElement> iconElementProvider;

    public DockPanelRegistry<C> setIconElementProvider(
            @Nullable Function<DockPanelRef, UIElement> provider) {
        this.iconElementProvider = provider;
        return this;
    }

    /**
     * A whole element to put in a tab's icon slot, or null to use {@link #iconOf}'s name.
     *
     * <h3>Why a name is not always enough</h3>
     *
     * <p>A file's icon is one picture and a name resolves it. A DECLARATION's is not: a class that is
     * {@code static} and {@code final} carries two more marks stacked over the glyph, and those are
     * elements rather than a name — JetBrains draws each on its own full-size canvas with the mark
     * already in its corner, which is what lets both show at once.</p>
     *
     * <p>So a caller that has a symbol hands over the widget that knows how to draw one, and the dock
     * stays ignorant of what a symbol is — it puts an element in a slot. That is also what makes this
     * the union point: the completion popup builds the same widget, so a tab and a completion row cannot
     * come to disagree about what an interface looks like.</p>
     */
    @Nullable
    public UIElement iconElementOf(DockPanelRef ref) {
        return iconElementProvider == null ? null : iconElementProvider.apply(ref);
    }

    // ── Tooltips ────────────────────────────────────────────────────────────────

    /** What a tab says on hover. @see #tooltipOf */
    @Nullable
    private Function<DockPanelRef, String> tooltipProvider;

    public DockPanelRegistry<C> setTooltipProvider(@Nullable Function<DockPanelRef, String> provider) {
        this.tooltipProvider = provider;
        return this;
    }

    /**
     * What a tab says on hover, or null for no tooltip at all.
     *
     * <p>A tab shows a file NAME, and a name is ambiguous the moment two projects both have a
     * {@code Main.java} — which is exactly when the answer matters. Both references put the full path
     * here rather than in the title, because a title long enough to disambiguate is a title too long to
     * fit in a strip.</p>
     *
     * <p>Pulled from a provider for the reason {@link #setTitleProvider} gives at length: the strip is
     * rebuilt on every rearrangement, so anything pushed onto a tab has to be pushed again by somebody
     * who noticed the rebuild.</p>
     */
    @Nullable
    public String tooltipOf(DockPanelRef ref) {
        if (tooltipProvider == null) return null;
        String text = tooltipProvider.apply(ref);
        return text == null || text.isEmpty() ? null : text;
    }

    /** What a tab's ICON says on hover, when it means something of its own. @see #iconTooltipOf */
    @Nullable
    private Function<DockPanelRef, String> iconTooltipProvider;

    public DockPanelRegistry<C> setIconTooltipProvider(
            @Nullable Function<DockPanelRef, String> provider) {
        this.iconTooltipProvider = provider;
        return this;
    }

    /**
     * What the tab's icon says on hover — a second answer for the same tab, or null for one answer.
     *
     * <h3>Two providers rather than one pair, because two panels answer differently</h3>
     *
     * <p>A file tab's icon says nothing its tab does not already say: the picture comes from the file
     * extension, which is the last few characters of the path the tab is about to show you. A
     * DECLARATION's icon is the only place the kind appears at all — nothing in "ArrayList.class" says
     * it is a class rather than an interface — so it earns wording of its own.</p>
     *
     * <p>Separate from {@link #setIconElementProvider} because the two questions have different answers
     * for the same panel: a source-backed library tab takes a FILE icon (it is a {@code .java}) and could
     * still want its kind read out. Folding them into one provider would force a caller to answer both or
     * neither.</p>
     *
     * <p>Delivered as a {@linkplain com.crystalgui.ui.elements.Tooltip#addRegion region} of the tab's own
     * tooltip rather than a tooltip on the icon: a tab's icon is unhittable — as every composite part is,
     * so that a press selects the tab rather than being swallowed — and an unhittable element never
     * receives {@code mouseenter}.</p>
     */
    @Nullable
    public String iconTooltipOf(DockPanelRef ref) {
        if (iconTooltipProvider == null) return null;
        String text = iconTooltipProvider.apply(ref);
        return text == null || text.isEmpty() ? null : text;
    }
}
