# P6.3 — The Shader Node Graph

**The track that turns the editor into a shader compiler.** P6.2 built a general-purpose node editor
with a typed, serialisable document. P6.3 makes a *shader* out of one, and builds whatever
CrystalGraphics needs in order to run the result.

> **`CRYSTALSHADER_MANIFESTO.md` is not the spec.** It was written before any CrystalShader code
> existed. What it owns is research findings and the vision — both still valuable — but its cornerstone
> list is a guess from before the ground was known, and several of its claims are contradicted by what we
> actually built. **This document is the spec.** Where the two disagree, this one is right, and the
> disagreements are called out explicitly below so nobody has to reconcile them twice.

---

## Where we actually stand — audited 2026-07-31, not assumed

Every line here was checked against the code, because the manifesto's own status claims are stale.

### What CrystalGraphics already does

| Capability | State |
|---|---|
| `.shader` format: `#type`, `Properties`, `v2f`, multiple `Pass`, `RenderState`, `Tags` | **Shipped** |
| Parse → compile → link → cache, keyed per keyword variant | **Shipped** (`CgShaderParser`, `CgMaterialShaderCompiler`, `CgMaterialShaderRegistry`) |
| `CgMaterial`: property store, `bind()`, keywords, `nextPass` chain, attached SSBO/TBO/UBO | **Shipped** |
| `cg_env.glsl`: frame block, per-instance object data, SSBO/TBO dual path, attribute aliases | **Shipped** |
| Engine buffers opted into by `#pragma cg_use` (`quad`, `curve`) | **Shipped** |
| GLSL stdlib: `math`, `vector`, `color`, `uv`, `noise`, `sdf`, `stroke` | **Shipped** |
| Hot reload on F3+T, shadow/depth pass auto-generation, MRT via `: RT0` | **Shipped** |
| Property types the parser accepts | `float`, `vec2`, `vec3`, `vec4`, `int`, `boolean`, `color`, `Range`, `sampler2D`, `sampler2DArray`, `sampler3D`, `samplerCube` |

**So the runtime is not the problem.** A `.shader` file that a compiler emits will load, compile, cache
and draw today, provided it is a file.

### What CrystalGUI already does that 6.3 gets for free

- **`GraphDocument`** — typed ports, stored ids, property values, cycle rejection at connect time, and
  **`topologicalOrder()`**, which is literally step one of a graph compiler. Headless and tested.
- **`GraphCodecs`** — content-addressed round-trip through `PlainOps`/`JsonOps`, schema-versioned.
- **`GraphIds`** — random, 10 chars, **letter-first by explicit design "so the id is a legal identifier
  in generated code"**. 6.2.5 anticipated this exactly; namespacing needs no new id scheme.
- **`ContentHash`** — which is what makes preview caching tractable (see 6.3.7).
- **`NodeTypeRegistry`** — id → type, search, and compatible-port filtering, already driving the create
  menu.

### The gaps, verified

1. **A material cannot be built from a generated source string.** `CgMaterialShader`'s only constructor
   takes a `resourcePath` and loads through `CgIO`; `CgMaterialShaderRegistry.getOrCreate(String)` keys
   on that path. `CgShaderParser.parse(String)` *does* exist, so the parser is ready and only the asset
   layer above it is not. **This is the one hard blocker in the whole track** — a compiler emits a
   string, and today there is nowhere to put it but a file.
2. **No render-to-target path for a single mesh.** `CgRenderPipeline` is shaped for the MC frame:
   `executeOpaquePass(partialTicks, sourceFboId)` then `executeTransparentPass()`, drawing into
   whatever is bound. There is no "render this one thing into that target with this camera, now", which
   is what a node preview is.
3. **No node-type concept anywhere.** `CgShaderNode`, `CgShaderGraph`, `CgGraphCompiler` do not exist —
   confirmed by search, not by reading the roadmap.

---

## Settled decisions

### 1. The graph is the source of truth; `.shader` is a generated artefact

**Decided 2026-07-31.** The document is stored in `GraphDocument`'s existing serialised format; the
shader is output.

This **contradicts the manifesto**, which claims *"The text-based `.shader` format … IS the node graph's
serialization format … A `.shader` file you author can be loaded into the node graph editor and
displayed as nodes. No separate formats."* That is not achievable and should stop being repeated:
loading arbitrary hand-written GLSL back into nodes is a **decompiler**, and a hand-written shader
contains loops, local variables, branches and helper functions that no finite node set expresses. Unity
reached the same conclusion — `.shadergraph` is JSON, the generated shader is a build artefact.

What survives of the manifesto's intent, and is worth keeping:

- **A graph and a hand-written shader produce the same *kind* of thing.** Both end at a `.shader` the
  same runtime loads. No parallel material system.
- **The generated GLSL stays readable and inspectable.** Namespaced, commented, with the node id in the
  comment. "Show generated code" is a first-class feature, not a debug aid.
- **Hand-written `.shader` files remain fully supported** and are never second-class. They are simply
  not round-trippable into nodes.

**The consequence to accept up front:** a user who edits generated GLSL by hand loses it on the next
compile. Unity marks generated files read-only. We should do the same, or emit into a directory that is
obviously derived.

### 2. The compiler emits text, and text is the seam

The compiler's output is a `.shader` source string. That is already an interface both sides understand,
so it adds **no new coupling in either direction** — CrystalGraphics never learns what a graph is, and
the compiler never learns what GL is. It also means the whole compiler is testable in `headlessTest`
with no GL context: *the assertion is the emitted string*.

### 3. A node type is an INTERFACE, whose common implementation is data

> **Corrected 2026-07-31 after reading Godot and Unity.** The first draft of this document said "data,
> not classes". That is wrong, and both battle-tested implementations say so independently.

Godot defines every built-in node as a **C++ subclass of `VisualShaderNode`** implementing
`generate_code(mode, type, id, input_vars[], output_vars[], for_preview)`. Unity defines every built-in
node as a **C# subclass of `AbstractMaterialNode`**. Two independent teams, both with the option of a
data format, both chose code — so the question is *why*, and the answer is not laziness:

- **Dynamic port types.** Unity's `Add` accepts float, vec2, vec3 or vec4 and resolves its output type
  from its inputs. A purely declarative node cannot express "output type = the wider of my inputs", and
  the alternative is four nodes per operation.
- **Variable port counts**, precision variants, and nodes whose emitted code depends on a setting.

**But both also ship a data path, for user-extension:** Godot has `VisualShaderNodeExpression` (inline
GLSL) and `VisualShaderNodeCustom` (script-defined); Unity has the **Custom Function Node** with a
**String mode** (inline body) and a **File mode** that *injects an include and calls a function*.

So the shape both converged on, and the one to port:

```java
interface CgShaderNode {
    List<PortSpec> inputs();
    List<PortSpec> outputs();
    String generateCode(CodeContext ctx);   // ctx carries resolved input/output var names + forPreview
}
```

- **`TemplateShaderNode implements CgShaderNode`** — the declarative JSON case, covering the large
  majority of nodes, loaded from resources. This is still where the library lives and still means a mod
  ships nodes without code.
- **A Java implementation** where the behaviour is genuinely dynamic.
- **`ExpressionNode`** — inline GLSL typed by the user, which is Godot's escape hatch and costs nothing
  once the interface exists.

The interface is what makes the dynamic case *possible at all*; the data implementation is what makes
the common case *cheap*. Choosing only one of them is the mistake.

**Unity's File mode is the include answer from 6.3.2**, already battle-tested: a node does not inline a
function body, it emits an `#include` and a call. That is exactly the per-node declared-include design,
arrived at independently.

---

## Prior art — read 2026-07-31, and what we take from each

| Source | Licence | How we may use it |
|---|---|---|
| **Godot `VisualShader`** | **MIT** | **Port outright.** A node graph emitting GLSL inside a game engine — the closest match to our problem that exists, and legally reusable. |
| **Unity Shader Graph** | Unity Companion (source-available) | **Read for shape only**, same rule this project applies to Zed. Its UX and its Custom Function Node design are the reference; its code is not. |
| **Blender node system** | GPL | Shape only. |

### What the research settled

**1. `generate_code`'s signature is the whole architecture.** Godot hands a node the *already-resolved
variable names* for its inputs and outputs and asks for a GLSL snippet back. The node never sees the
graph, never learns what it is connected to, and cannot get namespacing wrong. That inversion is the
single thing most worth stealing.

**2. Previews go through the same emitter, with a flag.** Godot's parameter is literally
`bool p_for_preview`. This document's 6.3.3 independently argued "one traversal parameterised by root,
not two code paths" — that is now confirmed rather than asserted, and it is why previews are a *flag*
in 6.3.7 rather than a second compiler.

**3. Implicit type conversion is the compiler's job**, and unconnected inputs get their defaults baked
into the emitted GLSL. Both were guesses in the first draft; both are what Godot does.

**4. Namespacing is node-id + port.** Godot's `make_unique_id` does exactly this. Ours is *better
positioned*: Godot's node ids are ints assigned by the editor, while `GraphIds` are stable, stored, and
already guaranteed to be legal GLSL identifiers.

**5. Dynamic port types are the reason nodes are code** — see the settled decision above.

> **Resolved 2026-07-31, by where the IR lives rather than by a decision.** This was written as a
> prerequisite for 6.3.2, on the grounds that CrystalGUI's `PortSpec` carries a fixed `typeId` and would
> need a `dynamic` member. Putting `CgShaderGraph` in CrystalGraphics removes the question: dynamic is a
> property of `CgShaderNode`, and `com.crystalgui.graph` did not change at all. The editor can still
> resolve a concrete type for colouring, because it depends on CrystalGraphics and calls the same code.

## The items

### 6.3.1 Materials from generated source · `DONE` (2026-07-31)

> **Shipped**: `CgMaterial.fromSource(String)`, keyed on `CgContentHash` of the source. Smaller than
> planned because both ends were already designed for it — `recompile()` documented "No-op when
> resourcePath is null (programmatic / shader-graph shaders)" and `CgMaterial.fromShader` was annotated
> for exactly this. Only the middle was missing.
>
> Hot reload skips generated shaders: F3+T means "re-read the files" and these have none. Their source
> cannot change without the owner emitting different text, which is a different hash and so a different
> asset. **10 GL-free tests.**

Everything else is gated on this. `CgMaterialShader` must be constructible from a source string with no
file behind it.

**Design**

- `CgMaterial.fromSource(String name, String source)` and `CgMaterialShaderRegistry.getOrCreate(name,
  source)`, keyed on the **content hash of the source**, not on a name. Two graphs that compile to
  identical GLSL must share one program — which is exactly the case a preview grid produces.
- The existing path stays: a resource-backed shader is a source-backed shader whose source came from
  `CgIO`. Ideally `getOrCreate(path)` becomes a thin wrapper, so there is one compile path rather than
  two that can drift.
- **Hot reload has to be told what to do with these.** `CgAssetReloader` re-reads every material from
  disk on F3+T; a generated one has no disk to re-read from and must be skipped rather than blanked.
  The natural rule: a source-backed shader is invalidated by its *owner* recompiling the graph, never by
  the resource reloader.
- **Lifecycle**: generated programs accumulate. A graph edited a hundred times must not leave a hundred
  live programs — eviction by content hash with a reference count, or an explicit `release()`.

**Traps**

1. **`CgMaterialShader` caches a `hasCompileFailed` latch cleared only by `markDirty()`.** A generated
   shader that fails must surface the error to the *editor*, not merely log it — see 6.3.8.
2. Content-hash keying makes an edit that changes nothing (moving a node) recompile nothing. That is a
   feature, and it is free because the hash is of the emitted GLSL, not of the graph.

---

### 6.3.2 `CgShaderNode` — the node type as an interface · `DONE` (2026-07-31)

> **Shipped**: `CgShaderType` (+ promotion and widening), `CgShaderPort`, `CgShaderNode`,
> `CgNodeCodeContext`, `CgTemplateShaderNode`, `CgShaderDomain` — in `com.crystalgraphics.shadergraph`.
>
> **The blocker dissolved rather than being decided.** Putting the IR in CrystalGraphics means dynamic
> port types are a property of `CgShaderNode`, so `com.crystalgui.graph` did not change at all — the
> editor can still resolve a concrete type for colouring, because it depends on CrystalGraphics and can
> call the same code.
>
> **`CgShaderType` is deliberately NOT `CgMaterialProperty.Type`.** The latter is a property-authoring
> vocabulary carrying two names (`color` compiles to `vec4`, `Range` to `float`) plus storage semantics;
> this one is what flows along a wire and needs matrices, `DYNAMIC` and promotion rules. They are bridged
> by the GLSL name and pinned by `CgShaderTypeBridgeTest` so they cannot drift.
>
> That test immediately earned its keep: **GLSL spells it `bool`, a Properties block spells it
> `boolean`**, so emitting the GLSL name produces a file the parser rejects. Hence
> `CgShaderType.propertyTypeName()`.

A declarative node definition, loaded from `assets/{ns}/shaders/nodes/*.json`.

```jsonc
{
  "id": "cg:math/multiply",
  "label": "Multiply",
  "category": "Math/Basic",
  "synonyms": ["times", "product"],
  "domain": "any",                    // vertex | fragment | any
  "inputs":  [ { "id": "A", "type": "vec3", "default": "(0,0,0)" },
               { "id": "B", "type": "vec3", "default": "(1,1,1)" } ],
  "outputs": [ { "id": "Out", "type": "vec3" } ],
  "body": "{Out} = {A} * {B};"
}
```

**What the template language must support, and nothing more**

- `{PortId}` substitution for inputs (an upstream variable, or the literal default) and outputs (the
  node's own namespaced variable).
- `{prop:Name}` for a node property that becomes a shader `Property` rather than a constant.
- Multi-statement bodies, because a real node is rarely one line.
- **Optional helper functions** emitted once per graph regardless of how many instances use them —
  noise, hashes, matrix builders. Without this, ten noise nodes emit ten copies.

**Deliberately not a scripting language.** A template is substitution and nothing else. The moment it
grows conditionals it becomes a language with no debugger, and the escape hatch already exists: a node
whose body is too complex calls a function from the GLSL stdlib.

**A node declares its own `#include`s**, and the compiler emits the union required by the nodes actually
present in the graph. This is not tidiness — it is the answer to a cost that only appears here.

> **Why whole-file includes are fine today and stop being fine in 6.3.** `CgShaderPreprocessor` expands
> an `#include` wholesale; the seven stdlib files total 641 lines. At runtime that costs **nothing** —
> GLSL compilers dead-strip unused functions, so the linked binary is identical. It costs a little
> compile-time parsing, paid once, and shifted error lines, which `-Dcrystalgraphics.shader.devmode=true`
> already fixes with `#line`.
>
> Generated shaders change the arithmetic: a preview compiles **per node**, interactively, on every
> edit. Fifty previews each parsing the whole stdlib while a slider is dragged is a cost paid
> repeatedly rather than once.
>
> **Trimming to used functions must not mean parsing GLSL.** Knowing which functions are reachable means
> resolving calls transitively, through macros, ignoring comments and strings — a real parser, which this
> project deliberately does not have (structural extraction by delimiter scanning only). A regex
> approximation would silently strip a function referenced only through a macro, surfacing as a link
> error in whichever keyword variant nobody tested. Per-node declared includes get most of the benefit
> from data we already have. If file granularity is ever not enough, the next step is a **manifest the
> lib itself declares** — reading our own metadata, still not parsing GLSL.

**Open**: whether a node may also declare a `#pragma cg_use` requirement.

---

### 6.3.3 `CgGraphCompiler` — graph to GLSL · `DONE` (2026-07-31)

> **Shipped**, with all six rules from the plan met: namespacing (`node_<id>_<port>`), compiler-emitted
> casts, unconnected inputs as literals, root-parameterised dead-code elimination, and deterministic
> output. **16 GL-free tests.**
>
> **Godot's inversion is the whole design.** A node is handed already-resolved variable names and
> returns a snippet; it never sees the graph, so it cannot namespace wrongly or depend on emission order.
> That is why the compiler is short.
>
> **Dynamic ports resolve TOGETHER**, to the widest type reaching the node — `Multiply(float, vec3)` is
> vec3 throughout. Per-port resolution would make the output a float whenever the first input happened to
> be one, a bug that depends on wiring order and is unreproducible.
>
> **Errors accumulate and name their node**, and every emitted line records its owner
> (`Result.ownerOfLine`) so a driver error can point at a node rather than at a line the user never wrote.
> Designed in from the first commit, as the plan insisted — it is free while emitting and impossible
> afterwards.

Topologically ordered emission. `GraphDocument.topologicalOrder()` already provides the ordering, and
cycle rejection already happened at connect time, so the compiler may assume a DAG.

**What it must get right**

1. **Namespacing.** `{nodeId}_{portId}`. Ids are already legal GLSL identifiers.
2. **Type coercion is the compiler's job, not the user's.** `TypeCompatibility` permits `float → vec3`;
   GLSL requires `vec3(x)`. Every promotion the document allows, the compiler must emit a constructor
   for — and every one it *cannot* express is a validation rule that belongs at connect time instead.
   These two must be defined together or they will disagree.
3. **Unconnected inputs emit their value, not a variable.** This is what the inline editor on
   `nodeport:blank` has been collecting all along; the value lives in `NodeData.properties`.
4. **Dead code.** Nodes not reaching the output node are not emitted — except while compiling a preview,
   where the node *is* the output. One traversal parameterised by root, not two code paths.
5. **Properties bubble up.** A node property marked as a shader property becomes an entry in the
   generated `Properties` block, deduplicated across instances, and must land in the type set the parser
   accepts (see the audit table).
6. **Deterministic output.** The same graph must emit byte-identical source, or content-hash keying in
   6.3.1 is worthless and every reopen recompiles. `GraphDocument` is already content-addressed with
   fixed field order for exactly this reason.

**Tests belong in `headlessTest`** — the emitted string is the assertion, and no GL is involved.

---

### 6.3.4 Domains, and the vertex/fragment split · `DONE` (2026-07-31)

> **Shipped**: `CgShaderDomain` and the stage assignment in `CgShaderEmitter`. A node feeding
> `BaseColor` that declares `VERTEX` is **hoisted** into the vertex stage together with its dependencies,
> and its value crosses as a `v2f` varying — which is what the hand-written format already does, so this
> is a mapping rather than an invention.
>
> **The asymmetry is enforced, not smoothed over.** Vertex data reaches the fragment stage through a
> varying; fragment data cannot reach the vertex stage at all, because the vertex shader has already run.
> A fragment-only node feeding the vertex stage is an error naming both nodes.
>
> **Why declared and not inferred**: `fwidth`, `discard` and `gl_FragCoord` are fragment-only, and this
> engine has already shipped that exact bug once — `sdf.glsl`'s `fwidth` reached the vertex stage, NVIDIA
> accepted it, AMD refused, and the whole UI gallery was unlaunchable on that hardware.
>
> **The bug the tests caught**: the fragment stage was re-emitting the hoisted nodes, so `cg_Position`
> appeared in a fragment body. `compileFrom` grew an `alreadyEmitted` set — those nodes contribute
> **names but no code**, which is what lets two stages share one compiler.

The graph has two domains. A node declares which it can live in; most are `any`. A **Varying** node
moves a value from vertex to fragment and generates a `v2f` field — which maps 1:1 onto the `.shader`
format's own `struct v2f`, so this is a mapping rather than an invention.

**Rules to settle:**

- A fragment-domain node may not feed a vertex-domain one. That is a connect-time validation rule and
  belongs in the document's `TypeCompatibility` sibling, not in the compiler.
- Domain is *inferred* where possible (a node reachable only from the vertex output is vertex-domain)
  and *declared* where it cannot be (`fwidth` is fragment-only — the same rule that already bites in
  `sdf.glsl` and is documented in both AGENTS files).

---

### 6.3.5 The master node · `DONE` (2026-07-31)

> **Shipped**: `CgMasterNode` (ports `Position` and `BaseColor`, plus `#type`, `Queue`, `Tags` and
> `Properties`) and `CgShaderEmitter`, which writes a complete `.shader`. **6 tests**, and the load-bearing
> one asserts the output **parses** through the real `CgShaderParser` — substring assertions would pass
> happily while emitting a file the parser rejects.
>
> The master answers 6.2.5's deferred blackboard question without a second concept: graph-level settings
> live on the node that represents the graph's result, exactly as Unity's Master Stack does.
>
> It emits **nothing** itself — its inputs become `gl_Position` and `fragColor`, written by the emitter,
> because only the emitter knows which stage is being written. A node is handed resolved names and
> nothing else.

What a graph terminates in. Unity's Master Stack has a Vertex block and a Fragment block; ours needs at
minimum a fragment colour and a vertex position, plus the `RenderState`/`Tags`/`Queue` that a `.shader`
carries and a graph currently has nowhere to put.

**This is where graph-level settings live** — the blackboard question 6.2.5 deferred. A master node is a
natural home for them and needs no new document concept.

---

### 6.3.6 The built-in node library · `IN PROGRESS` — 5 of the set (2026-07-31)

> **Shipped**: `CgBuiltinShaderNodes` — Color, Float, Time, Add, Multiply — plus `CgShaderNodeRegistry`.
> Five rather than fifty, deliberately: enough to prove the stack end to end, chosen so the demo is also
> a test. Constants exercise the unconnected-input-becomes-a-literal path, Add/Multiply are **dynamic**
> so widening and compiler-emitted casts are live in any graph using them, and Time is an engine builtin
> from `cg_env.glsl`.
>
> **All five are `CgTemplateShaderNode`** — the declarative path covered every one, which is the
> evidence that 6.3.2's interface/data split was drawn in the right place.
>
> **Still to do**: the volume. The categories below are unchanged, and the observation that most of them
> are one call into an existing stdlib function still holds.

Unity-scale, and entirely resource files once 6.3.2 exists. Categories worth having from the start,
ordered by how often a real shader needs them:

| Category | Examples |
|---|---|
| **Input** | Position, Normal, UV, Vertex Colour, Time, Screen Position, Camera, Texture2D, Sampler, Constants (π, e), Float/Vector/Colour parameters |
| **Math** | Add/Subtract/Multiply/Divide/Power/Sqrt; Abs, Sign, Floor, Ceil, Round, Frac, Modulo; Min/Max/Clamp/Saturate; Lerp, Smoothstep, Remap; Dot, Cross, Normalize, Length, Distance, Reflect, Refract; Sin/Cos/Tan and inverses; matrix construct/split/transpose |
| **UV** | Tiling and Offset, Rotate, Polar, Twirl, Flipbook, Parallax |
| **Procedural** | Simple/Gradient/Voronoi noise, Checkerboard, Shapes (ellipse, rectangle, polygon), Gradients |
| **Artistic** | Saturation, Contrast, Hue, Invert, Replace Colour, Blend modes, Channel Mask, Normal Blend/Strength/Unpack |
| **Channel** | Split, Combine, Swizzle, Flip |
| **Utility** | Preview, Comparison, Branch, And/Or/Not, Subgraph |

**Most of this already exists as GLSL** in `shaders/lib/` — `math.glsl`, `vector.glsl`, `color.glsl`,
`uv.glsl`, `noise.glsl`, `sdf.glsl`. A large fraction of the library is a JSON file whose body is one
call into a function that is already written and already tested. That is the payoff for the stdlib work
having been done first.

---

### 6.3.7 Node previews · `TODO` · **the only genuinely new GL capability**

A live render of the graph up to each node — the thumbnail in every reference screenshot, and the thing
that makes a node graph feel like a node graph.

**What it needs from CrystalGraphics**

- **A small-target FBO pool.** `CgFrameBufferRegistry` caches *screen-sized* targets that auto-resize on
  window resize; previews want many fixed-size ones (128×128) pooled and recycled as nodes scroll in and
  out of view. Different lifetime, different resize behaviour, so it is a new thing rather than a flag.
- **A render-to-target path for one mesh + one material.** Today the only entry point is the MC-shaped
  frame pass. A preview is "draw this quad/sphere with this material into this target", which the
  low-level API can already express (`fbo.bind()`, `material.bind()`, `mesh.drawInstanced(1)`) — but the
  per-instance object buffer has to be populated, and that is `CgRenderPipeline`'s job today.

**What makes it tractable rather than a per-frame catastrophe**

- **Only visible nodes need a preview**, and `CanvasView` already knows which nodes are culled. The cull
  set is the render set.
- **A preview is keyed by the content hash of the subgraph feeding it**, so two nodes computing the same
  thing share one target, and nothing re-renders unless something upstream actually changed. The
  document is already content-addressed; this is why that mattered.
- **A per-frame budget.** N nodes × a pass each is unbounded; re-render a bounded number per frame and
  let the rest lag by a frame or two. A preview that updates in 100 ms is indistinguishable from one that
  updates instantly.

**Blocked on 6.3.1** — a preview is a compile of a subgraph, and there is nowhere to put that source
until generated materials exist.

---

### 6.3.8 Editor integration and error reporting · `IN PROGRESS` (2026-07-31)

> **Shipped**: `ShaderGraphBridge` (`GraphDocument` ↔ `CgShaderGraph`, the node library, GLSL promotion
> and the port types), and the gallery's **shadergraph** page — a live graph beside the `.shader` it
> compiles to, recompiling on every connection change. 7 tests.
>
> **The lesson, and it cost two rounds:** there are **two compatibility checks and they are not the same
> one**. The document's `TypeCompatibility` governs `GraphDocument.connect`; a widget drag goes through
> `GraphView.canConnect`, which asks the `PortType`. Registering the rule in only one gives the worst
> failure available — a connection simply refused, with no wire, no error and nothing to read anywhere.
> A test now asserts the two return the *same answer* rather than each being separately correct.
>
> **Still to do:**
> - **Mapping a driver error back to a node.** `Result.ownerOfLine` exists and is populated; nothing
>   consumes it yet. This is the item that decides whether the editor is usable on a real failure.
> - **Debounced recompile.** Currently on connection change only, which is discrete. A per-keystroke
>   trigger needs real debouncing.
> - **Inline value editors.** `Color`'s `Value` and `Float`'s `Value` have no field to type into, so a
>   graph is connectable but not yet editable.
> - **Dynamic ports have no colour** — `graph.css` has no `dynamic` entry, so those dots are grey. Unity
>   colours a dynamic port by its *resolved* type, which the compiler already computes.

- `NodeType` ↔ `CgShaderNode` mapping, so the create menu offers shader nodes and
  `NodeWidgetFactory` builds their widgets. Both registries already exist; this is a bridge, not a
  design.
- **Compile on change, debounced.** Every keystroke in an inline editor must not recompile.
- **A GLSL error must point at a node.** This is the part that decides whether the editor is usable:
  a compile failure reports a line in generated source, and the compiler must be able to map that line
  back to the node that emitted it. Emit `#line` directives, or keep a line → nodeId side table. Cheap
  while emitting, effectively impossible afterwards. **Design it in from the first commit.**
- "Show generated code" as a real panel — the `TextEditor` from 6.1 already exists and already has GLSL
  syntax highlighting.

---

## Ordering, and what blocks what

```
6.3.1  materials from source ──┬── 6.3.3 compiler ── 6.3.4 domains ── 6.3.5 master node
                               │                            │
                               └── 6.3.7 previews ──────────┘
6.3.2  node type as data ──────── 6.3.6 the library
                                        │
6.3.8  editor integration ──────────────┘
```

**6.3.1 first and alone** — it is small, it is the only hard blocker, and nothing can be demonstrated
without it. **6.3.2 and 6.3.3 together**, because a template language designed without an emitter to
test it against will be wrong. **6.3.6 last of the core work**, since a library written before the
template language settles is a library rewritten.

**6.3.7 is the payoff and should not be rushed forward.** It is the most visible item and the most
expensive, and it needs both the compiler and generated materials to be real rather than mocked.

---

## Open questions

| Question | Notes |
|---|---|
| Subgraphs — a node whose type resolves to another graph document | 6.2.5 left the id scheme deliberately open to this. It is how a node library becomes user-extensible without JSON. Wants a decision before 6.3.2 freezes the node format. |
| Does a generated shader go to disk at all? | Not needed at runtime once 6.3.1 exists. But "export this graph as a `.shader` I can hand-edit" is a genuinely useful one-way door, and costs nothing once the emitter exists. |
| How does a graph declare its `RenderState`, `Queue` and `Tags`? | Proposed: on the master node (6.3.5). The alternative is document-level properties, which is a second concept for the same job. |
| Vertex-domain previews | A preview of a vertex-domain node is not a colour. Unity shows the geometry deformed. Possibly out of scope; possibly just "no preview for vertex nodes". |
| Do we need a `Branch` node given GLSL has no cheap branches? | Unity has one and compiles it to `lerp` where possible. Worth doing the same rather than emitting a real `if`. |

---

## What P6.3 is not

- **Not a lighting model.** The graph produces a colour and a position. Lighting math is a node someone
  writes, or a function in the stdlib — not an engine feature.
- **Not SPIR-V, HLSL, or shader LOD.** Same reasoning the manifesto gave, and still correct.
- **Not a decompiler.** See the settled decisions: hand-written `.shader` files stay first-class and
  stay un-round-trippable, and that is a deliberate trade rather than a missing feature.
