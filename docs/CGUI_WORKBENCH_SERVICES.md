# CrystalGUI — Workbench services

The service layer under the dock, the workbench and the editor: the things a widget is allowed to
*ask* rather than reach through the application for.

**Read this before adding anything to `ui/elements/dock/`, `ui/elements/workbench/` or `editor/`.**
Most of what looks like a missing feature there is a service that already exists, and most of what
looks like a needed helper is the symptom of one that does not — see
[`plan.md`](../plan.md) for the architecture review this layer is being built from.

> **This document is the index of that layer. Every new service API lands here in the same commit
> that introduces it.** A capability nobody knows about gets re-implemented locally, which is exactly
> the accumulation `plan.md` exists to stop.

---

## Status

| Service | State | Section |
|---|---|---|
| `Disposer` — ownership and lifetime | **shipped** | [Disposal](#disposal) |
| `DataContext` — "what am I acting on" | **shipped** | [Data context](#data-context) |
| Typed service events — replacing polling | planned (plan §16) | — |
| `Resource` — URI schemes, virtual documents | planned (plan §17) | — |
| `DockPane` — retargetable panel views | planned (plan §18) | — |
| `DockService` — `open(input, placement)` | planned (plan §19) | — |

---

## Disposal

`com.crystalgui.core.dispose` — `Disposable`, `Disposable.Gl`, `Disposer`.

An ownership tree, ported from IntelliJ's `Disposer`. Register a child against a parent; disposing the
parent releases its children **first, in reverse registration order**, then the parent.

```java
Disposer.register(parent, child);      // child's lifetime is now part of parent's
Disposer.dispose(parent);              // children first, reverse order, then parent
Disposer.isDisposed(parent);           // idempotent — a second dispose is a no-op
Disposer.newDisposable("drag");        // a scope to hang things off when there is no natural owner
```

### What it is NOT

**It does not replace `CgGraphicsLifecycle.destroyContext()`.** That already sweeps every
CrystalGraphics registry in a documented order at context teardown, and nothing escapes the process.
Reaching for `Disposer` to "stop GL leaking at shutdown" is solving a solved problem.

It exists for the two things a registry structurally cannot do:

1. **Release on close rather than on exit.** A registry knows what exists, never what is still
   *wanted*. Closing a shader graph frees nothing unless somebody says so.
2. **Reach what no registry can see.** `CgPreviewRenderer.delete()` states it: *"the pool's targets
   are `createOwned`, so no registry sweeps them."* `CgUiPaintContext`'s layer FBO pool is the same.
   For that class, release depends on somebody remembering — which is the thing an ownership tree
   exists to stop depending on.

### Rules

- **Never call `dispose()` directly.** Go through `Disposer.dispose(x)`, or the children are skipped.
- **`dispose()` releases what you own *directly*.** Trust the tree for anything registered under you.
- **Make it idempotent.** The tree calls it once; a surviving hand-written `delete()` may call it again.
- **Do not throw for an ordinary reason.** A throw is caught and logged so siblings still release, but
  it leaves your own state undefined.
- **Own GPU memory? implement `Disposable.Gl`.** Freeing a GL object off the GL thread is silent
  corruption, not an exception. `Disposer` defers those to the GL thread; see below.

### The GL gate

`Disposable.Gl` disposals are routed to the GL thread rather than run wherever the request came from.

- `CgUiLifecycle.onInit` installs the gate — the thread receiving `onInit` *is* the GL thread.
- `CgUiLifecycle.onFrame` drains the queue; it is the one moment we are certainly on it.
- **The default gate runs everything immediately**, which is what keeps `Disposer` usable from
  `headlessTest` where there is no context. Do not "fix" that by requiring a gate.

### The disposal protocol for GL resources

**`dispose()` must mean "give back what I was using", not "delete GL objects".** Creating and
destroying GPU objects is expensive and driver-serialised; a design where closing a tab deletes
framebuffers and reopening it creates them is paying that cost for nothing.

So, in order of preference:

| Situation | What `dispose()` should do |
|---|---|
| The resource comes from a pool | **release the key back to the pool.** Delete nothing |
| The resource is genuinely unique and unshareable | delete it |
| The resource is a *reference* into something shared | drop the reference; the owner decides |

Pools are owned by the **context** (`CgGraphicsLifecycle`), never by a document or a panel. A document
owns *slots*, not GL objects. That way the GL object count is bounded by what is on screen rather than
by how many things happen to be open.

**What this looks like in practice.** Closing a shader graph should be bookkeeping — release its
preview keys, delete nothing — and reopening it should allocate nothing, because the pool still holds
the targets. The FBOs live as long as the context, which is the lifetime they should have had all
along.

#### The gap today

`CgPreviewSlots` is already the right primitive — capacity-bounded, LRU, with `acquire`/`release`/
`retainOnly`. It is instantiated **per `CgPreviewRenderer`, therefore per open graph**:

```java
this.targets = new CgPreviewSlots<>(capacity, () -> new CgPreviewTarget("cg_preview", size, samples));
```

So N open graphs hold N × capacity targets, and closing one deletes a whole pool. The decisive point
is that **only one graph is visible at a time** — the others are behind tabs and are not drawing — so a
single context-scoped pool of the same capacity would serve every graph with a fraction of the memory
and no allocation on tab switch.

Moving the pool up a level is a CrystalGraphics change and is tracked in `plan.md`; until it lands,
prefer **not disposing** a graph over disposing it, because the churn costs more than the retention.

#### The rule that follows

**Do not add `Disposable.Gl` to something just because it touches GL.** Ask first whether the resource
should be pooled instead. An object that returns slots to a shared pool does not need to be
`Disposable.Gl` at all — its release is ordinary bookkeeping and is safe on any thread. `Disposable.Gl`
is for the genuinely unique and unshareable, and the smaller that set is, the better the engine
behaves.

### Where it is wired today

| Owner | Owns | Notes |
|---|---|---|
| `CrystalEditor` | every `ShaderGraphEditor` it builds | replaced a `graphs` list that was never pruned, so every graph ever opened stayed reachable for the session |
| `ShaderGraphEditor` | its `MainPreviewPanel` | that panel's `delete()` had **no caller anywhere** — its `createOwned` target and meshes leaked for the life of the process |
| `OpenDocuments.close` | the document it drops | only reached today when a file is **deleted or moved**; closing a *tab* does not come through here yet |
| `CgUiLifecycle` | the GL gate and its queue | not an owner — the seam |

### What disposes, and what deliberately does not

| Action | Disposes? | Why |
|---|---|---|
| **Close a tab** | no | Nothing tells the panel — the step-3 gap. Also the *right* answer once pooling lands, since a closed graph should return slots rather than delete anything |
| **Rename / move a file** | **no** | Goes through `OpenDocuments.retarget`, not `close`. It is the same document at a new address; disposing and rebuilding would discard unsaved work and churn GL for a path change |
| **Delete a file** | **yes** | `WorkspaceFileService` closes what was open under it. The document is genuinely dead |
| **Close the editor** | yes | `Disposer.dispose(editor)` — the root of the tree |
| **Context destroyed** | yes | `CgGraphicsLifecycle`, which is where pooled GL objects are supposed to die |

### Known gap

**Closing a tab still does not release its document.** `DockArea.closePanel` tells the content nothing,
and `Workbench`'s `close(path)` is only reached on delete/move. The dock cannot fix this alone: panel
content is *shared* — the registry hands back the same `fileTree`, `problems` and cached editors — so
disposing on close would destroy the file tree the first time somebody closes the Project panel.

The missing half is the dock announcing a close so the workbench can drop the document. That is
`plan.md` step 3. Do not work around it locally.

---

## Data context

`com.crystalgui.core.data` — `DataKey`, `DataProvider`, `DataContext`. Standard keys in
`com.crystalgui.ui.UiDataKeys`.

How a command finds its subject without naming the widget that supplies it. Ported from IntelliJ's
`DataKey`/`DataProvider`/`DataContext`.

```java
// asking, from a command
GraphView graph = context.data().get(GraphView.GRAPH_VIEW);
if (context.data().has(UiDataKeys.SELECTION)) { … }

// answering, from a widget
@Override
public Object getData(DataKey<?> key) {
    if (key == GRAPH_VIEW) return this;
    return super.getData(key);        // ALWAYS last
}
```

### The rule

**First non-null answer of the right type, walking outward from the focused element.** The same rule
the keymap uses to resolve a binding and `UndoScope.nearest` uses to find a stack — so a keystroke, an
undo and a command all agree about what they are addressing.

This replaced three hand-rolled copies of the same walk (`GraphCommands.graphFor`,
`ShaderGraphEditor.editorFor`, `UndoScope.nearestScope`), each with a different `instanceof`. The point
is not that it is shorter: a widget can now *supply* a subject without **being** it, so a wrapper, a
preview or something not yet written can participate in a command written today.

### Rules for implementers

- **Answer for yourself only.** An element that answers on behalf of its children defeats the walk —
  inner elements are asked first precisely so the innermost answer wins.
- **Call `super.getData(key)` last**, or the generic `ELEMENT` answer stops being reachable.
- **Be cheap and side-effect free.** This runs while menus are built and palettes filtered — often, for
  many keys. Cache where the value changes, not here.
- **Declare a key where its concept lives.** `UiDataKeys` is for what the engine has an opinion about;
  a key belonging to one feature belongs with that feature (`GraphView.GRAPH_VIEW`,
  `ShaderGraphEditor.SHADER_GRAPH`).
- **Do not keep a `DataContext`.** It caches for one pass and is only valid for that pass.

### Two behaviours worth knowing

- **Internal children are walked.** Click-focus targets the exact element hit, which in a composite is
  one of its internal parts — a walk that skipped them would lose the subject for precisely the widgets
  built properly.
- **A wrong-typed answer is skipped, not fatal, and does not stop the walk.** Accepting it and casting
  to null afterwards would let one mistaken provider shadow a correct one further out; the command then
  reports "nothing selected" in a widget that plainly has a selection.

### Not yet here

The `when`-expression parser (`editorFocus && resourceExtname == .java`). Predicates over
`DataContext` cover commands today; the parser is only needed when keymaps want conditions, and it is
~1000 lines in VS Code with most of that being parsing. See `plan.md` §15.6.
