# ui/elements — Reusable Widget Subclasses

> Root guide: [`AGENTS.md`](../../../../../AGENTS.md)

## Purpose

Concrete `UIElement` subclasses providing visual and interactive UI building blocks.

## Files

| File | Role |
|------|------|
| `UiPanel.java` | Filled rectangle drawn via draw-list; base class for `UiButton` and `UiTextbox` |
| `UiButton.java` | Clickable button: `clicked` (Signal.Action), `hoverChanged` (Signal.Value\<Boolean\>), `FocusPolicy.CLICK` |
| `UiLabel.java` | Property-backed text label with Taffy `MeasureFunc` for intrinsic sizing |
| `UiTextbox.java` | Single-line text input: `textChanged` (Signal.Value\<String\>), `submitted` (Signal.Action), `FocusPolicy.CLICK`, caret navigation, backspace/delete |

## Key Rules

- **UiButton** wires DOM event listeners (CLICK, MOUSE_ENTER, MOUSE_LEAVE) internally; consumers connect to signals.
- **UiButton** suppresses `clicked` signal when `enabled == false`.
- **UiLabel** measurement is pure CPU via `CgTextLayoutBuilder` — no GL context touched during layout.
- **UiLabel** sets `hitTestVisible = false` — clicks pass through to parent (buttons, panels).
- **UiLabel** caches its text layout and validates cache against box dimensions to prevent staleness.
- **UiTextbox** listens for KEY_DOWN and KEY_TYPED events internally; supports backspace, delete, left/right, home/end, enter, Ctrl+A.
- **UiTextbox** visually highlights when focused (2px border in `focusBorderColor`) and draws a 1px caret at `caretPosition`.
- **UiTextbox** exposes text as `Property<String>` with `textChanged` signal on every edit and `submitted` signal on Enter.
- **UiPanel** is not property-ified in phases 0–3.
