package com.crystalgui.workbench;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.async.ReplyError;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.document.DocumentState;
import com.crystalgui.document.EditorInput;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.ContentProvider;
import com.crystalgui.fs.protocol.FsError;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.text.lang.ProjectSources;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.display.SymbolIcon;
import com.crystalgui.workbench.decoration.FileDecoration;
import com.crystalgui.workbench.decoration.FileDecorations;
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.DockGroup;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.editor.EditorService;
import com.crystalgui.workbench.explorer.WorkspaceTreeSource;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Extracted from {@link Workbench}. See the plan's §4.5 for why this cluster is one thing.
 */
public final class DocumentTabs {

    private final Workbench workbench;

    public DocumentTabs(Workbench workbench) {
        this.workbench = workbench;
    }

    /**
     * Re-reads the folder an operation touched.
     *
     * <p>Both ends of a move are covered, because the SERVER reports a rename as one event carrying
     * both — which is what the tree subscribes to. This is the local half, for the operation this
     * client itself issued.</p>
     */
    void refreshAfter(Resource resource) {
        CgPath path = resource.asPath();
        // INVALIDATING ANNOUNCES, so there is no `treeView().refresh()` beside it any more: whoever
        // is showing the listing subscribes. @see WorkspaceTreeSource#onDidInvalidate
        if (path != null) workbench.projects().invalidate(path.parent());
    }

    /**
     * Keeps the dock in step with what the document store did.
     *
     * <p>The store announces what happened to a document and the dock follows. It hears about another
     * client's rename through the same {@code fs.changed} as its own, so a file renamed from anywhere
     * moves its tab — not only one renamed from here.</p>
     */
    void followDocuments() {
        workbench.documents.onDidClose.connect(document -> {
            Resource resource = document.resource();
            // The TAB goes too, and this is the half that is easy to forget: a document dropped with its
            // tab left behind leaves the dock asking the registry to rebuild a panel for a file that no
            // longer exists, which comes back as the "__missing__" placeholder.
            workbench.dock.layout().closePanel(workbench.refForResource(resource));
            workbench.dock.requestRebuild();
        });
        workbench.documents.onDidOpen.connect(document -> document.onDidChangeResource.connect((from, to) -> {
            // In place, so the tab keeps its position and its selection. A remove-then-add would send the
            // renamed file to the end of the strip and, if it was active, hand the selection to a
            // neighbour on the way -- the file you just renamed vanishing from where you were looking.
            DockPanelRef was = workbench.refForResource(from);
            DockPanelRef now = workbench.refForResource(to);
            for (DockLeaf leaf : workbench.dock.layout().leaves()) {
                if (leaf.replace(was, now)) break;
            }
            workbench.dock.requestRebuild();
        }));
    }

    /**
     * Rebuilds a panel whose placeholder no longer stands for anything true.
     *
     * <p>Two transitions matter and nothing else does. A tab that <b>gains a view</b> while a
     * placeholder is on screen: the read landed, and the dock's memo is still the empty element it built
     * while the read was in flight. And a placeholder whose <b>state moved</b> — in practice
     * {@code LOADING -> FAILED}, which is the only way a tab with no view changes — so the banner below
     * can say what went wrong.</p>
     *
     * <p><b>Not "the state changed".</b> This signal also fires on {@code CLEAN -> DIRTY}, which is every
     * keystroke; rebuilding there would detach the editor being typed in, which is the widget-rebuild
     * trap on the one widget that can least afford it. Once a view is up there is no entry in
     * {@link #placeholders} and this method does nothing at all.</p>
     */
    void refreshPanelForTab(EditorService.Tab tab) {
        DockPanelRef ref = workbench.refForResource(tab.resource());
        if (workbench.dock.builtContentFor(ref) == null) return;   // not on screen; nothing to replace
        if (closeIfTheFileIsGone(tab, ref)) return;
        DocumentState placeholder = workbench.placeholders.get(ref);
        if (tab.editor() != null) {
            if (placeholder != null) workbench.dock.rebuildPanel(ref);
            return;
        }
        if (placeholder != null && placeholder != tab.state()) workbench.dock.rebuildPanel(ref);
    }

    /**
     * <b>A tab for a file that is no longer there closes itself.</b>
     *
     * <p>What both references do, and the distinction they draw is the whole of this method. A session
     * remembers what was open when it was written; between then and now a file can be deleted, renamed
     * or moved, and a tab for one is not a problem to report — it is a tab with no subject. VS Code
     * drops it on restore and IntelliJ drops it; neither asks, because there is nothing to decide.</p>
     *
     * <p><b>Only for {@code NOT_FOUND}.</b> A file that is still there and could not be READ is a
     * different thing entirely — no permission, a bad encoding, larger than the cap, a provider that
     * failed — and closing the tab would throw away both the fact and the {@code Retry} that can act on
     * it. That one keeps its banner. The code is the discriminator rather than the message, which is
     * what {@code FsError} carries a code FOR.</p>
     *
     * <p>Silent, deliberately. {@code openResource} notifies on a failed open because somebody just
     * asked for that file; this fires for a tab nobody asked for today, and a notification per deleted
     * file on every launch is noise about something that was already true.</p>
     *
     * @return whether the tab was closed, so the caller stops touching a panel that has gone
     */
    private boolean closeIfTheFileIsGone(EditorService.Tab tab, DockPanelRef ref) {
        if (tab.state() != DocumentState.FAILED) return false;
        ReplyError why = tab.failure();
        if (why == null || !why.is(FsError.NOT_FOUND)) return false;
        workbench.placeholders.remove(ref);
        workbench.editors.close(tab);
        if (workbench.dock.layout().closePanel(ref)) workbench.dock.requestRebuild();
        return true;
    }

    /**
     * A tab whose document could not be read says so, with a way to try again.
     *
     * <p>Without it a file that has been deleted or renamed under a saved session comes back as a blank
     * pane and nothing anywhere explains it — which is indistinguishable from the editor being broken,
     * and was reported as exactly that. A banner rather than content because the panel legitimately has
     * nothing to show: this is the one place that can say WHY there is nothing.</p>
     *
     * <p>{@code Tab.retry()} has carried the javadoc <i>"what a retry affordance on the tab calls"</i>
     * since it was written, and there was no such affordance.</p>
     */
    void registerFailureBanner() {
        workbench.registry.registerBanner(panel -> {
            // A PANEL NEED NOT BE ABOUT A FILE. A tool window has no path state at all, and
            // `Resource.parse("")` THROWS rather than answering null -- so a provider that parses first
            // and asks questions afterwards takes down the build of every panel in the dock, not its
            // own. The same shape as the active-panel signal assuming a path, one layer over.
            String path = panel.state(Workbench.PATH_STATE, "");
            if (path.isEmpty()) return null;
            Resource about = Resource.parse(path);
            EditorService.Tab tab = workbench.editors.tabFor(EditorInput.of(about));
            if (tab == null || tab.state() != DocumentState.FAILED) return null;
            ReplyError why = tab.failure();
            return Notification.error(about.name() + " could not be opened")
                    .withDetail(why == null ? "the read failed" : why.detail())
                    .withAction("Retry", tab::retry);
        });
    }

    /**
     * What each tab's label should say right now — the file name, plus a marker when it is modified.
     *
     * <p>Registered as the registry's title provider, so it is consulted whenever a tab is built or
     * refreshed. Returns null for a panel with no file, which is the provider contract's way of saying
     * "nothing to add" and lets the registry fall through to the panel's own title.</p>
     *
     * <p>Reads {@code TITLE} state directly rather than calling {@code registry.titleOf}, which would
     * re-enter this method.</p>
     */
    @Nullable
    String tabTitleFor(DockPanelRef panel) {
        Resource viewed = viewedResource(panel);
        if (viewed != null) return viewerDisplayName(viewed);
        String path = panel.state(Workbench.PATH_STATE, "");
        if (path.isEmpty()) return null;
        String title = panel.state(DockPanelRef.TITLE, CgPath.parse(path).name());
        return workbench.saveActions.isDirty(CgPath.parse(path)) ? title + Workbench.DIRTY_MARKER : title;
    }

    /**
     * How a tab is coloured — the same answer the file's row in the tree gets, from the same providers.
     *
     * <p>Asked of {@link FileDecorations} rather than of {@code markers} directly, and that is the point
     * of routing it this way: a tab and a tree row showing different things about one file is precisely
     * the disagreement a shared model exists to prevent, and everything else that decorates a file —
     * dirty state, VCS, whatever comes next — reaches the tab for free rather than needing a second
     * mechanism per surface.</p>
     *
     * <p><b>Not bubbled and not directory-resolved</b>: a tab is always a file.</p>
     */
    @Nullable
    String tabDecorationFor(DockPanelRef panel) {
        // A BORROWED FILE IS TINTED, which is the one decoration a viewer carries and the reason it can
        // share the file-decoration slot rather than needing a second one: a library class has no VCS
        // state, no dirty marker and no compile errors of its own to report, so nothing can collide.
        // IntelliJ tints these tabs for the same reason -- it is the fastest way to say "this is not
        // yours" without spending a word on it.
        Resource resource = viewedResource(panel);
        if (resource == null) return null;
        CgPath path = resource.asPath();
        if (path == null) return Workbench.LIBRARY_DECORATION;
        // NULL IS THE ORDINARY ANSWER -- an undecorated file is the state nearly every file is in, and
        // resolve() says so with null rather than with an empty decoration.
        FileDecoration decoration = workbench.decorations().resolve(path, false);
        return decoration == null ? null : decoration.styleClass();
    }

    /**
     * Re-reads every open tab's decoration.
     *
     * <p>Through the dock's own {@code refreshPanelPresentation} rather than by walking leaves to groups
     * to tabs — the walk {@code DockArea} explicitly warns callers off, because it keeps compiling long
     * after the dock changes how a tab is built.</p>
     */
    public void syncTabDecorations() {
        for (DockPanelRef panel : workbench.dock.allPanels()) workbench.dock.refreshPanelPresentation(panel);
    }

    /**
     * Which icon a tab shows — the same one the file's row in the tree shows, from the same theme.
     *
     * <p>Static, because it depends on nothing but the panel. It is no longer pulled once and kept,
     * though: the icon element beside this one asks what the file DECLARES, and that answer is read
     * through {@code ProjectSources}, which does not have it until the file has been read. So
     * {@link #announceProjectSourcesMoved} re-reads every tab's presentation when one lands.</p>
     */
    @Nullable
    String tabIconFor(DockPanelRef panel) {
        Resource viewed = viewedResource(panel);
        if (viewed != null) {
            // A DECLARATION'S GLYPH IS AN ELEMENT, not a name -- see viewerIconElement, which the dock
            // asks for first. This is the fallback for a viewer showing a FILE: a `.java` tab takes the
            // file icon, exactly as one in the project does.
            String name = viewerDisplayName(viewed);
            return name == null ? null : FileIconTheme.getDefault().iconFor(name, false, false);
        }
        return null;
    }

    /**
     * The glyph for a viewer tab showing a DECLARATION, or null to fall back to a file icon.
     *
     * <p>{@link SymbolIcon} is the union point: the completion popup builds the same widget from the
     * same {@code completion-kind-*} vocabulary, so a tab and a completion row cannot come to disagree
     * about what an interface looks like. It also carries the {@code static} and {@code final} marks,
     * which an icon NAME cannot — they are layers stacked over the glyph rather than a picture.</p>
     *
     * <p>Null when nothing can say what the tab holds — see {@link #symbolFor}, which is where both
     * kinds of tab now ask the same question.</p>
     */
    @Nullable
    UIElement viewerIconElement(DockPanelRef panel) {
        SymbolInfo symbol = symbolFor(panel);
        if (symbol == null) return null;
        return new SymbolIcon().show(symbol.kind(), symbol.modifiers());
    }

    /**
     * What the thing behind this tab IS, or null when nothing knows.
     *
     * <p><b>Both kinds of tab, through one seam.</b> This used to ask only about a VIEWER panel, so a
     * decompiled {@code FlexDirection.class} drew an enum glyph and hovered "Final enum" while the
     * author's own {@code Main.java} in the next tab drew a file icon — the same question, asked of a
     * resource nobody had registered a provider for. {@code ProjectSourceSymbols} answers
     * {@code project://} now, and this is where the tab stopped asking.</p>
     *
     * <p>Null stays a supported answer at every step: no resource, no provider, no symbol, or a symbol
     * with no kind all fall through to the file-type icon the tab drew before.</p>
     */
    @Nullable
    private SymbolInfo symbolFor(DockPanelRef panel) {
        Resource resource = viewedResource(panel);
        if (resource == null) return null;
        ContentProvider provider = workbench.workspace.providerFor(resource);
        SymbolInfo symbol = provider == null ? null : provider.symbolOf(resource);
        return symbol == null || symbol.kind() == null ? null : symbol;
    }

    /**
     * What a tab says on hover — where the thing it shows actually is.
     *
     * <p>The label is a bare name, and a name stops identifying anything the moment two of them collide:
     * two {@code Main.java} in one workspace, or {@code java.util.List} beside {@code java.awt.List}. The
     * second pair is the reason a viewer answers with its <b>fully-qualified</b> name rather than a file
     * path — there often is no file, the tab is a decompilation, and the qualified name is the only thing
     * that names it uniquely.</p>
     *
     * <p>Null for a panel that is not about a location at all — a console, the Problems view — which the
     * registry reads as "no tooltip", not as an empty one.</p>
     */
    /**
     * A torn-out window's caption: {@code Project - name.ext [where]} — W9.
     *
     * <h3>Three parts, in the order they are useful</h3>
     *
     * <p>A tab can be terse because it sits in a strip of siblings and is read by shape; a caption is
     * read alone, from across a desktop, and is the only thing that can say which of three files called
     * {@code build.gradle.kts} this one is. So it names the workspace, then the file, then where the
     * file is — the file in the middle because that is what the eye is looking for, with the context on
     * either side of it.</p>
     *
     * <p><b>Both kinds of document take the same shape</b>, which is the point of doing it here rather
     * than twice. A workspace file's "where" is its directory within the project; a borrowed class's is
     * its package, and its project is the workspace you are in rather than one of its own — a library
     * class belongs to a jar, not to the project, and the caption is still telling you where YOU are.
     * With more than one project open that stops being unambiguous, so it says nothing instead of
     * guessing (see {@code WorkspaceTreeSource.soleProjectName}).</p>
     *
     * <p>Null for anything that is not a document at all — a torn-out tool window has a name and no
     * location — and the registry falls back to the tab label for those.</p>
     */
    @Nullable
    String windowTitleFor(DockPanelRef panel) {
        WorkspaceTreeSource source = workbench.projectListing();

        Resource viewed = viewedResource(panel);
        if (viewed == null) return null;
        CgPath path = viewed.asPath();
        if (path != null) return projectCaption(source, path);
        // A qualified name is its own path: everything up to the last dot is the package, and the
        // display name is already the file-shaped form of the last segment ("JarFile.java"), so
        // neither half has to be reassembled from the other.
        String qualified = viewed.path();
        int lastDot = qualified.lastIndexOf('.');
        String pkg = lastDot > 0 ? qualified.substring(0, lastDot) : "";
        return caption(source == null ? null : source.soleProjectName(),
                viewerDisplayName(viewed), pkg);
    }

    private static String projectCaption(@Nullable WorkspaceTreeSource source, CgPath path) {
        String within = path.path();
        int lastSlash = within.lastIndexOf('/');
        String directory = lastSlash > 0 ? within.substring(0, lastSlash) : "";
        return caption(source == null ? null : source.displayNameOf(path), path.name(), directory);
    }

    /**
     * {@code Project - name [where]}, with either context omitted when there is none.
     *
     * <p>Omitted rather than left empty: a caption reading {@code " - name []"} says the same thing as
     * {@code "name"} and looks like a bug in the formatter, which is worse than saying less.</p>
     */
    private static String caption(@Nullable String project, String name, @Nullable String where) {
        StringBuilder out = new StringBuilder();
        if (project != null && !project.isEmpty()) out.append(project).append(" - ");
        out.append(name);
        if (where != null && !where.isEmpty()) out.append(" [").append(where).append(']');
        return out.toString();
    }

    @Nullable
    static String tabTooltipFor(DockPanelRef panel) {
        Resource viewed = viewedResource(panel);
        return viewed == null ? null : viewed.path();
    }

    /**
     * What a tab's ICON says on hover — what the declaration behind it <em>is</em>.
     *
     * <p>The one fact a library tab shows nowhere else. Nothing in {@code ArrayList.class} distinguishes a
     * class from an interface, an enum or an annotation, and the glyph is where that answer already
     * lives — so the icon is the part of the tab that has something of its own to say, and this is it in
     * words. {@link SymbolIcon#describe} is the single source of both, so the picture and the sentence
     * cannot drift apart.</p>
     *
     * <p>And it is no longer only a library tab that has it: a project {@code .java} row and its tab
     * both show what the file declares, so the sentence follows them there. A tab whose provider cannot
     * say keeps the file icon and gets no icon tooltip, which is the honest pair.</p>
     */
    @Nullable
    String tabIconTooltipFor(DockPanelRef panel) {
        SymbolInfo symbol = symbolFor(panel);
        return symbol == null ? null : SymbolIcon.describe(symbol.kind(), symbol.modifiers());
    }

    /**
     * The resource a panel shows, or null for a tab that is not about one.
     *
     * <p><b>One question for every kind of tab.</b> There were two — a viewer panel carried its own
     * state key and a file panel carried {@link #PATH_STATE} — so every presentation method below began
     * by asking which kind it had, and each of them got a different half of the answer right.</p>
     */
    @Nullable
    static Resource viewedResource(DockPanelRef panel) {
        String text = panel.state(Workbench.PATH_STATE, "");
        if (text.isEmpty()) return null;
        try {
            return Resource.parse(text);
        } catch (RuntimeException notAResource) {
            return null;
        }
    }

    /**
     * What a viewer tab is called — the provider's answer, or the bare type name.
     *
     * <p>Asked of the provider rather than derived here, because the extension depends on what is
     * SERVING the resource: {@code ArrayList.java} where source was attached and
     * {@code FlexDirection.class} where the bytes were decompiled. The workbench has no way to know
     * which, and inventing {@code .java} for both would put a source extension on a tab full of
     * reconstructed code.</p>
     *
     * <p><b>Not written into the ref.</b> {@link DockPanelRef} equality includes its state, and the ref
     * is how an open tab is FOUND again — so a title that can change between two reads would orphan the
     * tab it names. The ref keeps the stable simple name; this decorates it for display, which is
     * exactly the split the title provider exists for.</p>
     */
    @Nullable
    private String viewerDisplayName(Resource resource) {
        return workbench.titleOf(resource);
    }

    /**
     * Brings every visible tab label into line with its document.
     *
     * <p>The labels are otherwise only computed when the strip is <b>rebuilt</b>, and a rebuild is exactly
     * what must not happen for this: it detaches and recreates the tab elements, so doing it on every
     * keystroke would tear down the tab the user is typing under — the rule the table header and the file
     * tree both paid for. Setting the text on the tabs that already exist changes nothing structural.</p>
     */
    public void refreshTabTitles() {
        for (DockLeaf leaf : workbench.dock.layout().leaves()) {
            DockGroup group = workbench.dock.groupFor(leaf);
            if (group == null) continue;
            for (DockPanelRef panel : group.panels()) workbench.dock.refreshPanelPresentation(panel);
        }
    }

    /**
     * Keeps the dirty markers current.
     *
     * <p>Polled rather than pushed, because a document goes dirty by being <em>typed into</em> and there is
     * no edit event to hang this on that would not also mean routing every keystroke through the workbench.
     * The cost is one string comparison per open document per frame, and it is only when the answer changes
     * that any element is touched.</p>
     */
    /**
     * Releases the document behind a panel that has just been closed.
     *
     * <h3>Only when nothing else is showing it</h3>
     *
     * <p>A document can have more than one tab — a split showing the same file twice, or a derived view
     * of it. Closing one must not release what the other is still drawing, so this asks the layout
     * whether any panel still names this resource before letting go.</p>
     *
     * <p>Unsaved work is not a consideration here, deliberately: the dock's close <b>guard</b> already
     * asked before anything got this far, and re-asking at release time would be a second prompt for one
     * decision.</p>
     */
    void releaseClosedPanel(DockPanelRef closed) {
        String raw = closed.state(DockPanelRef.PATH, "");
        if (raw.isEmpty()) return;
        CgPath path;
        try {
            Resource resource = Resource.parse(raw);
            // Only a project resource owns a document. A derived view is somebody else's business and
            // releasing its ORIGIN because a generated tab closed would take the graph with it.
            if (!resource.isProject()) return;
            path = resource.asPath();
        } catch (RuntimeException unparseable) {
            return;
        }
        for (DockLeaf leaf : workbench.dock.layout().leaves()) {
            for (DockPanelRef panel : leaf.panels()) {
                if (path.toString().equals(panel.state(DockPanelRef.PATH, ""))) return;
            }
        }
        long timed = FrameProfile.begin();
        // THE TAB'S REFERENCE, and nothing more. The document is disposed by its LAST holder, which may
        // be the Problems panel, an index or a background compile -- later than the tab, and never
        // earlier. That ordering is the "Parser is closed" defect, inverted.
        EditorService.Tab tab = workbench.editors.tabFor(EditorInput.of(Resource.of(path)));
        if (tab != null) workbench.editors.close(tab);
        FrameProfile.step(timed, "close.editors.close (release the tab's reference)");
        timed = FrameProfile.begin();
        workbench.onDidCloseDocument.emit(path);
        FrameProfile.step(timed, "close.onDidCloseDocument -> "
                + workbench.onDidCloseDocument.connectionCount() + " listeners");
    }

    void refreshDirtyMarkers() {
        List<CgPath> dirty = workbench.saveActions.unsavedFiles();
        if (!dirty.equals(workbench.lastDirty)) {
            workbench.lastDirty = dirty;
            refreshTabTitles();
        }
    }

}
