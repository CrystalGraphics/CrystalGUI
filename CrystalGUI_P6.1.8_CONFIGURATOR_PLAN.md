# P6.1.8 — The Configurator

**Planned 2026-08-01.** Supersedes the four-line sketch in `CrystalGUI_P6_TODO.md` §6.1.8, which said
"point it at an object, get an editing UI" and left everything else open.

> **Why this is being planned properly rather than improvised.** The immediate consumer is P6.3.6, a
> library of ~170 shader nodes drawn from a kit of about a dozen controls. A control that is a pixel
> wrong is wrong in every node that uses it, and wrong in a way that costs a review round *per node* to
> find, because nothing fails. The colour picker took a full session of nitpicking to settle — the
> point of this item is that it should be close to the last session of that kind.

---

## Contents

1. [The one architectural call](#the-one-architectural-call)
2. [What the research settled](#what-the-research-settled)
3. [The visual spec](#the-visual-spec)
4. [Package and types](#package-and-types)
5. [The control catalogue](#the-control-catalogue)
6. [The three hosts](#the-three-hosts)
7. [Tokens](#tokens)
8. [How this stays correct](#how-this-stays-correct)
9. [Work order](#work-order)
10. [Deliberately not doing](#deliberately-not-doing)
11. [Open questions](#open-questions)

---

## The one architectural call

**A control and a row are two different things, and LDLib2 conflates them.**

In LDLib2, `Configurator` *is* the row — it owns a `label`, an `inlineContainer`, and a `tip`, and every
concrete editor extends it. `NumberConfigurator` is a row containing a number field; there is no number
field on its own. That is a perfectly good design for an inspector, and it is the wrong one here,
because we have **three hosts and only one of them is a row**:

```
ConfigControl  ─── the bare editor. No label, no row, no idea where it is mounted.
     │
     ├── Configurator          label + control, stacked in a panel        ← inspector
     ├── node control row      label + control, node-scoped sizing        ← node properties
     └── port-attached editor  control alone, floating beside a port      ← node input defaults
```

Unity's own editor is the evidence: in `docs/research/unity-inspector/07-full-window.png`, marker **C**
is a master node whose `Object Space` dropdown, `X 0.5` float and colour swatch float to the *left of
their ports*, while marker **F** is an inspector showing the same dropdown, float and swatch as labelled
rows. One widget, two hosts, different anchor and different sizing.

Conflate them and the port-attached host can reuse nothing — which is precisely the trap, because that
host is the more common one in a node graph. So: **`ConfigControl` is the unit of reuse; the row is a
wrapper around it.**

---

## What the research settled

Three independent sources agree on the control set, which is the strongest signal available that the
list is right and neither short nor padded.

| Source | Where | What it gave |
|---|---|---|
| Unity Shader Graph, 169 nodes | [`docs/research/UNITY_SHADER_GRAPH_NODES.md`](docs/research/UNITY_SHADER_GRAPH_NODES.md) | 13 widget kinds cover every node; port and control tables for 15 |
| Unity UI Toolkit's control reference | 44 named editor controls | An independent list that collapses onto the same 13 |
| LDLib2's configurator | `research_repos/LDLib2/.../configurator/` | 27 concrete widgets, 34 type accessors, and a base-row design worth porting |

**LDLib2's `@ConfigNumber` range is Unity's Slider `Min`/`Max`. Its `@ConfigHDR` is Unity's Color node
`Mode: HDR`.** Two systems built years apart, without reference to each other, arrived at the same
options on the same two controls. That is what a settled design looks like, and it is why porting is
the right move here rather than deriving.

### Ported wholesale from LDLib2

- **The row anatomy** — `line[label, inline, tip]` plus a nested content area below it. Our internal-child
  convention already spells this: `__label__`, `__inline__`, `__tip__`.
- **`ValueConfigurator<T>`'s seam** — `Supplier<T>` in, `Consumer<T>` out, plus a default. The widget
  never touches a document.
- **Copy/paste per row**, from the context menu, gated by a type predicate.
- **`ConfiguratorGroup`** — a foldout owning a child container.
- **A single change event** rather than a listener per concrete type.

### Deliberately *not* ported

- **`ConfiguratorParser` and the annotations.** See [Deliberately not doing](#deliberately-not-doing).
- **Sprite-textured chrome.** Ours is CSS.
- **`moveInlineAsDefault()` on everything.** We have the call; LDLib2 uses it as a reflex. Used where a
  theme genuinely should be able to override, not everywhere.

---

## The visual spec

Measured off `docs/research/unity-inspector/01-inspector-property.png` and `07-full-window.png`, which
are 1:1 screenshots. Numbers are Unity's at its own scale; ours land in the token block and are the
single place they get argued about.

### The form row

```
┌─────────────────────────────────────────────────────┐
│  Exposed          [✓]                               │   ← toggle, control left-aligned in its column
│  Reference        [Vector3_53e07ad7601e4…       ]   │   ← text, control FILLS its column
│  Default          [X 0 ][Y 0 ][Z 0 ]                │   ← vector, sub-fields share the column
│  Precision        [ Inherit                    ▾]   │   ← dropdown, fills
└─────────────────────────────────────────────────────┘
   └── label column ──┘└────── control column ────────┘
```

- **Label column is fixed-width and left-aligned**, not right-aligned. Unity's is ~40% of the panel.
  Controls therefore start on a common left edge, which is what makes a stack of unlike controls read
  as a form rather than as a pile.
- **One row height for every control kind.** In `07-full-window.png` the `Material` dropdown, the
  `Allow Material Override` checkbox and the `Workflow Mode` dropdown are all on one rhythm. This is
  the same defect we already fixed for node control rows, where a text field was 16px, a dropdown 14
  and a checkbox 12.
- **The control column fills**, except for the toggle, which is square and sits at the column's left.
- **Sub-labels are inside the control** — `X`, `Y`, `Z` are part of the vector control, not three rows.

### Groups, headers and lists

- **Section header** (`Target Settings`): bold, full width, no control, no arrow.
- **Foldout** (`▼ Universal`): a disclosure arrow, and children indented under it. LDLib2 draws a border
  around the child container; Unity indents only. **Take Unity's** — a border per group turns a deep
  panel into a stack of boxes.
- **List** (`Active Targets`, and the `Entries` list in `03-inspector-keyword-enum.png`): a header bar,
  a body, and a `+` / `−` pair at the **bottom right**. Rows carry a reorder handle on the left. An
  empty list shows a placeholder row — `List is Empty` — rather than collapsing to nothing, which is
  what makes an empty list still a drop target and still obviously a list.

### The object field

From `04-inspector-custom-function.png`: `[icon] None (Text Asset)              (⊙)` — an icon, the
value or a typed placeholder, and a picker button hard right, inside the same box.

---

## Package and types

`com.crystalgui.ui.elements.config` — **not** under `graph/`, because the graph is one of three
consumers and the least general.

```
config/
  ConfigControl          abstract UIElement. The bare editor. No label.
  ValueControl<T>        ConfigControl + Supplier<T>/Consumer<T>/default + copy-paste + change signal
  Configurator           the ROW: label + a ConfigControl + tip. Not a superclass of controls.
  ConfiguratorGroup      a foldout Configurator owning a child container
  ConfiguratorPanel      the scrollable stack of rows — the inspector surface
  ConfigDescriptor       what to build: id, label, tooltip, type, options, range, flags
  ConfigControls         registry: descriptor -> control. The one extension point.

  control/               the thirteen (see below)
```

### `ConfigControl` — the contract

```java
public abstract class ConfigControl extends UIElement {
    /** Fires when the USER changes the value. Never on a programmatic set — see below. */
    public final Signal.Value<Object> changed;

    /** Push a value in without echoing back out. */
    public abstract void setValueObject(Object value);
    public abstract Object getValueObject();

    /** True when this control brings its own label (a colour swatch, a matrix grid) and the host must
     *  not add one. The CONTROL decides, because only it knows whether it self-describes — the same
     *  rule GraphNode.addControl already follows for FULL_WIDTH_CLASS. */
    public boolean selfLabelling() { return false; }
}
```

> **`changed` must not fire on a programmatic set**, or a host that pushes a value in response to a
> change gets an infinite echo. `ColorSelector` already carries the `updating` guard this needs, and
> the same shape goes on the base class so no concrete control has to remember it.

### The typed/string seam, and why it lands where it does

`ValueControl<T>` is **typed**. The node graph is **string-valued** — a `NodeField` holds
`vec4(1.0, 0.5, 0.0, 1.0)`, which is GLSL text.

The adapter goes on the graph side, not the config side, and the reason is not symmetric: a
configurator over a POJO has real types available and would be throwing them away to speak in strings,
whereas the graph *already* parses and formats (`ShaderColorFieldWidget.parseVec4` / `formatVec4`) and
must keep doing so whatever we choose — the document is text. So `NodeFieldWidgets` becomes a thin
codec layer over `ConfigControls`, and `NodeField.Kind` maps to a `ConfigDescriptor`.

---

## The control catalogue

Thirteen, each with the Unity reference to build against.

| # | Control | Reference | Built from | Notes |
|---|---|---|---|---|
| 1 | `NumberControl` | `03-scalar-float.png` | `TextField` | Int/float. Drag-to-scrub on the label is Unity's, and is a follow-up, not v1 |
| 2 | `VectorControl` | `04-vector2/3/4.png` | N × `NumberControl` | Sub-labels `X Y Z W` inside the control |
| 3 | `BooleanControl` | `05-toggle.png` | `Checkbox` | The one control that is square and does not fill |
| 4 | `TextControl` | `08-validated-text.png` | `TextField` | Optional validator + `setMaxLength` |
| 5 | `SelectControl` | `06-dropdown-*.png` | `Dropdown` | Must scroll — Blend has 22 options |
| 6 | `ColorControl` | `01-color-field.png` | **`ColorSelector`** ✅ | Already built. Add the HDR mode |
| 7 | `SliderControl` | `02-slider-with-range.png` | `Slider` + `NumberControl` | Range is **per instance**, not per type |
| 8 | `MaskControl` | `07-mask-dropdown.png` | `Dropdown` + `Checkbox` | Multi-select; reads `Everything` when full |
| 9 | `MatrixControl` | `11-matrix-grid.png` | N×N `NumberControl` | Self-labelling; no row label |
| 10 | `AssetControl` | `04-inspector-custom-function.png` | `TextField` + `Button` | Path + picker. Browser is 6.1.10 |
| 11 | `GradientControl` | `10-gradient-field.png` | new | **The expensive one.** Stops, alpha stops, interpolation |
| 12 | `ConfiguratorGroup` | `07-full-window.png` **F** | `UIElement` | Foldout. Structure, not a value |
| 13 | `ArrayControl` | `03-inspector-keyword-enum.png` | **`ListView`** ✅ | Reorder + `+`/`−` + empty state |

**Six of the thirteen are assembly over widgets that already exist**, and two (`ColorControl`,
`ArrayControl`) are close to free. `GradientControl` is the only genuinely new drawing work.

---

## The three hosts

| Host | Class | Anchor | Sizing | Label |
|---|---|---|---|---|
| Inspector | `Configurator` in a `ConfiguratorPanel` | stacked rows | control column fills | fixed label column |
| Node property | `GraphNode.addControl` | inside the node | node width | inline, left |
| Node input default | **new** — `NodePort` hosts it | floats left of the port | self-sized, ~60px | the port's own name |

The third does not exist and is the one blocking a faithful node library. Two facts about it, both
already available to us:

- **Visibility is `nodeport:blank`** — the pseudo-class already exists and `graph.css` already drives
  the hollow-vs-filled dot from it. Connect something and the editor goes; disconnect and it returns.
- **It is outside the node's box**, so it must not contribute to the node's width. That is what keeps a
  Lerp narrow, and it is why Unity's node height is a function of port count rather than of how many
  inputs happen to be unconnected.

---

## Tokens

`--cfg-*` in `default.css`, alongside the `--graph-*` block added on 2026-08-01. Separate from
`--graph-*` because an inspector row is roomier than a node row and they will not stay equal — a node
is 134px wide and an inspector panel is not.

```
--cfg-row-h        the one height every control lands on
--cfg-ctrl-h       the control inside the row
--cfg-label-w      the label column (Unity: ~40% of the panel)
--cfg-gap          label to control
--cfg-pad-x        the panel's horizontal inset
--cfg-indent       one foldout level
--cfg-font
```

Reminder from the parser, since it decides how this is written: variables are collected **per sheet**
and substitution is **textual and single-level** — no `calc()`, no fallback form, no variable referring
to another. Derived numbers are written out with the derivation in a comment.

---

## How this stays correct

The assembly line's guard rails. `NodeControlKitTest` already exists and generalises to
`ConfigKitTest`:

1. **Every kind is registered.** Enumerate the descriptor types; a type with no control is a *named*
   failure rather than a silent gap.
2. **The kit is a set.** Every control lands on `--cfg-ctrl-h`. This has already caught one real
   defect — three controls at three heights in one node.
3. **A composite keeps its internal geometry in every host.** The generalisation of the colour-picker
   leak: `graphnode .__control-row__ textfield` reached into a promoted picker, tied on specificity so
   the width was won on source order, and left the grow factor uncontested. Verified to bite — removing
   the defence reports the picker's field at 53.5px against 20. **Every new composite goes in the
   `COMPOSITES` list.**
4. **Round-trip, per control.** `format(parse(x))` stable, `parse` tolerant of garbage. One
   parameterised test over the whole catalogue kills a bug class rather than a bug.
5. **A gallery page showing all thirteen at once**, reviewed against the Unity images side by side —
   **once**, rather than per node.

> **What this cannot promise.** Not zero visual review — the harness is the only GL surface, and
> `core/src/test` has no context to dump PNGs from. What it promises is collapsing the review from
> ~170 nodes to 13 controls, done once, up front.

---

## Work order

Sequenced so each step is testable and nothing is built twice.

| # | Step | Why here |
|---|---|---|
| 1 | `ConfigControl`, `ValueControl<T>`, `ConfigControls`, `ConfigDescriptor` | The seam. Everything else is a leaf on it |
| 2 | Tokens + the row: `Configurator`, `ConfiguratorPanel` | The form host, so a control has somewhere to be seen |
| 3 | The six assembly controls — Number, Vector, Boolean, Text, Select, Slider | All over existing widgets; this is where the kit becomes a kit |
| 4 | `ConfiguratorGroup` + `ArrayControl` | Structure. `ArrayControl` is over `ListView`, already built |
| 5 | **Gallery page + the review sitting** | Before anything depends on the look. This is the one human gate |
| 6 | `ColorControl` (adopt `ColorSelector`), `MaskControl`, `MatrixControl`, `AssetControl` | The four remaining leaves |
| 7 | `NodeFieldWidgets` becomes a codec layer over `ConfigControls` | The graph adopts the kit; nothing visual changes |
| 8 | **Port-attached host** on `NodePort` | The mechanism 6.3.6 is actually blocked on |
| 9 | `GradientControl` | Deferred to last: expensive, and no node in the near set needs it |

Step 5 is the gate. Everything before it is structure; everything after it inherits a look that has
already been signed off.

---

## Deliberately not doing

- **The annotation-driven reflection driver.** `@Configurable`, `ConfiguratorParser`,
  `IConfiguratorAccessor`. The node graph has no object to reflect over — `NodeField` already states
  kind, label, options and default, so the driver would serve nothing that exists today. The
  descriptor/registry seam is deliberately shaped so it can be added later without touching a control.
  *This is the half of LDLib2's design that gives 6.1.8 its name, and it is the half nothing needs yet.*
- **A curve editor.** In Unity's control list (`CurveField`) and in no shader node.
- **Drag-to-scrub on number labels.** Unity has it and it is genuinely good; it is a follow-up.
- **The asset browser.** `AssetControl` takes a path and offers a picker button; the browser behind it
  is 6.1.10, which is blocked on its own open question about what a filesystem means here.
- **Node Settings as a second surface.** Unity splits on-node controls from inspector-only ones
  (`Sampler State`'s anisotropic filter, `Custom Function`'s port list). Worth stealing eventually —
  it is what stops a node growing fifteen controls — but it needs an inspector to exist first.

---

## Open questions

| Question | Notes |
|---|---|
| Does a control own its undo, or does the host? | LDLib2 puts `EditAction` on the row. Our `UndoStack` belongs to a *document*, and a control has none — so the host almost certainly owns it. Settle before step 1, because it decides whether `changed` carries enough to build an `Edit` from |
| Label column: fixed px or a percentage? | Unity's is ~40%. A percentage keeps a narrow panel usable; a fixed column keeps two panels aligned. Probably percentage with a min |
| Is `MaskControl`'s option list ever dynamic? | Unity's `Channel Mask` derives its options from the *resolved* input width. That needs the control and the type resolver to talk, which nothing else in the kit does |
| Does the port-attached host reuse `Configurator` or bypass it? | It has a label (the port's) but not a row. Leaning bypass — `NodePort` hosts a bare `ConfigControl` |
| One `ConfigControls` registry, or one per host? | One, with the host deciding chrome. A second registry is how the graph and the inspector drift apart |

---

## References

- [`docs/research/UNITY_SHADER_GRAPH_NODES.md`](docs/research/UNITY_SHADER_GRAPH_NODES.md) — 169 nodes, 13 widget kinds, the six missing mechanisms
- [`docs/research/unity-inspector/`](docs/research/unity-inspector/README.md) — 8 inspector screenshots, indexed
- [`docs/research/unity-nodes/`](docs/research/unity-nodes/README.md) — 26 node screenshots, indexed by widget
- `research_repos/LDLib2/src/main/java/com/lowdragmc/lowdraglib2/configurator/` — the prior art, in-repo
- `core/src/test/java/com/crystalgui/ui/NodeControlKitTest.java` — the guard rail, already live
