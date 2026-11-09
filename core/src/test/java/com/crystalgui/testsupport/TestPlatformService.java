package com.crystalgui.testsupport;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.input.CgClipboard;
import com.crystalgraphics.platform.input.CgInput;

import java.util.function.IntPredicate;
import java.util.function.IntSupplier;

/**
 * The platform the unit tests run against: an empty GL bundle (nothing in a unit test reaches GL), and a keyboard,
 * mouse and clipboard a test can swap.
 *
 * <pre>{@code
 * TestPlatformService.install();                                   // UiTestBase's @Before: nothing held, empty clipboard
 * TestPlatformService.holdModifiers(CgModifiers.CTRL);             // a chord
 * TestPlatformService.get().devices(TestPlatformService.holding(() -> mods, button -> dragging));
 * CgClipboard.set("x"); assertEquals("x", CgClipboard.get());      // the clipboard is a real one, in memory
 * }</pre>
 *
 * <ul>
 *   <li>{@link #install()} runs once a test method and resets everything: JUnit gives a fresh instance, not a fresh
 *       JVM, so a fake left installed would leak into every later test.</li>
 *   <li>The clipboard keeps what is set, so a copy is observable: a list once shipped copying a record's
 *       {@code toString}, unseen while writes were dropped.</li>
 * </ul>
 */
public final class TestPlatformService implements CgPlatformService {

    private static final TestPlatformService INSTANCE = new TestPlatformService();

    private static int modifiers;
    private static String clipboard = "";

    /** Nothing held but {@link #holdModifiers}; three buttons; the engine's own codes. */
    public static final CgInput.Host STUB_DEVICES = holding(() -> modifiers, button -> false);

    /** An in-memory clipboard. */
    public static final CgClipboard.Host MEMORY_CLIPBOARD = new CgClipboard.Host() {
        @Override public String get() { return clipboard; }
        @Override public void set(String text) { clipboard = text == null ? "" : text; }
    };

    private TestPlatformService() {
    }

    /** A keyboard and mouse holding {@code modifiers}' answer and the buttons {@code mouseDown} accepts; no key held. */
    public static CgInput.Host holding(IntSupplier modifiers, IntPredicate mouseDown) {
        return new CgInput.Host() {
            @Override public boolean isKeyDown(int key) { return false; }
            @Override public boolean isMouseDown(int button) { return mouseDown.test(button); }
            @Override public int modifiers() { return modifiers.getAsInt(); }
            @Override public int mouseButtons() { return 3; }
            @Override public boolean pointerGrabbed() { return false; }
            @Override public int key(int toolkitKey) { return toolkitKey; }
            @Override public int mouseButton(int toolkitButton) { return toolkitButton; }
        };
    }

    /**
     * Holds a modifier mask down, as though the user held the keys: without it every keystroke reads unmodified and a
     * chord is unreachable. Reset by {@link #install()}.
     */
    public static void holdModifiers(int mask) {
        modifiers = mask;
    }

    /** Registers the bundle and resets the keyboard, mouse and clipboard. */
    public static TestPlatformService install() {
        modifiers = 0;
        clipboard = "";
        CgPlatform.register(INSTANCE);
        CgPlatform.provide(CgInput.HOST, STUB_DEVICES);
        CgPlatform.provide(CgClipboard.HOST, MEMORY_CLIPBOARD);
        return INSTANCE;
    }

    /** The registered bundle, for a test that swaps a device mid-method. */
    public static TestPlatformService get() {
        return INSTANCE;
    }

    /** Replaces the keyboard and mouse; null restores {@link #STUB_DEVICES}. */
    public TestPlatformService devices(CgInput.Host devices) {
        CgPlatform.provide(CgInput.HOST, devices != null ? devices : STUB_DEVICES);
        return this;
    }

    /** Replaces the clipboard; null restores {@link #MEMORY_CLIPBOARD}. */
    public TestPlatformService clipboard(CgClipboard.Host host) {
        CgPlatform.provide(CgClipboard.HOST, host != null ? host : MEMORY_CLIPBOARD);
        return this;
    }

    @Override public CgGLBackend gl() { return null; }
    @Override public CgGLContext capabilities() { return null; }
}
