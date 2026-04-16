# core/signal — Lifecycle-Safe Signal Primitives

> Root guide: [`AGENTS.md`](../../../../../../AGENTS.md)

## Purpose

Local typed signal/slot system for CrystalGUI widget wiring. Signals are **not** propagated through the DOM tree — they are strictly local state-change notifications.

## Files

| File | Role |
|------|------|
| `Signal.java` | Namespace class containing all signal variants as static inner classes |
| `SignalBase.java` | Shared connection machinery: slot storage, deferred disconnect-during-emit, debug hook |
| `Connection.java` | Disconnect handle interface (`@FunctionalInterface`) |
| `ConnectionGroup.java` | Bulk lifecycle cleanup — call `disconnectAll()` on element detach |

## Signal Variants

| Variant | Emit signature | Listener type | Use case |
|---------|---------------|---------------|----------|
| `Signal.Action` | `emit()` | `Runnable` | Button clicks, parameterless triggers |
| `Signal.Value<T>` | `emit(T)` | `Signal.Value.Listener<T>` | Hover state, single-value notifications |
| `Signal.Pair<A,B>` | `emit(A, B)` | `Signal.Pair.Listener<A,B>` | Property `(old, new)` change pairs |

## Key Rules

- **Single-thread only** — all connect/disconnect/emit on the UI thread.
- **No per-emit allocation** — uses indexed-for loop over live list with deferred disconnect.
- **Disconnect during emit is safe** — entries are marked disconnected and removed after emission completes.
- **`Connection` is lambda-compatible** — only `disconnect()` is abstract.
- **`UIElement.detachFromContainer()` unconditionally calls `connections.disconnectAll()`** — subclasses cannot skip cleanup.
- **`SignalBase.debugHook`** — optional static hook for connect/disconnect logging, wired by `CgUiDebug.setEnabled(true)`.
