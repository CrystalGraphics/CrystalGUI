package com.crystalgui.desktop.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.document.DocumentKind;
import com.crystalgui.fs.Resource;

/**
 * <b>What an installed application IS</b> — freedesktop's {@code .desktop} entry, macOS's
 * {@code Info.plist}, Windows' AppUserModelID.
 *
 * <pre>{@code
 * ApplicationKind.of("crystalgui:editor", "Crystal Editor")
 *         .icon("crystalgui:logo")
 *         .keywords("code", "ide", "files")
 *         .opens(DocumentKind.FilePatterns.extension("java"))
 *         .launch(ctx -> WorkbenchApplication.of(ctx, CRYSTAL_EDITOR)
 *                 .with("crystalgui:notes", "crystalgui:scripting")
 *                 .title("Crystal Editor").key("editor:main"));
 * }</pre>
 *
 * <p>The point of a manifest is that it is <b>data</b>: a launcher can list what is installed, a search
 * can match it, and "open with" can answer, all without launching anything. That is what the tree had
 * no way to express — an application was a class a host constructed, so the only way to know it existed
 * was to build it.</p>
 *
 * <h3>What a second application costs</h3>
 *
 * <p>A different list of extensions and a different title. Not a class: the engine is
 * {@code WorkbenchApplication} and the difference between an editor and a graph-only product is which
 * ids each names. That is the whole claim the rewrite was for, and it is why {@link #launch} takes a
 * factory rather than a subclass.</p>
 */
public final class ApplicationKind {

    private final String id;
    private final String displayName;

    @Nullable
    private String icon;
    @Nullable
    private String category;
    private final Set<String> keywords = new LinkedHashSet<>();
    private final List<DocumentKind.Matcher> opens = new ArrayList<>();
    @Nullable
    private Function<LaunchContext, Application> factory;
    private boolean singleInstance;
    private boolean requiresConnection = true;
    private boolean frozen;

    private ApplicationKind(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** @param id namespaced and stable — a session record and a window key are both keyed on it */
    public static ApplicationKind of(String id, String displayName) {
        return new ApplicationKind(id, displayName);
    }

    public ApplicationKind icon(String iconName) {
        check();
        this.icon = iconName;
        return this;
    }

    /** What a search matches besides the name. A launcher's "code" finding an editor. */
    public ApplicationKind keywords(String... searchTerms) {
        check();
        for (String term : searchTerms) keywords.add(term);
        return this;
    }

    public ApplicationKind category(String category) {
        check();
        this.category = category;
        return this;
    }

    /**
     * The files this application opens — its associations.
     *
     * <p>Answerable with nothing running, which is the requirement: "open with" is asked of an
     * application that may never have been launched, so it cannot be derived by building one and asking
     * its workbench what kinds it registered.</p>
     */
    public ApplicationKind opens(DocumentKind.Matcher... files) {
        check();
        for (DocumentKind.Matcher matcher : files) opens.add(matcher);
        return this;
    }

    /** How one is started. Required — a manifest nothing can launch is a listing with no product. */
    public ApplicationKind launch(Function<LaunchContext, Application> factory) {
        check();
        this.factory = factory;
        return this;
    }

    /**
     * One instance at a time; launching again activates what is running.
     *
     * <p>Win32's single-instance mutex and macOS's {@code LSMultipleInstancesProhibited}. Right for a
     * workbench, whose whole promise is that closing it keeps everything: a second one would be a
     * second dock over the same documents.</p>
     */
    public ApplicationKind singleInstance() {
        check();
        this.singleInstance = true;
        return this;
    }

    /**
     * That this application is useless without a server, and a launcher should say so.
     *
     * <p>The default, because the applications here are workbenches over a workspace. One that is not
     * — a settings window, a log viewer — says {@link #standalone()} and is offered on a title
     * screen.</p>
     */
    public ApplicationKind standalone() {
        check();
        this.requiresConnection = false;
        return this;
    }

    private void check() {
        if (frozen) throw new IllegalStateException("'" + id + "' is registered; a manifest is data");
    }

    /** Called by the registry on install: a manifest that could change afterwards is not one. */
    void freeze() {
        frozen = true;
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
    public String category() {
        return category;
    }

    public Set<String> keywords() {
        return keywords;
    }

    public boolean isSingleInstance() {
        return singleInstance;
    }

    public boolean requiresConnection() {
        return requiresConnection;
    }

    /** Whether this application declares it opens {@code resource}. @see #opens */
    public boolean handles(Resource resource) {
        String name = resource.name();
        for (DocumentKind.Matcher matcher : opens) {
            if (matcher.matches(name)) return true;
        }
        return false;
    }

    @Nullable
    Function<LaunchContext, Application> factory() {
        return factory;
    }
}
