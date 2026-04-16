# core/property — Minimal Observable Property Model

> Root guide: [`AGENTS.md`](../../../../../../AGENTS.md)

## Purpose

Synchronous, equality-suppressing observable properties for CrystalGUI widget state. Used for `UiLabel` text/color and similar value-holder patterns.

## Files

| File | Role |
|------|------|
| `Property.java` | `Property<T>` with `get()`, `set()`, `changed` signal, one-way and bidirectional binding |

## Key Rules

- **Equality suppression** — `set()` only emits when `!Objects.equals(old, new)`.
- **No silent mutation** — every change goes through `set()` and emits if different.
- **Bidirectional binding** uses reentrancy guard to prevent infinite recursion.
- **Synchronous** — no batching, no async queue, no lazy/computed variants.
- **Phases 0–3 scope**: no `ComputedProperty`, `ReadOnlyProperty`, or `setSilent()`.
