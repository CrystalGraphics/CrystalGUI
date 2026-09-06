package com.crystalgui.widget.surface.overlay;

/**
 * A way of looking at the whole surface — outline only, isolate the selection, difference against a
 * reference. Exactly one is current at a time, or none.
 *
 * <p>Declared with {@code ViewModeKind}, which derives the toggle command and its accelerator. The
 * engine calls {@link #enter} when it becomes current and {@link #exit} when it stops, including when
 * another view mode replaces it.</p>
 *
 * <pre>{@code
 * ctx.registerViewMode(ViewModeKind.of("mymod:outline", "Outline")
 *         .command("mymod.view.outline", "Ctrl+Shift+O")
 *         .mode(OutlineMode::new));
 * }</pre>
 *
 * <p>{@link #exit} must undo everything {@link #enter} did: a view mode is a lens, never an edit, and
 * nothing it writes reaches the document.</p>
 */
public interface ViewMode {

    void enter();

    void exit();
}
