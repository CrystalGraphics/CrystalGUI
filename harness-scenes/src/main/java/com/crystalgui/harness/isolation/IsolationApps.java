package com.crystalgui.harness.isolation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.crystalgui.core.async.UiSequence;
import com.crystalgui.desktop.app.AppContext;
import com.crystalgui.desktop.app.AppWindow;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.app.ApplicationKinds;
import com.crystalgui.desktop.app.ApplicationRegistry;
import com.crystalgui.desktop.app.WindowSpec;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * Test applications for the desktop's isolation: one per step of "Isolation", each on a document of its own, each
 * a way to make the shell prove a failed application costs only itself. They install in the harness's launcher only.
 *
 * <ul>
 *   <li><b>Drop Pad</b>: lists the files dropped on it, from the Crystal Editor's tree or a tab.</li>
 *   <li><b>Freezer</b>: stops answering by a sleep, an engine loop, a loop the engine cannot see, and a deadlock.</li>
 *   <li><b>Crasher</b>: throws from a handler, a task, a frame hook, a stack overflow, and again on every start.</li>
 *   <li><b>Won't Start</b> and <b>Missing Class</b>: a launch that throws, and one that fails to link.</li>
 * </ul>
 */
public final class IsolationApps implements ApplicationKinds {

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public IsolationApps() {
    }

    public static final ApplicationKind DROP_PAD = ApplicationKind.of("harness:drop-pad", "Drop Pad")
            .keywords("drop", "drag", "files", "isolation")
            .windowSize(360, 280).standalone().separateDocument().launch(DropPad::new);

    public static final ApplicationKind FREEZER = ApplicationKind.of("harness:freezer", "Freezer")
            .keywords("hang", "freeze", "not responding", "isolation")
            .windowSize(380, 300).standalone().separateDocument().launch(Freezer::new);

    public static final ApplicationKind CRASHER = ApplicationKind.of("harness:crasher", "Crasher")
            .keywords("crash", "fault", "stopped working", "isolation")
            .windowSize(380, 340).standalone().separateDocument().launch(Crasher::new);

    public static final ApplicationKind WONT_START = ApplicationKind.of("harness:wont-start", "Won't Start")
            .keywords("couldn't start", "launch", "isolation")
            .standalone().separateDocument().launch(context -> {
                throw new IllegalStateException("this application never starts");
            });

    public static final ApplicationKind MISSING_CLASS = ApplicationKind.of("harness:missing-class", "Missing Class")
            .keywords("linkage", "couldn't start", "isolation")
            .standalone().separateDocument().launch(context -> {
                throw new NoClassDefFoundError("com/example/MissingDependency");
            });

    @Override
    public void register(ApplicationRegistry applications) {
        applications.install(DROP_PAD);
        applications.install(FREEZER);
        applications.install(CRASHER);
        applications.install(WONT_START);
        applications.install(MISSING_CLASS);
    }

    /** A column of a note and buttons: the whole of each application's window. */
    private static final class Panel extends UIElement {
        Panel(String note) {
            layout(l -> l.flexDirection(FlexDirection.COLUMN));
            append(new UIText(note));
        }

        Button button(String label, Runnable press) {
            Button button = new Button(label);
            button.onPressed.connect(press);
            append(button);
            return button;
        }

        /** Runs {@code work} on the document's thread in its next frame, and once. */
        void nextFrame(Runnable work) {
            document().animation().every(this, delta -> {
                work.run();
                return false;
            });
        }
    }

    private abstract static class Base implements Application {
        private final ApplicationKind kind;
        private final AppWindow window;

        Base(AppContext context, ApplicationKind kind, String title, Panel content, int width, int height) {
            this.kind = kind;
            this.window = context.windows().open(WindowSpec.titled(title).content(content).size(width, height).at(60, 60));
        }

        @Override
        public ApplicationKind kind() {
            return kind;
        }

        @Override
        public AppWindow mainWindow() {
            return window;
        }

        @Override
        public boolean open(Resource resource) {
            return false;
        }

        @Override
        public void activate() {
        }

        @Override
        public void dispose() {
        }
    }

    /** Receives what is dropped on it. */
    private static final class DropPad extends Base {
        private final UIText received;
        private final List<String> names = new ArrayList<>();

        DropPad(AppContext context) {
            this(context, new Panel("Drag a file from the Crystal Editor's Project tree onto this window.\n"
                    + "A tab dropped on the bare desktop opens its own window."), new UIText("Nothing yet"));
        }

        private DropPad(AppContext context, Panel panel, UIText received) {
            super(context, DROP_PAD, "Drop Pad", panel, 360, 280);
            this.received = received;
            panel.append(received);
        }

        @Override
        public boolean open(Resource resource) {
            names.add(resource.name());
            received.setText("Received " + names.size() + ": " + String.join(", ", names));
            return true;
        }
    }

    /** Stops answering four ways; a press on a ghost, the box's Close and Wait are the test. */
    private static final class Freezer extends Base {
        Freezer(AppContext context) {
            this(context, new Panel("Press one, then click the window again.\nAfter 5 s it dims; then its box opens."));
        }

        private Freezer(AppContext context, Panel panel) {
            super(context, FREEZER, "Freezer", panel, 380, 300);
            panel.button("Busy 8 s (sleep, then recovers)", () -> sleep(8000));
            panel.button("Loop through the engine (Close aborts it cleanly)", () -> {
                while (true) UiSequence.checkpoint();
            });
            panel.button("Loop the engine cannot see (abandoned, 60 s)", () -> {
                long end = System.nanoTime() + 60_000_000_000L;
                while (System.nanoTime() < end) {
                    Thread.onSpinWait();
                }
            });
            panel.button("Deadlock (a lock cycle in the report)", Freezer::deadlock);
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private static void deadlock() {
            Object a = new Object();
            Object b = new Object();
            Thread other = new Thread(() -> {
                synchronized (b) {
                    sleep(200);
                    synchronized (a) {
                        return;
                    }
                }
            }, "isolation-deadlock");
            other.setDaemon(true);
            synchronized (a) {
                other.start();
                sleep(100);
                synchronized (b) {
                    return;
                }
            }
        }
    }

    /** Faults five ways; Details and Close are the test. */
    private static final class Crasher extends Base {
        /** Starts left that fault on their first frame: three makes a crash loop. */
        private static final AtomicInteger CRASHES_ON_START = new AtomicInteger();

        Crasher(AppContext context) {
            this(context, new Panel("The window dims and a box offers Details and Close.\nThe third fault in a minute says it keeps stopping."));
        }

        private Crasher(AppContext context, Panel panel) {
            super(context, CRASHER, "Crasher", panel, 380, 340);
            panel.button("Throw in this click handler", () -> {
                throw new IllegalStateException("thrown from a click handler");
            });
            panel.button("Throw in a task", () -> panel.document().execute(() -> {
                throw new IllegalStateException("thrown from a task");
            }));
            panel.button("Throw in the next frame", () -> panel.nextFrame(() -> {
                throw new IllegalStateException("thrown from a frame hook");
            }));
            panel.button("Overflow the stack", () -> recurse(0));
            panel.button("Crash the next 3 starts (a crash loop)", () -> CRASHES_ON_START.set(3));
            if (CRASHES_ON_START.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                panel.onConnected(() -> panel.nextFrame(() -> {
                    throw new IllegalStateException("thrown on start");
                }));
            }
        }

        private static int recurse(int depth) {
            return recurse(depth + 1) + 1;
        }
    }
}
