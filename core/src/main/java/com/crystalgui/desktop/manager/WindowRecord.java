package com.crystalgui.desktop.manager;

import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.SnapZones;
import com.crystalgui.desktop.window.WindowFrame;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import javax.annotation.Nullable;

/**
 * What the window manager knows about one window: identity, state, geometry and stacking, apart from the frame
 * that draws it. A window is a record here and a chrome element in the shell; the manager and the session read
 * and write the record, never the element. (Chromium's {@code views::Widget} keeps the same split from its
 * {@code NativeWidget}.)
 *
 * <p>A plain value holder: it changes no element and emits nothing. {@link WindowFrame} owns the work a change
 * implies (the class it sets, the tooltip it rewrites, the registry it announces to), then stores the result here.
 * Reached with {@link WindowFrame#record()}.</p>
 *
 * <pre>{@code
 * WindowRecord window = frame.record();
 * if (window.state() == WindowState.VISIBLE && window.pinned()) { ... }
 * }</pre>
 */
@Getter
@Setter
@Accessors(fluent = true)
@RequiredArgsConstructor
public final class WindowRecord {

    /** The frame that draws this window. */
    private final WindowFrame frame;

    // Identity
    private String title = "";
    private boolean titleShown = true;
    @Nullable
    private String iconName;
    /** What a record can name this window by: its place is remembered under it. */
    @Nullable
    private String key;
    @Nullable
    private ApplicationKind application;
    private boolean applicationMain;
    /** The window this one belongs to: it minimises and closes with it, and stays above it. */
    @Nullable
    private WindowRecord owner;
    private boolean toolWindow;

    // State
    private WindowState state = WindowState.VISIBLE;
    private WindowPolicy policy = WindowPolicy.DESTROY_ON_CLOSE;
    private boolean pinned;
    private boolean maximized;
    private boolean fullscreen;
    private boolean maximizedBeforeFullscreen;
    @Nullable
    private SnapZones.Zone snappedZone;
    private boolean demandingAttention;
    @Nullable
    private String badge;
    /** 0..1, or negative for none. */
    private float progress = -1f;

    // Geometry and stacking
    /** What was asked for, never clamped. */
    private float wantedLeft, wantedTop;
    /** What was written, after clamping: what a drag and a resize measure from. */
    private float placedLeft, placedTop;
    /** Whether a position has been written at all; the cascade places the ones that have not. */
    private boolean placed;
    /** Whether a size has been written. */
    private boolean sized;
    /** Where to put the window back: the measured rect at the moment it was maximised. */
    private float restoreLeft, restoreTop, restoreWidth, restoreHeight;
    private int stackOrder;
}
