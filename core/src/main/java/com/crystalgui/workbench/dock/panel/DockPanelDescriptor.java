package com.crystalgui.workbench.dock.panel;

import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * What a panel type <em>is</em>, independent of where any instance of it sits.
 *
 * <h3>{@code singleton} is where the editor-vs-toolwindow distinction really lives</h3>
 *
 * <p>VS Code and IntelliJ encode that distinction in the <em>layout</em>: the editor area is a grid and
 * tool windows are a different system arranged around it. This design rejects that asymmetry — the tree is
 * uniform — but the distinction it was protecting is real and survives here, on the type rather than on
 * the position.</p>
 *
 * <ul>
 *   <li><b>Singleton</b> — one instance, reopened from a menu when closed. The node library, an inspector,
 *       a console. Closing it means "hide it", and opening it again must find the existing one.</li>
 *   <li><b>Document</b> — many instances, opened from something. A shader graph, a {@code .glsl} buffer.
 *       Two of them are two different things and both belong on screen at once.</li>
 * </ul>
 *
 * <p>Getting this wrong is not a layout bug: a singleton treated as a document opens a second console
 * every time you press the button, and a document treated as a singleton silently refuses to open the
 * second file you asked for.</p>
 */
public final class DockPanelDescriptor {

    private final String typeId;
    private final String title;
    private DockPanelKind kind;
    private DockRegion region;
    private RegionSide side = RegionSide.PRIMARY;
    private final boolean closable;
    private final String icon;
    private final DockDropZone anchor;

    public DockPanelDescriptor(String typeId, String title) {
        this(typeId, title, false, true);
    }

    public DockPanelDescriptor(String typeId, String title, boolean singleton, boolean closable) {
        this(typeId, title, singleton, closable, null, DockDropZone.SPLIT_LEFT);
    }

    private DockPanelDescriptor(String typeId, String title, boolean singleton, boolean closable,
                                @Nullable String icon, DockDropZone anchor) {
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.title = Objects.requireNonNull(title, "title");
        this.kind = singleton ? DockPanelKind.VIEW : DockPanelKind.DOCUMENT;
        this.closable = closable;
        this.icon = icon;
        this.anchor = Objects.requireNonNull(anchor, "anchor");
    }

    public static DockPanelDescriptor singleton(String typeId, String title) {
        return new DockPanelDescriptor(typeId, title, true, true);
    }

    /**
     * A group of {@link DockPanelKind#VIEW}s sharing a region — what an activity bar button toggles.
     *
     * <p>The kind that did not exist. See {@link DockPanelKind#CONTAINER}.</p>
     */
    public static DockPanelDescriptor container(String typeId, String title, DockRegion region) {
        return new DockPanelDescriptor(typeId, title, true, true, null, region.wall())
                .region(region)
                .asKind(DockPanelKind.CONTAINER);
    }

    /** Sets the kind outright — for {@link #container}, which is neither of the two boolean states. */
    private DockPanelDescriptor asKind(DockPanelKind value) {
        this.kind = value;
        return this;
    }

    public static DockPanelDescriptor document(String typeId, String title) {
        return new DockPanelDescriptor(typeId, title, false, true);
    }

    /**
     * The panel type's own icon — what the activity bar draws for it.
     *
     * <h3>Not a tab icon</h3>
     *
     * <p>Both editors put a tool window's icon on its <b>stripe button</b> and leave its header plain
     * text: IntelliJ's Project panel is a folder glyph on the rail and the word "Project" on the window,
     * and VS Code's Explorer is the same. So this is a different concept from the file icon a document tab
     * carries, which is a property of the <em>file</em> and comes from
     * {@link DockPanelRegistry#setIconProvider}. Feeding this to a tab as well would put a folder next to
     * the word "Project" and read as clutter in exactly the way both editors avoided.</p>
     *
     * <p>An icon <em>name</em>, resolved the way {@code icon()} resolves one in CSS.</p>
     */
    public DockPanelDescriptor icon(@Nullable String iconName) {
        return new DockPanelDescriptor(typeId, title, isSingleton(), closable, iconName, anchor)
                .region(region).side(side);
    }

    @Nullable
    public String icon() {
        return icon;
    }

    /**
     * Which outer edge this panel opens against, and therefore which stripe carries its button.
     *
     * <h3>One fact, not two</h3>
     *
     * <p>IntelliJ's {@code ToolWindowAnchor} is exactly this, and it is deliberately a single value: the
     * stripe you appear on <em>is</em> where you dock. Splitting it into "which rail" and "where it opens"
     * gives two things that can disagree, and the disagreement is invisible until someone closes a panel
     * and reopens it somewhere they were not looking.</p>
     *
     * <p>Stated as a {@link DockDropZone} rather than a fresh {@code Anchor} enum because
     * {@link DockLayout#dropOnOuterEdge} already consumes exactly this vocabulary — a parallel enum would
     * be a mapping to keep in step for no expressive gain.</p>
     *
     * <p><b>This is a real concession</b> and worth naming. The class note above says this design rejects
     * the editor-area/tool-window asymmetry that VS Code and IntelliJ build into their layouts, and an
     * anchor puts some of it back. It earns its place by answering a question the uniform tree cannot: a
     * <em>closed</em> panel is in no leaf, so "where does it reopen?" has no answer derivable from the
     * layout — and that is precisely the moment the activity bar exists for.</p>
     */
    public DockPanelDescriptor anchor(DockDropZone zone) {
        return new DockPanelDescriptor(typeId, title, isSingleton(), closable, icon, zone)
                .region(region).side(side);
    }

    public DockDropZone anchor() {
        return anchor;
    }

    public String typeId() {
        return typeId;
    }

    /** The default tab label. A panel may override it per instance through its {@link DockPanelRef}. */
    public String title() {
        return title;
    }

    /**
     * What this panel type is — see {@link DockPanelKind}.
     *
     * <p>Replaces the {@code singleton} boolean, which could not express a third kind. {@link #isSingleton}
     * survives as {@code kind() == VIEW} so every existing call site keeps its exact meaning.</p>
     */
    public DockPanelKind kind() {
        return kind;
    }

    /**
     * Where a panel of this type belongs — see {@link DockRegion}.
     *
     * <p>Defaults from {@link #anchor()}, which is the same fact stated as a wall. The anchor is what a
     * tool window's placement has always meant; naming it a region is what lets it survive the tree it is
     * currently expressed in.</p>
     */
    public DockRegion region() {
        return region != null ? region : DockRegion.ofWall(anchor);
    }

    /** @see #region() */
    public DockPanelDescriptor region(DockRegion value) {
        this.region = value;
        return this;
    }

    /**
     * Which half of that region a panel of this type opens in — see {@link RegionSide}.
     *
     * <p>Only a <em>default</em>, and one almost every type should leave alone. It is where a tool window
     * lands the first time anyone opens it and never again: from then on the answer is the user's, stored
     * on its {@code ToolWindowState}. Worth setting for a type that ships as the second half of a pair —
     * an outline beside a tree — so the pair is the out-of-the-box arrangement rather than something to be
     * discovered.</p>
     */
    public DockPanelDescriptor side(RegionSide value) {
        this.side = value == null ? RegionSide.PRIMARY : value;
        return this;
    }

    /** @see #side(RegionSide) */
    public RegionSide side() {
        return side;
    }

    /** {@code kind() == VIEW}. Kept because it is what every call site already asks. */
    public boolean isSingleton() {
        return kind == DockPanelKind.VIEW;
    }

    public boolean isClosable() {
        return closable;
    }

    @Override
    public String toString() {
        return typeId + " (" + kind + ")";
    }
}
