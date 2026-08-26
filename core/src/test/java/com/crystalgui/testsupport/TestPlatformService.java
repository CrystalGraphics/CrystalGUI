package com.crystalgui.testsupport;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.input.CgCursor;
import com.crystalgraphics.platform.service.CgCursorService;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.platform.service.CgReloadService;
import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.platform.service.CgResourceService;
import com.crystalgraphics.platform.service.CgSoundService;
import com.crystalgui.ui.elements.slot.NativeContentService;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link CgPlatformService} the unit tests run against — a mutable bundle whose UI-facing services
 * can be swapped per test.
 *
 * <h3>Why a mutable singleton rather than a fresh bundle per test</h3>
 * <p>{@link CgPlatform} reads every service through the one registered bundle and offers no per-service
 * setter, which is correct for production: a loader has all of this to hand at once, and half-registration
 * is the failure mode that shape rules out. A test wanting only a fake clipboard would otherwise have to
 * build a whole bundle, so instead there is one bundle, registered once, whose three UI services are
 * fields — {@link #input(CgInputService)}, {@link #sound(CgSoundService)}, {@link #cursor(CgCursorService)}.</p>
 *
 * <p>The six GL-facing services all answer {@code null}. Nothing in a unit test reaches them —
 * {@code CgPlatform.register} only stores {@code gl()} and {@code capabilities()} into static fields, and
 * {@code CgIO} explicitly null-checks {@code resources()} before falling through to the classpath, which
 * is the path these tests load stylesheets and fonts by.</p>
 *
 * <h3>Registration is idempotent and state is reset, both on purpose</h3>
 * <p>{@link #install()} runs from {@link UiTestBase}'s {@code @Before}, i.e. once per test method, and
 * resets the three services to their defaults each time. JUnit gives every test class a fresh instance but
 * <em>not</em> a fresh JVM, so without the reset a test that installed a recording cursor service would
 * leave it installed for every test that ran afterwards — the exact cross-test leakage that used to make
 * some of these classes pass only because an earlier one had filled in a static field.</p>
 */
public final class TestPlatformService implements CgPlatformService {

    /** Silence. Named rather than anonymous so a stack trace in a failing test says what it hit. */
    public static final CgSoundService SILENT_SOUND = soundId -> {};

    /** Shows nothing — the platform services no longer ship a shared no-op to borrow. */
    public static final CgCursorService NO_CURSOR = cursor -> {};

    /** A keyboard and mouse that report nothing pressed — what a test needs unless it says otherwise. */
    public static final CgInputService STUB_INPUT = new CgInputService() {
        @Override public int getCurrentModifiers() { return 0; }
        @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
        @Override public boolean isKeyDown(int localKeyCode) { return false; }
        @Override public int translateMouseCodes(int platformCode) { return platformCode; }
        @Override public boolean isMouseDown(int localMouseCode) { return false; }
        @Override public int howManyMouseButtons() { return 3; }
        // A REAL IN-MEMORY CLIPBOARD, not a pair of stubs. Copy was unobservable while setClipboard threw
        // its argument away, so anything that produced the wrong text produced it silently -- which is how
        // a list shipped copying a record's generated toString, object graph and all.
        @Override public String getClipboard() { return clipboard; }
        @Override public void setClipboard(String text) { clipboard = text == null ? "" : text; }
    };

    private static String clipboard = "";

    /** @see #install() */
    private static boolean contentServiceDeclared = false;

    private static final TestPlatformService INSTANCE = new TestPlatformService();

    private CgInputService input = STUB_INPUT;
    private CgSoundService sound = SILENT_SOUND;
    private CgCursorService cursor = NO_CURSOR;

    private TestPlatformService() {}

    /** Registers the bundle and resets its three UI services to their defaults. */
    public static TestPlatformService install() {
        INSTANCE.input = STUB_INPUT;
        INSTANCE.sound = SILENT_SOUND;
        INSTANCE.cursor = NO_CURSOR;
        CgPlatform.register(INSTANCE);
        // DECLARED, not merely absent. A slot throws on a platform that never said either way, which is
        // the whole point of that check -- so a test fixture has to answer, and the honest answer here is
        // that this source set has no Minecraft and renders no items. Saying so is what separates it from
        // a loader that forgot, and it is what lets a slot be painted in a test at all.
        //
        // ONCE PER JVM, unlike the three services above. `CgService.provide` announces every install on
        // stderr, deliberately -- a platform service being swapped under a running application is worth
        // seeing in a log. From a per-test @Before that is one line per test method, and at ~2,250 tests
        // it wrote enough to kill the Gradle worker outright: the build failed while every single test
        // passed, reporting a broken socket rather than anything about a test. A slot test that wants a
        // different service installs one itself and restores this in @After.
        if (!contentServiceDeclared) {
            contentServiceDeclared = true;
            CgPlatform.provide(NativeContentService.SERVICE, NativeContentService.UNSUPPORTED);
        }
        return INSTANCE;
    }

    /** The registered bundle, for a test that wants to swap a service mid-method. */
    public static TestPlatformService get() {
        return INSTANCE;
    }

    /** Replaces the input service. {@code null} restores {@link #STUB_INPUT}. */
    public TestPlatformService input(CgInputService inputService) {
        this.input = inputService != null ? inputService : STUB_INPUT;
        return this;
    }

    /** Replaces the sound service. {@code null} restores {@link #SILENT_SOUND}. */
    public TestPlatformService sound(CgSoundService soundService) {
        this.sound = soundService != null ? soundService : SILENT_SOUND;
        return this;
    }

    /** Replaces the cursor service. {@code null} restores {@link #NO_CURSOR}. */
    public TestPlatformService cursor(CgCursorService cursorService) {
        this.cursor = cursorService != null ? cursorService : NO_CURSOR;
        return this;
    }

    /**
     * Installs a cursor service that appends every cursor it is shown to {@code sink}.
     *
     * <p>Convenience for the common assertion shape — what the engine <em>resolved</em> is only observable
     * as the sequence of values it handed the platform.</p>
     */
    public List<CgCursor> recordCursors() {
        List<CgCursor> shown = new ArrayList<>();
        cursor(shown::add);
        return shown;
    }

    @Override public CgInputService input() { return input; }
    @Override public CgSoundService sound() { return sound; }
    @Override public CgCursorService cursor() { return cursor; }

    // ── Unused by unit tests; see the class javadoc ────────────────────────────────────────────────
    @Override public CgGLBackend gl() { return null; }
    @Override public CgGLContext capabilities() { return null; }
    @Override public CgResourceService resources() { return null; }
    @Override public CgRenderingService rendering() { return null; }
    @Override public CgLifecycleService lifecycle() { return null; }
    @Override public CgReloadService reload() { return null; }
}
