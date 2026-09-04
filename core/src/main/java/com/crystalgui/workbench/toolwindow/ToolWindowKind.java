package com.crystalgui.workbench.toolwindow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;

/**
 * <b>A panel in one declaration</b> — what {@code DocumentKind} is to a file type.
 *
 * <pre>{@code
 * ToolWindowKind.of("crystalgui:problems", "Problems")
 *         .icon("crystalgui:toolwindows/problems")
 *         .anchor(DockDropZone.SPLIT_DOWN)
 *         .view(ctx -> problemsPanel)
 *         .toggle("workbench.showProblems")
 *         .openByDefault();
 * }</pre>
 *
 * <p>From that the engine derives what was spread across five places: the {@code DockPanelDescriptor}
 * (kept as the <em>compiled</em> form), the factory, the {@code ViewContainerRegistry} entries, the
 * stripe button, the toggle command with its accelerator, whether it is open on a fresh workspace, and
 * the badge subscription. Registering one is {@code ctx.registerToolWindow(kind)} and it hands back a
 * {@link Disposable}, so an extension that goes takes its panel with it.</p>
 *
 * <h3>Where it opens is a DEFAULT, never a rule</h3>
 *
 * <p>{@link #region}, {@link #side} and {@link #anchor} say where a panel goes when nothing else has an
 * opinion. A {@code ToolWindowState} restored from a session outranks all three — a panel the user
 * dragged to the other rail stays there, which is the whole point of persisting a placement.</p>
 *
 * <h3>Mapping to the references</h3>
 *
 * <p>IntelliJ's {@code toolWindow} extension point: {@code id}, {@code anchor}→{@link #region},
 * {@code icon}, {@code factoryClass}→{@link #view}, {@code secondary}→{@link #side},
 * {@code doNotActivateOnStart}→ the absence of {@link #openByDefault()}. VS Code:
 * {@code viewsContainers.activitybar} is a kind, {@code views} are {@link #view(String, String,
 * Function)} entries in one container.</p>
 */
public final class ToolWindowKind {

    /** One view inside a container. A container with several draws a header per view. */
    public record View(String viewId, String title, Function<WorkbenchContext, UIElement> factory) {
    }

    /**
     * A count or a dot on the stripe button, kept in step with whatever it is counting.
     *
     * <p>An installer rather than a value, because a badge is a <em>subscription</em>: something has to
     * be watched and something has to be released. The engine hands over the sink and keeps the handle,
     * so a kind that is withdrawn stops writing to a button that is gone.</p>
     */
    @FunctionalInterface
    public interface Badge {
        Disposable install(WorkbenchContext workbench, Consumer<String> set);
    }

    private final String id;
    private final String displayName;

    @Nullable
    private String icon;
    @Nullable
    private DockRegion region;
    @Nullable
    private RegionSide side;
    @Nullable
    private DockDropZone anchor;
    @Nullable
    private Function<WorkbenchContext, UIElement> single;
    private final List<View> views = new ArrayList<>();
    @Nullable
    private String toggleCommand;
    @Nullable
    private String accelerator;
    @Nullable
    private Badge badge;
    private boolean openByDefault;
    private boolean persistent;

    private ToolWindowKind(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** @param id the type id — persisted in a session record, so it is picked once and namespaced */
    public static ToolWindowKind of(String id, String displayName) {
        return new ToolWindowKind(id, displayName);
    }

    public ToolWindowKind icon(String icon) {
        this.icon = icon;
        return this;
    }

    /** Which region it opens in when nothing else says. @see DockRegion */
    public ToolWindowKind region(DockRegion region) {
        this.region = region;
        return this;
    }

    /** Which half of that region. @see RegionSide */
    public ToolWindowKind side(RegionSide side) {
        this.side = side;
        return this;
    }

    /**
     * Where it lands in the dock when it has no region — the older placement, kept because two of the
     * built-ins want it: a panel reopened from its stripe button should land where the default layout
     * had put it rather than somewhere merely legal.
     */
    public ToolWindowKind anchor(DockDropZone anchor) {
        this.anchor = anchor;
        return this;
    }

    /** The panel's content. Built once, lazily, when the dock first asks for it. */
    public ToolWindowKind view(Function<WorkbenchContext, UIElement> factory) {
        this.single = factory;
        return this;
    }

    /** A named view in this container — several make a container with a header per view. */
    public ToolWindowKind view(String viewId, String title, Function<WorkbenchContext, UIElement> factory) {
        views.add(new View(viewId, title, factory));
        return this;
    }

    /**
     * A command that reveals it, and optionally the key that runs the command.
     *
     * <p>Registered globally and resolved from the data context, never captured: a captured workbench
     * makes a second window's command toggle a panel in the first.</p>
     */
    public ToolWindowKind toggle(String commandId) {
        this.toggleCommand = commandId;
        return this;
    }

    /** @param accelerator a chord as {@code KeyChord.parse} reads it, e.g. {@code "Alt+6"} */
    public ToolWindowKind toggle(String commandId, String accelerator) {
        this.toggleCommand = commandId;
        this.accelerator = accelerator;
        return this;
    }

    /** Keeps a count or a dot on the stripe button. @see Badge */
    public ToolWindowKind badge(Badge badge) {
        this.badge = badge;
        return this;
    }

    /** Open on a workspace that has no session record yet. A session outranks it. */
    public ToolWindowKind openByDefault() {
        this.openByDefault = true;
        return this;
    }

    /** Marks the view's element {@code SESSION_PERSISTENT}, so its opted-in widget state is recorded. */
    public ToolWindowKind persistent() {
        this.persistent = true;
        return this;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    @Nullable
    public String icon() {
        return icon;
    }

    @Nullable
    public DockRegion region() {
        return region;
    }

    @Nullable
    public RegionSide side() {
        return side;
    }

    @Nullable
    public DockDropZone anchor() {
        return anchor;
    }

    @Nullable
    public Function<WorkbenchContext, UIElement> singleView() {
        return single;
    }

    public List<View> views() {
        return views;
    }

    @Nullable
    public String toggleCommand() {
        return toggleCommand;
    }

    @Nullable
    public String accelerator() {
        return accelerator;
    }

    @Nullable
    public Badge badgeSource() {
        return badge;
    }

    public boolean isOpenByDefault() {
        return openByDefault;
    }

    public boolean isPersistent() {
        return persistent;
    }
}
