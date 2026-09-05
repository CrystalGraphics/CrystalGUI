package com.crystalgui.desktop.app;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.document.DocumentKind;
import com.crystalgui.fs.Resource;

/**
 * An installed application's <b>manifest</b> — what it is called, what it opens, and how to start it.
 *
 * <p>The same job as freedesktop's {@code .desktop} entry, macOS's {@code Info.plist} or Windows'
 * AppUserModelID: everything about a product that can be answered <b>without launching it</b>. Declare
 * one as a {@code static final} beside your application and hand it to the desktop through an
 * {@link ApplicationKinds} service.</p>
 *
 * <pre>{@code
 * public static final ApplicationKind KIND = ApplicationKind.of("mymod:editor", "My Editor")
 *         .icon("mymod:logo")
 *         .opens(DocumentKind.FilePatterns.extension("java"))
 *         .singleInstance()
 *         .launch(ctx -> WorkbenchApplication.of(ctx)
 *                 .with("crystalgui:explorer", "crystalgui:problems")
 *                 .title("My Editor")
 *                 .key("myeditor:main")
 *                 .start());
 * }</pre>
 *
 * <h3>Who reads it</h3>
 *
 * <p>{@link ApplicationRegistry#installed()} lists it for a launcher; {@link ApplicationRegistry#handlerFor}
 * matches {@link #opens} to answer "open with"; {@link ApplicationRegistry#launch} checks
 * {@link #isSingleInstance()} before starting a second one and {@link #requiresConnection()} before
 * starting one at all. The builder is frozen the first time any of them asks, so a manifest cannot
 * change under a desktop that has already listed it.</p>
 *
 * <h3>A second product is a list, not a subclass</h3>
 *
 * <p>The engine behind a workbench-shaped application is always {@code WorkbenchApplication}; what makes
 * an editor different from a graph-only tool is which extension ids it names and what it calls itself.
 * That is why {@link #launch} takes a factory rather than a class to extend.</p>
 */
public final class ApplicationKind {

    private final String id;
    private final String displayName;

    @Nullable
    private String icon;
    @Nullable
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

    // `keywords(...)` and `category(...)` ARE NOT HERE, and their absence is the same call as
    // `action(...)`'s: a slot nothing sets and nothing reads is the write-only failure this repository
    // documents five times over, and it is worse on a manifest than elsewhere because a manifest READS
    // like a complete declaration -- an author writing `.keywords("code")` would reasonably conclude the
    // launcher can find their application by it. Both arrive at W8, with the launcher and the desktop
    // search that are their only consumers.

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
