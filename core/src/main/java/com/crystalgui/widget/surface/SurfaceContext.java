package com.crystalgui.widget.surface;

import javax.annotation.Nullable;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.widget.config.inspector.InspectorSection;
import com.crystalgui.widget.surface.insert.InsertSource;
import com.crystalgui.widget.surface.mode.ToolKind;
import com.crystalgui.widget.surface.overlay.OverlayKind;
import com.crystalgui.widget.surface.overlay.ViewModeKind;
import java.util.List;

import com.crystalgui.widget.surface.edit.Clipboard;
import com.crystalgui.widget.surface.edit.Edits;
import com.crystalgui.widget.surface.mode.Cursors;
import com.crystalgui.widget.surface.mode.Modes;
import com.crystalgui.widget.surface.mode.ToolKind;
import com.crystalgui.widget.surface.overlay.Geometry;
import com.crystalgui.widget.surface.overlay.OverlayKind;
import com.crystalgui.widget.surface.overlay.OverlayLayer;
import com.crystalgui.widget.surface.overlay.Snapping;
import com.crystalgui.widget.surface.select.Picking;
import com.crystalgui.widget.surface.select.SurfaceSelection;

/**
 * <b>What a surface extension is written against</b> — the editor's surface, without the editor's class.
 *
 * <p>Everything a feature may do to a surface is here, and everything it registers hands back a
 * {@link Disposable} so a feature that goes takes its tools, overlays and commands with it. A feature
 * that cannot be written against this interface is a hole in the seam: the fix is a method here, never
 * a reach around it.</p>
 *
 * <pre>{@code
 * public final class GridExtension implements SurfaceExtension {
 *     public String id() { return "mymod:grid"; }
 *
 *     public Disposable activate(SurfaceContext ctx) {
 *         return ctx.registerOverlay(OverlayKind.of("mymod:grid", "Grid")
 *                 .inPlane().visibleByDefault()
 *                 .element(c -> new GridOverlay(c.surface())));
 *     }
 * }
 * }</pre>
 *
 * <p>A consumer extends this with its own questions — a graph adds its document, its wires and its
 * ports; a builder adds its artboards, its extras and its problems — and its features are written
 * against that. Anything generic enough for both stays here.</p>
 */
public interface SurfaceContext {

    /** The plane, the view onto it, and the theme. */
    Surface surface();

    /** What is selected, and the one signal that says it changed. */
    SurfaceSelection selection();

    /** The one door every change goes through, and the transactions that make a gesture one step. */
    Edits edits();

    /** Cut, copy and paste, as this consumer means them. Null when it has no notion of a fragment. */
    @Nullable
    Clipboard<?> clipboard();

    /** What is under a point, and what a band touches. */
    Picking picking();

    /** Which tool is current, and the input mode that feeds it. */
    Modes modes();

    /** Rectangles to draw from, read after layout. */
    Geometry geometry();

    /** What is drawn over the plane, and whether each is showing. */
    OverlayLayer overlays();

    /** Where a dragged value settles. */
    Snapping snapping();

    /** What the pointer looks like while a gesture owns it. */
    Cursors cursors();

    /** Every tool registered here, in registration order — what a tool strip lists. */
    List<ToolKind> tools();

    /** Every overlay registered here, in registration order — what a View menu lists. */
    List<OverlayKind> overlayKinds();

    Disposable registerTool(ToolKind kind);

    Disposable registerOverlay(OverlayKind kind);

    Disposable registerViewMode(ViewModeKind kind);

    Disposable registerInsertSource(InsertSource source);

    Disposable registerDropHandler(DropHandler handler);

    /** Adds a section to the inspector. Withdrawn when the handle is disposed. */
    Disposable registerSection(InspectorSection section);

    /** Adds a command, which resolves its subject from the data context like any other. */
    Disposable registerCommand(Command command);

    /**
     * The consumer's own {@link SurfacePolicy}, for its own extensions.
     *
     * <p>A graph feature asks for {@code GraphPolicy.class} and gets the answers only a graph has.
     * Asking for a type this surface's policy is not is a programming error and throws.</p>
     */
    <T> T policy(Class<T> type);
}
