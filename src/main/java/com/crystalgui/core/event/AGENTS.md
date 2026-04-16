# core/event — DOM-Style Routed Event System

> Root guide: [`AGENTS.md`](../../../../../../AGENTS.md)

## Purpose

Three-phase routed event dispatch (capture → target → bubble) for CrystalGUI input handling, plus direct-target dispatch for non-bubbling events.

## Files

| File | Role |
|------|------|
| `UiEvent.java` | Base event class with routing metadata, propagation control, `preventDefault()` |
| `UiEventType.java` | Enum of 13 event types, each with `bubbles()` flag |
| `UiMouseEvent.java` | Mouse/pointer payload: position, button, modifiers, scrollDelta, clickCount |
| `UiKeyEvent.java` | Keyboard payload: keyCode, modifiers, character |
| `UiEventDispatcher.java` | Stateless dispatch: `findTarget()`, `dispatchRoutedEvent()`, `dispatchDirectEvent()` |
| `UiEventListener.java` | `@FunctionalInterface` for event handlers |
| `UiEventPhase.java` | Enum: `CAPTURE`, `TARGET`, `BUBBLE` |
| `CgUiKeyCodes.java` | Platform-agnostic key constants: `KEY_TAB`, `KEY_BACKSPACE`, `KEY_ENTER`, `KEY_DELETE`, `KEY_LEFT`, `KEY_RIGHT`, `KEY_HOME`, `KEY_END`, `KEY_A` |
| `Modifiers.java` | Bitmask constants: `SHIFT`, `CTRL`, `ALT`, `SUPER` with query helpers |
| `CgUiDebug.java` | Centralized debug logger for all CrystalGUI subsystems (guarded by `enabled` flag) |

## Key Rules

- **Coordinates are absolute container-space** — matching `LayoutContext.updateLayoutBoxes()` output.
- **Non-bubbling events** (`MOUSE_ENTER`, `MOUSE_LEAVE`, `FOCUS_IN`, `FOCUS_OUT`) use `dispatchDirectEvent()` only.
- **Hit test** checks reverse child order (last = topmost); invisible and hit-test-disabled elements are skipped.
- **No LWJGL/GLFW imports** — `CgUiKeyCodes` and `Modifiers` are platform-agnostic.
- **`CgUiDebug`** is wired into all hot paths: hit-test, dispatch phases, propagation stop, signal connect/disconnect (via `SignalBase.debugHook`), property set/bind, focus transitions. Enable via `CgUiDebug.setEnabled(true)`.
