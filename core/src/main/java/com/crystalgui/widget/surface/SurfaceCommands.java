package com.crystalgui.widget.surface;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.ClipboardActions;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.widget.canvas.WorldRect;
import com.crystalgui.widget.surface.edit.Clipboard;
import com.crystalgui.widget.surface.edit.Clipboards;

/**
 * The ten things every surface can do: delete, select all, deselect, frame, and the clipboard four.
 *
 * <p>Registered once per process and resolved from the data context, so one set serves every open
 * surface — the graph's and the builder's. A consumer adds only what is <em>its own</em>: connecting two
 * ports is a graph command and has no meaning on a tree.</p>
 *
 * <pre>{@code
 * SurfaceCommands.register();                      // from registerCommands, once per class
 * SurfaceCommands.bindDefaults(keymap());          // on the surface, so bare letters are scoped
 * }</pre>
 *
 * <p>The keys are bound on the <b>surface</b> rather than declared on the commands, which is what scopes
 * {@code Delete} and {@code F} to a focused surface: bound globally they would be live over every text
 * field in the application.</p>
 */
public final class SurfaceCommands {

    public static final String DELETE = "surface.delete";
    public static final String SELECT_ALL = "surface.selectAll";
    public static final String DESELECT = "surface.deselect";
    public static final String FRAME_SELECTION = "surface.frameSelection";
    public static final String FRAME_ALL = "surface.frameAll";
    public static final String CUT = "surface.cut";
    public static final String COPY = "surface.copy";
    public static final String PASTE = "surface.paste";
    public static final String DUPLICATE = "surface.duplicate";

    /** World units between a duplicate and its original, so the copy is visible rather than exact. */
    private static final float PASTE_OFFSET = 24f;

    /** Breathing room when framing. World units, because framing is a view operation. */
    private static final float FRAME_PADDING = 24f;

    private SurfaceCommands() {
    }

    public static void register() {
        CommandRegistry.global().contribute(SurfaceCommands.class, SurfaceCommands::declare);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(DELETE, "Delete")
                .run(context -> with(context, SurfaceCommands::delete))
                .enabledWhen(context -> hasSelection(surfaceOf(context))));
        registry.register(Command.of(SELECT_ALL, "Select All")
                .run(context -> with(context, surface ->
                        surface.selection().replaceWith(surface.surface().items())))
                .enabledWhen(context -> surfaceOf(context) != null));
        registry.register(Command.of(DESELECT, "Deselect")
                .run(context -> with(context, surface -> surface.selection().clear()))
                .enabledWhen(context -> hasSelection(surfaceOf(context))));
        registry.register(Command.of(FRAME_SELECTION, "Frame Selection")
                .run(context -> with(context, SurfaceCommands::frameSelection))
                .enabledWhen(context -> surfaceOf(context) != null));
        registry.register(Command.of(FRAME_ALL, "Frame All")
                .run(context -> with(context, surface -> surface.surface().fit()))
                .enabledWhen(context -> surfaceOf(context) != null));

        registry.register(Command.of(COPY, "Copy")
                .run(context -> with(context, surface -> Clipboards.store(copyOf(surface))))
                .enabledWhen(context -> canCopy(surfaceOf(context))));
        registry.register(Command.of(CUT, "Cut")
                .run(context -> with(context, surface -> {
                    Object clip = copyOf(surface);
                    if (clip == null) return;
                    Clipboards.store(clip);
                    surface.selection().clear();
                }))
                .enabledWhen(context -> canCopy(surfaceOf(context))));
        registry.register(Command.of(PASTE, "Paste")
                .run(context -> with(context, SurfaceCommands::paste))
                .enabledWhen(context -> canPaste(surfaceOf(context))));
        registry.register(Command.of(DUPLICATE, "Duplicate")
                .run(context -> with(context, surface -> pasteInto(surface, copyOf(surface))))
                .enabledWhen(context -> canCopy(surfaceOf(context))));
    }

    /**
     * The engine's keys, bound on one surface.
     *
     * <p>Bare letters and {@code Delete} are scoped by being bound here; the clipboard chords are
     * modified, so they keep working while something inside an item has focus.</p>
     */
    public static void bindDefaults(Keymap keymap) {
        keymap.bind("Delete", DELETE);
        keymap.bind("Backspace", DELETE);
        keymap.bind("Mod+A", SELECT_ALL);
        keymap.bind("Escape", DESELECT);
        keymap.bind("F", FRAME_SELECTION);
        keymap.bind("A", FRAME_ALL);
        keymap.bind("Mod+C", COPY);
        keymap.bind("Mod+X", CUT);
        keymap.bind("Mod+V", PASTE);
        keymap.bind("Mod+D", DUPLICATE);
    }

    /**
     * Cut, copy and paste for the shell, derived from a surface's {@link Clipboard}.
     *
     * <p>What the Edit menu and the application's own Ctrl+C reach through the data context. Every
     * surface used to hand-write this adapter; now it says what a fragment is and gets the rest.</p>
     */
    public static ClipboardActions actionsFor(SurfaceEditor surface) {
        return new ClipboardActions() {
            @Override
            public boolean canCut() {
                return SurfaceCommands.canCopy(surface);
            }

            @Override
            public void cut() {
                CommandRegistry.global().run(CUT, CommandContext.of(surface));
            }

            @Override
            public boolean canCopy() {
                return SurfaceCommands.canCopy(surface);
            }

            @Override
            public void copy() {
                CommandRegistry.global().run(COPY, CommandContext.of(surface));
            }

            @Override
            public boolean canPaste() {
                return SurfaceCommands.canPaste(surface);
            }

            @Override
            public void paste() {
                CommandRegistry.global().run(PASTE, CommandContext.of(surface));
            }
        };
    }

    // ── What the commands do ────────────────────────────────────────────────

    /** Deleting is the consumer's; the engine knows only that it acts on the selection. */
    private static void delete(SurfaceEditor surface) {
        List<UIElement> doomed = surface.selection().items();
        if (doomed.isEmpty()) return;
        surface.edits().gesture("delete",
                () -> surface.edits().apply(surface.surfacePolicy().deleteEdit(doomed)));
        surface.selection().clear();
    }

    /** The selection, or everything when nothing is selected — what Unity binds F and A to. */
    private static void frameSelection(SurfaceEditor surface) {
        List<UIElement> selected = surface.selection().items();
        surface.surface().frame(surface.geometry().boundsOf(
                selected.isEmpty() ? surface.surface().items() : selected), FRAME_PADDING);
    }

    @Nullable
    private static Object copyOf(SurfaceEditor surface) {
        Clipboard<?> clipboard = surface.clipboard();
        return clipboard == null ? null : clipboard.copy();
    }

    private static void paste(SurfaceEditor surface) {
        Clipboard<?> clipboard = surface.clipboard();
        if (clipboard == null) return;
        pasteInto(surface, Clipboards.stored(clipboard.type()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void pasteInto(SurfaceEditor surface, @Nullable Object clip) {
        Clipboard clipboard = surface.clipboard();
        if (clipboard == null || clip == null || !clipboard.type().isInstance(clip)) return;
        // AT THE POINTER when it is over this surface, and at an offset otherwise: a command knows who
        // invoked it, and the input service holds where the pointer is.
        UIDocument window = surface.document();
        if (window != null) {
            var pointer = window.input().pointer();
            if (surface.surface().contains(pointer.x(), pointer.y())) {
                var world = surface.surface().toWorld(pointer.x(), pointer.y());
                clipboard.paste(clip, world.x(), world.y());
                return;
            }
        }
        clipboard.pasteBy(clip, PASTE_OFFSET, PASTE_OFFSET);
    }

    // ── Resolving the subject ───────────────────────────────────────────────

    @Nullable
    private static SurfaceEditor surfaceOf(CommandContext context) {
        return context.data().get(SurfaceEditor.SURFACE);
    }

    private static void with(CommandContext context, Consumer<SurfaceEditor> action) {
        SurfaceEditor surface = surfaceOf(context);
        if (surface != null) action.accept(surface);
    }

    private static boolean hasSelection(@Nullable SurfaceEditor surface) {
        return surface != null && !surface.selection().isEmpty();
    }

    private static boolean canCopy(@Nullable SurfaceEditor surface) {
        return surface != null && surface.clipboard() != null && !surface.selection().isEmpty();
    }

    private static boolean canPaste(@Nullable SurfaceEditor surface) {
        return surface != null && surface.clipboard() != null
                && Clipboards.has(surface.clipboard().type());
    }
}
