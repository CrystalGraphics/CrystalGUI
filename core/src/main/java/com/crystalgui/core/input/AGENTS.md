# core/input — Container-Scoped Interaction Layer

> Root guide: [`AGENTS.md`](../../../../../../AGENTS.md)

## Purpose

Owns all mutable pointer/keyboard interaction state for a single `UIContainer`. `UIContainer` directly owns `UiInputManager` and `FocusManager` as fields (no intermediate façade class).

## Files

| File | Role |
|------|------|
| `UiInputManager.java` | Pointer ingress: one hit-test per move, hover enter/leave synthesis, click/double-click synthesis |
| `FocusManager.java` | Focus tracking: requestFocus, tab/shift-tab traversal, key dispatch, auto-blur on detach/disable/hide |
| `FocusPolicy.java` | Enum: `NONE`, `FOCUSABLE`, `CLICK` |

## Key Rules

- **One hit-test per mouse move** — resolved target drives both hover and move routing.
- **Click synthesis** — requires matching press/release target + button within distance threshold.
- **Double-click** — resets click counter after emission so next click starts at count 1.
- **Focus is container-scoped** — one focused element per `UIContainer`.
- **Focus auto-clears** when focused element detaches, becomes invisible, or becomes disabled.
- **Tab traversal** uses document order over visible + enabled + focusable elements.
- **Key events** route through focused element via full capture/target/bubble dispatch.
- **Access from callers**: `container.getInputManager()` and `container.getFocusManager()` directly.
