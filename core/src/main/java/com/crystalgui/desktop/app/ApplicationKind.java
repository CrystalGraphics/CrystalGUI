package com.crystalgui.desktop.app;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
    private final List<String> keywords = new ArrayList<>();
    @Nullable
    private final List<DocumentKind.Matcher> opens = new ArrayList<>();
    @Nullable
    private Function<LaunchContext, Application> factory;
    private boolean singleInstance;
    private boolean preventPinning;
    private boolean requiresConnection = true;
    private boolean startingWindow;
    @Nullable
    private Consumer<LaunchContext> autostart;
    private float windowWidth, windowHeight;
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

    /**
     * The size its main window opens at, in logical pixels, until the desktop has recorded one: the size an
     * application asks for when its window is made, as Windows' {@code CreateWindow} takes it.
     *
     * <pre>{@code
     * ApplicationKind.of("mymod:picker", "Picker").windowSize(820, 460).launch(...);
     * }</pre>
     *
     * <ul>
     *   <li>Only the main window ({@code WindowFrame.markApplicationMain}); another window sizes itself.</li>
     *   <li>A kind that names none opens at the desktop's default, most of the screen, centred.</li>
     *   <li>Clamped to the desktop like any size, so a small screen gets a smaller window.</li>
     * </ul>
     */
    public ApplicationKind windowSize(float width, float height) {
        check();
        this.windowWidth = width;
        this.windowHeight = height;
        return this;
    }

    /** @see #windowSize; 0 when it names none */
    public float windowWidth() {
        return windowWidth;
    }

    /** @see #windowSize; 0 when it names none */
    public float windowHeight() {
        return windowHeight;
    }

    /**
     * Other words this application answers to in the launcher's search.
     *
     * <p>The display name is always matched and needs no entry here; this is for what somebody types
     * when they do not know what the thing is called — {@code "fps"} and {@code "performance"} for a
     * profiler, {@code "code"} for an editor. freedesktop's {@code Keywords=} and macOS's Spotlight
     * aliases are the same idea.</p>
     *
     * <p><b>It exists because the launcher does.</b> This slot was deliberately withheld while nothing
     * read it: a manifest reads like a complete declaration, so an author writing
     * {@code .keywords("code")} against a launcher that never consulted it would reasonably conclude
     * their application was findable by it, and be wrong with no way to tell. {@code category(...)} is
     * still absent for exactly that reason — nothing groups applications yet.</p>
     */
    public ApplicationKind keywords(String... words) {
        check();
        if (words != null) {
            for (String word : words) {
                if (word != null && !word.isBlank()) keywords.add(word.trim());
            }
        }
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
     * Never pinned to the taskbar: its windows offer no Pin row. Windows' {@code PreventPinning}.
     *
     * <pre>{@code
     * ApplicationKind.of("mymod:viewer", "Viewer").preventPinning();   // only opened from a file
     * }</pre>
     *
     * <p>For an application that only makes sense opened from somewhere else, so a pin that launches it bare
     * would open nothing useful. One with no {@link #launch} factory is never pinnable anyway.</p>
     */
    public ApplicationKind preventPinning() {
        check();
        this.preventPinning = true;
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

    /**
     * Opens a starting window at once when launched: the application's frame with its icon growing in, about 1.45 s,
     * then the application built into it and revealed as the icon leaves. Android's starting window and Windows'
     * splash. Without it, a launch builds the application on the click and shows its window when it is ready.
     *
     * <pre>{@code
     * ApplicationKind.of("mymod:editor", "My Editor")
     *         .startingWindow()                    // its build holds the desktop for half a second
     *         .launch(ctx -> ...);
     * }</pre>
     *
     * <p><b>Opt in when the build takes 0.2 s or more</b> (a workbench, an editor indexing a project, a
     * tool loading assets), so the click is answered on its frame rather than by a frozen desktop; or when the
     * application simply wants the loading visuals. A quick one (a settings window, a log viewer) leaves it off:
     * a starting window for one that opens in a frame only delays it by the icon's 1.45 s.</p>
     *
     * <ul>
     *   <li>The build runs once the icon has come in, and holds the desktop while it runs, as any build does.</li>
     *   <li>Minimised meanwhile, the application builds at once and waits minimised; closed, it never builds.</li>
     *   <li>Its window takes the starting window's place, size and taskbar slot.</li>
     * </ul>
     */
    public ApplicationKind startingWindow() {
        check();
        this.startingWindow = true;
        return this;
    }

    /** @see #startingWindow() */
    public boolean hasStartingWindow() {
        return startingWindow;
    }

    /**
     * Work to do when a desktop starts, whether or not this application is ever opened.
     *
     * <p>freedesktop's autostart entry, for what an application must set up <b>before</b> its window
     * exists — a profiler that records from the first frame, a tool that restores a background service.
     * Run once per desktop, as soon as the desktop has storage, with this application's own
     * {@link LaunchContext#storage()} and no workspace.</p>
     *
     * <pre>{@code
     * ApplicationKind.of("mymod:profiler", "Profiler")
     *         .autostart(ctx -> MySettings.load(ctx.storage()).applyAtLaunch())
     *         .launch(ctx -> ...);
     * }</pre>
     *
     * <p>It must be quick and must not open a window: it runs inside the host's startup, before the
     * first frame.</p>
     */
    public ApplicationKind autostart(Consumer<LaunchContext> hook) {
        check();
        this.autostart = hook;
        return this;
    }

    @Nullable
    Consumer<LaunchContext> autostartHook() {
        return autostart;
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

    /** @see #keywords(String...) */
    public List<String> keywordList() {
        return List.copyOf(keywords);
    }

    @Nullable
    public String icon() {
        return icon;
    }

    public boolean isSingleInstance() {
        return singleInstance;
    }

    /** Whether a pin could start it again: it has a launch factory and does not {@link #preventPinning}. */
    public boolean isPinnable() {
        return factory != null && !preventPinning;
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
