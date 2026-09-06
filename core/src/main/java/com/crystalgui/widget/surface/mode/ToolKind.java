package com.crystalgui.widget.surface.mode;

import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.widget.surface.SurfaceContext;

/**
 * <b>A tool in one declaration</b> — what {@code ToolWindowKind} is to a panel.
 *
 * <p>Describe it once and the engine derives the rest: the entry in the tool strip, the command that
 * makes it current, its accelerator, and the tool's own construction the first time it is picked.</p>
 *
 * <pre>{@code
 * Disposable hand = ctx.registerTool(
 *         ToolKind.of("mymod:hand", "Hand")
 *                 .icon("mymod:icons/hand")
 *                 .command("mymod.tool.hand", "H")
 *                 .tool(HandTool::new));
 * }</pre>
 *
 * <p>{@link #tool} is required — a kind with no factory is a strip entry that does nothing, and it is
 * refused at registration rather than on the click. The factory is called once per surface and the
 * instance kept, so a tool may hold gesture state between presses.</p>
 */
public final class ToolKind {

    private final String id;
    private final String displayName;

    @Nullable
    private String icon;
    @Nullable
    private String commandId;
    @Nullable
    private String accelerator;
    @Nullable
    private Function<SurfaceContext, Tool> factory;

    private ToolKind(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public static ToolKind of(String id, String displayName) {
        return new ToolKind(id, displayName);
    }

    public ToolKind icon(String icon) {
        this.icon = icon;
        return this;
    }

    /** The command that makes this tool current. Without one the tool is reachable only from the strip. */
    public ToolKind command(String commandId) {
        this.commandId = commandId;
        return this;
    }

    public ToolKind command(String commandId, String accelerator) {
        this.commandId = commandId;
        this.accelerator = accelerator;
        return this;
    }

    /** How the tool is built, once per surface. Required. */
    public ToolKind tool(Function<SurfaceContext, Tool> factory) {
        this.factory = factory;
        return this;
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
    public String commandId() {
        return commandId;
    }

    @Nullable
    public String accelerator() {
        return accelerator;
    }

    @Nullable
    public Function<SurfaceContext, Tool> factory() {
        return factory;
    }

    @Override
    public String toString() {
        return "ToolKind[" + id + "]";
    }
}
