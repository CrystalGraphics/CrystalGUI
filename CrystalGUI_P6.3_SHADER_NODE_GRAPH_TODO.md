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

### 6.3.6 The built-in node library · `IN PROGRESS` — 93 of the set, Math done, Channel/UV/Utility/Procedural started (2026-08-03)

> **Update 2026-08-03, later — batch 4 (20 nodes), the first cross-category pass.** Where the Math
> batches stayed inside one category, this one is a slice across five: Channel's `Combine`/`Flip`
> (`Swizzle` still deferred — its output width is set by a user-typed string, needing a real
> `CgShaderNode`, not a template), three UV nodes with a direct 1:1 `uv.glsl` call (`Rotate`, `Tiling
> and Offset`, `Polar Coordinates`), three more whose formulas were new to `uv.glsl` this batch
> (`Twirl`, `Radial Shear`, `Spherize` — standard node-graph distortions, not independently verified
> against Unity's own page, same honesty note `Rotate About Axis` already carries), Utility's Logic set
> (`And`/`Or`/`Not`/`Nand`/`Comparison`/`Branch`), and six of Procedural's nine (`Voronoi` and the two
> Polygon shapes need a genuinely new stdlib function each — cellular noise and an N-gon SDF — and are
> deferred rather than approximated).
>
> - **Logic is fixed-`FLOAT`/`BOOL`, not `DYNAMIC`.** GLSL's `&&`/`||`/comparison operators are
>   scalar-bool-only — there is no `bvecN` short-circuit form — so generalising `Comparison` to a
>   vector would need `lessThan()`/`any()`/`all()` reducing a `bvec` back to one `bool`, a real
>   mechanism this batch does not add. `Comparison`'s `Condition` dropdown (Equal/NotEqual/Less/
>   LessOrEqual/Greater/GreaterOrEqual) is the same `bodyFor` property mechanism `Space`/`Transform`
>   already use, just six options instead of three.
> - **`Gradient Noise` is `fbm4`, not true gradient/Perlin noise** — this library has no real
>   gradient-vector noise function yet, and `fbm4` (four-octave *value* noise) is the closest existing
>   stand-in rather than a new one built to match. Visually similar, mathematically different;
>   documented on the node itself rather than presented as the genuine article.
> - **`Ellipse` uses a cheap approximation** (`length(p/radius) - 1`), not a true elliptical SDF —
>   `sdf.glsl` has none, and adding one for a single caller was judged not worth it this pass. The
>   antialiasing band is not perfectly uniform around the rim as a result (the expression's gradient
>   isn't unit magnitude off-axis); `Rectangle`/`Rounded Rectangle` use the real `sdf_rounded_box` and
>   have no such caveat.
> - **All three shapes are `FRAGMENT`-domain**, same rule Derivative already established: `sdf_coverage`
>   is `fwidth`-based and does not compile in a vertex shader.
>
> **23 new GL-free tests** in `CgBuiltinMathNodesBatch4Test`, same pattern as the Math batches —
> including one that runs `Comparison` through all six `Condition` options, not just the default.

> **Update 2026-08-03 — the Math volume pass, three batches, `CgShaderNodeRegistry.register` grew
> varargs to carry it.** `CgBuiltinShaderNodes` went from 12 nodes to 73 in one session, closing Math
> from 2/64 (Add, Multiply) to 63/64. Every subcategory is now complete except Wave (1/2 — see below),
> and every node is still `CgTemplateShaderNode`: nothing in this pass needed a Java implementation,
> which is the same evidence 6.3.2's interface/data split was drawn correctly that the original twelve
> already gave.
>
> **`CgShaderNodeRegistry.register(CgShaderNode...)`** — was single-arg; `registerAll` now reads as one
> call per subcategory (`register(ADD, MULTIPLY, SUBTRACT, DIVIDE, POWER, SQUARE_ROOT) // math/basic`)
> instead of one chained `.register(...)` per node. Ids grew a matching subcategory segment
> (`cg:math/basic/add`, not `cg:math/add`) so `ShaderGraphBridge.categoryOf`'s existing nested-path
> derivation puts each node in Unity's own `Math ▸ Basic/Advanced/Range/Round/...` menu structure
> instead of dumping all 63 into one flat "Math" list — a real, user-visible reason for the rename, not
> tidiness. `Add`/`Multiply` moved ids too, for the same reason: leaving them at the old flat id would
> have split Basic across two menu locations. Every real reference to the old ids (the harness's
> shadergraph demo, `ShaderGraphBridgeTest`) was updated with the rename; both are covered by the test
> suite that already existed for them.
>
> **Batch 1 (15) — Basic/Advanced/Range/Round, no property, no domain.** Subtract, Divide, Power,
> Square Root · Absolute, Negate · One Minus, Minimum, Maximum, Clamp, Saturate · Floor, Ceiling, Round,
> Sign. `Clamp`'s `Min`/`Max` and an unconnected input of any of these stay bare float literals even
> once a `DYNAMIC` port group widens to a vector — legal GLSL (`clamp`/`min`/`max` all have a
> vector-plus-scalar overload), the same trick `Add`/`Multiply` already relied on implicitly.
>
> **Batch 2 (30) — completing Advanced, Interpolation, Range, Round, Trigonometry, and starting
> Vector.** Exponential, Length, Log, Modulo, Normalize, Posterize, Reciprocal, Reciprocal Square Root ·
> Inverse Lerp, Lerp, Smoothstep · Fraction, Remap, Random Range · Step, Truncate · all twelve
> Trigonometry nodes · Dot Product, Cross Product.
>
> - **`Remap` is the one node in this whole pass that needed a real fix, not just a template.**
>   `math.glsl`'s `remap` has no scalar-broadcast overload the way GLSL's own `clamp`/`mix` do — every
>   one of its five parameters must be the *same* width. An unconnected bound left as a bare float while
>   `In` widens to a vector, the pattern every Range/Round node above relies on, references an overload
>   that does not exist and fails to compile. The fix is `{type:In}(...)` around each bound — a
>   `{type:}` cast token 6.3.2 already had, just never previously needed for anything but width
>   resolution bookkeeping.
> - **`Random Range` is fixed-typed throughout** (`Seed: vec2`, `Min`/`Max`/`Out: float`), not
>   `DYNAMIC` — a hash needs a concrete `vec2` to hash and its result is always one float, so unlike
>   every other node in this batch there is no width to resolve from context at all. Uses
>   `noise.glsl`'s `hash12`.
> - **`Length`/`Dot Product` are the first `DYNAMIC`-in/fixed-`FLOAT`-out nodes** — proof that a node's
>   dynamic ports do not all have to resolve to the same *role* (in vs. out), only the same *value*
>   within whichever ports actually declare `DYNAMIC`. `resolveTypes` already supported this; nothing
>   needed changing to use it.
>
> **Batch 3 (16) — Derivative, Matrix (simplified), the rest of Vector, Sawtooth Wave.** DDX, DDY, DDXY
> · Matrix Split, Matrix Construction, Matrix Transpose, Matrix Determinant · Distance, Reflection,
> Fresnel Effect, Projection, Rejection, Rotate About Axis, Sphere Mask, Transform · Sawtooth Wave.
>
> - **Derivative is this library's first `FRAGMENT`-domain node.** `dFdx`/`dFdy` are refused outright in
>   a vertex shader — the exact `sdf.glsl`/`fwidth` failure 6.3.4's own doc already warns about, now
>   guarded against by construction rather than by remembering not to wire one in wrong.
>   `CgGraphCompiler.compile()` does not itself check domain (that is `CgShaderEmitter`'s job at real
>   two-stage split time), so this is pinned by asserting `.domain() == FRAGMENT` directly rather than by
>   provoking a compile failure.
> - **Matrix is this library's first consumer of a matrix TYPE at all** — `CgShaderType.MAT2/3/4`
>   existed with nothing wired through them since 6.3.2. Simplified to `mat4` only, dropping Unity's
>   Dimension property (2x2/3x3/4x4) — the same "drop the dropdown, ship one concrete form" call
>   `Exponential`/`Log` already made for their own Base property. One real, load-bearing consequence
>   worth knowing: `ShaderGraphBridge.widgetKindFor` has no case for a matrix type, so an unconnected
>   matrix input gets no inline editor — the same gap Texture/Sampler/Gradient already have, and every
>   Matrix node's input must be wired rather than typed in as a result. `Matrix Split`'s outputs are
>   named `Col0`..`Col3`, not Unity's `Row0`..`Row3` — GLSL's own `m[i]` indexing is column-major, and a
>   `Row`-labelled port would be quietly wrong rather than merely differently-shaped.
> - **`Transform` is simplified to Object→{Object,World,View} position only** — Unity's real node
>   crosses `From` × `To` × `Type` (Position/Direction/Normal), a combinatorial control surface this
>   pass does not reproduce. Reuses `POSITION`'s own `SPACE` property rather than inventing a second
>   one, with `From` fixed at Object and `Type` fixed at Position.
> - **`Noise Sine Wave` is deliberately not implemented.** It was not one of the fifteen nodes verified
>   in detail against Unity's own docs, and this library's existing convention — see `Time`'s own doc,
>   "Delta Time/Smooth Delta are deliberately absent rather than wired to something that looks
>   plausible" — is to leave a node out rather than ship a guessed formula. `Sawtooth Wave`'s formula is
>   unambiguous (`(fract(x) - 0.5) * 2`) and shipped; Wave is 1/2 until Noise Sine Wave's real formula is
>   confirmed.
>
> **61 new GL-free tests** across three new files (`CgBuiltinMathNodesTest`,
> `CgBuiltinMathNodesBatch2Test`, `CgBuiltinMathNodesBatch3Test`), each following
> `CgGraphCompilerTest`'s own pattern — the emitted GLSL string is the assertion, compiled unconnected
> (so the port defaults are checked verbatim) and again fed a `vec3` (so `DYNAMIC` resolution is
> exercised, not just the float identity every default would otherwise hide) — plus one
> registration-reachability test per batch, since a node nobody calls `.register()` on never reaches the
> editor's create menu regardless of how correct its GLSL is.

> **Update 2026-08-02: `Split` (`cg:channel/split`) — the multi-output blocker is resolved, not just
> worked around.** `docs/research/UNITY_SHADER_GRAPH_NODES.md`'s "What this implies for 6.3.6" had
> flagged multiple outputs as "the change that is hardest to retrofit" — checking the actual code
> (`CgGraphCompiler`, `CgTemplateShaderNode`, `CgPreviewEmitter`, `EdgeData`, `GraphNode`'s port lists)
> found every layer already generic over output count; nothing was ever hardcoded to one. `Split` is the
> first node to actually use more than one output, proven end to end by `CgGraphCompilerTest` (four
> outputs, four independent consumers, per-output type resolution) and
> `ShaderGraphBridgeTest.splitsFourOutputsBecomeFourPortsAndWireIndependently` (the editor's `NodeType`,
> `GraphDocument.link` fan-out from one node on two different output ports, the compiled result
> parsing). Zero production code changed outside the node itself. `Combine`/`Matrix Split` are now the
> same shape of work as any other template node — see the Channel row below.

> **Update 2026-07-31, later**: three more — **UV, Position, Normal Vector**. These are the nodes a
> preview system exists to show, and adding them forced two mechanisms into being rather than merely
> using the existing one:
>
> - **`previewBody(...)`** — Godot's `p_for_preview` made real. All three read vertex attributes, so they
>   are `VERTEX` domain and cannot run in the fragment stage where a preview evaluates. They declare a
>   second body reading the preview's varying instead, and `hasPreviewForm()` lifts the preview emitter's
>   refusal for exactly those nodes.
> - **Node properties** — the `Space` dropdown (Object/World/View). See 6.3.8.
>
> **A real bug this turned up**: `Normal Vector` emitted `CG_NORMAL_MATRIX * cg_Normal` unconditionally —
> a *world* normal, with nothing saying so. Object is now the default, matching Unity, with World and
> View as variants.
>
> **Tangent space is deliberately absent.** Unlike the other three it needs a per-vertex tangent basis,
> which this engine's vertex formats do not carry, so it cannot be derived from what a node is handed. An
> option that silently emitted the wrong frame would be worse than one that is missing.

> **Shipped**: `CgBuiltinShaderNodes` — 93 nodes plus `CgShaderNodeRegistry`. The original twelve
> (Color, Float, Vector2/3/4, Time, Add, Multiply, UV, Position, Normal Vector, Split) proved the stack
> end to end; the Math volume pass closed out Basic/Advanced/Interpolation/Range/Round/Trigonometry/
> Derivative/Vector fully and Matrix (simplified to `mat4`) and Wave (Sawtooth only — Noise Sine Wave
> deferred) partially; batch 4 opened Channel (2/4 — Swizzle deferred), UV (6/10), Utility's Logic set
> (6/12), and Procedural (6/9 — Voronoi and the two Polygon shapes deferred). Constants exercise the
> unconnected-input-becomes-a-literal path, the arithmetic/comparison nodes are **dynamic** so widening
> and compiler-emitted casts are live in any graph using them, Time is an engine builtin from
> `cg_env.glsl`, UV/Position/Normal exercise `previewBody`/`hasPreviewForm` and the `Space` property
> (now also reused by `Transform`), Split/Matrix Split exercise fixed (non-`DYNAMIC`) multi-output
> nodes, and Derivative/the Procedural shapes are `FRAGMENT`-domain.
>
> **Every one is `CgTemplateShaderNode`** — the declarative path has covered every node so far, across
> 93 of them now, which is the evidence that 6.3.2's interface/data split was drawn in the right place.
>
> **Still to do**: Input (54, 8 shipped), Artistic (16, untouched), the rest of UV (4: Flipbook,
> Triplanar, Parallax Mapping, Parallax Occlusion Mapping — the genuinely hard ones, needing texture
> sampling/tangent space), the rest of Procedural (3: Voronoi, Polygon, Rounded Polygon), the rest of
> Utility (6: Preview, Subgraph, Is Infinite, Is NaN, Any, All), Channel's Swizzle, Wave's Noise Sine
> Wave, and mapping a driver error back to a node
> (6.3.8's own open item). The observation that most of the rest is one call into an existing stdlib
> function still holds — Math was the proof of that at scale.

Unity-scale, and entirely resource files once 6.3.2 exists. Categories worth having from the start,
ordered by how often a real shader needs them:

| Category | Examples |
|---|---|
| **Input** | Position, Normal, UV, Vertex Colour, Time, Screen Position, Camera, Texture2D, Sampler, Constants (π, e), Float/Vector/Colour parameters |
| **Math** | ~~Basic, Advanced, Interpolation, Range, Round, Trigonometry, Derivative, Vector~~ (done — 63/64); Matrix (done, simplified to `mat4`); Wave (Sawtooth done, Noise Sine Wave deferred) |
| **UV** | ~~Tiling and Offset, Rotate, Polar Coordinates, Twirl, Radial Shear, Spherize~~ (done — 6/10); Flipbook, Triplanar, Parallax Mapping, Parallax Occlusion Mapping remain (texture sampling/tangent space needed) |
| **Procedural** | ~~Checkerboard, Simple Noise, Gradient Noise (approximated via `fbm4`), Ellipse, Rectangle, Rounded Rectangle~~ (done — 6/9); Voronoi, Polygon, Rounded Polygon remain (each needs a new stdlib function) |
| **Artistic** | Saturation, Contrast, Hue, Invert, Replace Colour, Blend modes, Channel Mask, Normal Blend/Strength/Unpack |
| **Channel** | ~~Split, Combine, Flip~~ (done — 3/4); Swizzle remains (output width set by a user-typed string, needs a real `CgShaderNode`) |
| **Utility** | ~~And, Or, Not, Nand, Comparison, Branch~~ (Logic done — 6/12); Preview, Subgraph, Is Infinite, Is NaN, Any, All remain |

**Most of this already exists as GLSL** in `shaders/lib/` — `math.glsl`, `vector.glsl`, `color.glsl`,
`uv.glsl`, `noise.glsl`, `sdf.glsl`. A large fraction of the library is a JSON file whose body is one
call into a function that is already written and already tested. That is the payoff for the stdlib work
having been done first.

---

### 6.3.7 Node previews · `DONE` (2026-07-31) · **the genuinely new GL capability**

> **Shipped, and live in the gallery's shadergraph page.**
>
> | Piece | What it is |
> |---|---|
> | `CgPreviewEmitter` | A subgraph → a complete, parseable `.shader`. Not a second compiler: `compileFrom(graph, rootId, forPreview)` from 6.3.4, rooted elsewhere |
> | `CgPreviewGeometry` | Quad vs sphere, **propagated downstream** — Unity's rule |
> | `CgPreviewSlots` | Keep/reuse/evict policy, split out so it is testable with no GL |
> | `CgPreviewTargetPool` / `CgPreviewTarget` | Bounded pool of MSAA target + resolve target pairs |
> | `CgPreviewRenderer` | One mesh, one material, one draw, on a per-frame budget |
> | `ShaderNodePreview` / `ShaderGraphPreviews` | The editor side: paints the texture, drives the visible set |
>
> **`CgRenderPipeline.prepareFrame()` was the whole seam.** It already existed for "manual-bind scenes
> that call `CgMaterial.bind()` directly" — no second pipeline was needed. The shared `CgFrameData` is
> borrowed and restored in a `finally`, or the world pass would later render through a 128×128 preview
> camera with no exception and no obvious cause. `timeSecs` is deliberately *not* overridden, which is
> what makes a Time node's thumbnail animate for free.
>
> **MSAA was added to CrystalGraphics for this** — committed separately as `999f218`: sample count on
> `CgFrameBufferFormat`, `glRenderbufferStorageMultisample` / `glTexImage2DMultisample` through the SPI to
> all four backends, multisampled attachments, and EXT degrading to single-sampled rather than failing.
> That commit also fixed a **pre-existing bug**: nothing ever *bound* a renderbuffer before allocating its
> storage, so allocation landed on whatever was bound already. Single-sampled depth survived it by luck.
>
> **Four bugs found by looking at it, every one silent:**
>
> 1. **Nothing ever rendered.** `invalidateAll()` marks what has already been drawn, which on a cold start
>    is nothing — so the dirty set stayed empty forever. `setVisible` now queues never-rendered nodes.
> 2. **UV drew fully transparent.** `UV` is `vec4(uv, 0, 0)`, so its alpha is 0. Alpha is now supplied
>    structurally as the geometry's coverage and the value contributes colour only.
> 3. **Everything was too dark, and the sphere's quadrants looked uneven.** Linear values were being
>    written into an RGBA8 the compositor treats as sRGB. `linear_to_srgb` on output fixed both — the
>    geometry was centred the whole time; the sRGB curve compresses exactly the values either side of the
>    x=0 boundary hardest.
> 4. **A failing preview retried forever.** `renderedSource` was only recorded after a successful draw, so
>    any node that could not be drawn — the Output node permanently, by design — came back dirty every
>    frame. There is now a `failed` set, cleared by `invalidate`.
>
> **Also**: previews declare `CastShadows Off`. Without it the compiler auto-generates a ShadowCaster pass
> referencing `cg_ShadowViewProjMatrix`, which nothing in a preview context declares.
>
> **Known limitation, recorded rather than hidden**: the preview camera is the identity, so `View` space
> is a no-op there. The thumbnail expresses the object-vs-view Z convention directly instead; the *real*
> shader still emits the true transform, and both are pinned by tests. If previews ever get a real camera,
> delete that substitution.
>
> **Blocked externally**: the shadergraph page pins its `TextEditor` to `height: 300px`, because a
> `TextEditor` with a parent-derived height hangs — `viewportHeight`/`horizontalBarThickness` and
> `textViewportWidth`/`verticalBarThickness` are mutually recursive and do not converge. That is a 6.1
> defect, handed over with a stack dump. Revert to `flex-grow: 1` once it converges.

<details><summary>The original plan, for the record</summary>

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

</details>

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
> **Update 2026-07-31, later — node properties and their dropdowns shipped.**
>
> `CgShaderNodeProperty` is an enumerated **compile-time choice** that selects which GLSL a node emits,
> and it is deliberately not an input port with a default: `Position` in object space and in world space
> are different expressions, not one expression with a different input. Modelling it as a port would imply
> it could be driven by another node — i.e. branching in the shader for something known before compiling.
> Unity and Godot keep the two apart for the same reason.
>
> - `CgTemplateShaderNode.bodyFor(property, option, glsl)` / `previewBodyFor(...)` — declarative variants
> - `ShaderNodeControls` — draws a `Dropdown` per property and writes the choice **to the document**, not
>   to the widget: selecting an option changes the generated shader and must survive a reload
> - `ShaderGraphPreviews.onPropertyChanged` — a property changes the emitted GLSL but *not* the graph's
>   shape, so `onConnectionsChanged` never fires for it and the source pane would silently show the
>   previous variant
> - A stored option this build no longer has resolves to the default, rather than leaving the dropdown
>   blank while the compiler quietly used something else
>
> `CgMasterNode.properties()` became `shaderProperties()` in the same change — those are the material's
> `Properties {}` uniform block, a genuinely different thing from a node's dropdowns, and the two collided.
>
> **Update 2026-08-02 — inline value editors shipped, as part of P6.1.8's config-kit work.**
> `NodeFieldWidgets` became a codec layer over `ConfigControls` (step 7) and `NodePort`'s existing
> `setInlineEditor` slot was proven end to end against real controls for the first time (step 8) —
> `Color`'s `Value` and `Float`'s `Value` (and any port carrying a literal default) now get a real
> `NumberControl`/`ColorControl`/etc. inline on the port row, not a bare stand-in. See
> `CrystalGUI_P6.1.8_CONFIGURATOR_PLAN.md`'s step 7/8 write-ups and `NodePortInlineEditorTest`.
>
> **Update 2026-08-02, later — moved OFF the port row entirely, to match Unity's actual layout.**
> The step-8 shape above put the control inside the port's own row, inside the node's box. Unity's
> reference screenshots show it floating OUTSIDE the node, beside the port, joined by a short stub —
> and `Add`/`Multiply`'s `A`/`B` had no editor at all (`widgetKindFor` had no case for `DYNAMIC`, now
> fixed). `NodePort.setInlineEditor` is `setDefaultEditor` now — a stored reference only, mounted
> nowhere by the port itself. `GraphView` discovers, places and tears down the floating widget
> (`discoverPortEditors`/`setPortEditorMounted`/`positionPortEditor`), tracking its own live
> `NodePort.dotCenter()` every tick; `NodeWireLayer` draws the connecting stub in the port's own type
> colour. Two real bugs surfaced building this, both now pinned by tests rather than left for the next
> screenshot to catch: every `ConfigControl` self-marks internal in its own constructor, so mounting via
> public `addChild` "worked" (the public API doesn't check the child's own flag) while unmounting via
> public `removeChild` silently no-opped forever (`content().addInternalChild`/`removeInternalChild`
> fixed it); and `NodePort.dotCenter()` is a raw accumulated layout position, not a world coordinate —
> feeding it straight into `moveNode` double-counts the plane's own on-screen origin, invisible at world
> (0, 0) and off by a whole panel width anywhere else (`positionPortEditor` now subtracts
> `content().getRuntimeCache()`, the same conversion `CanvasView.worldBoundsOf` already does).
>
> **Update 2026-08-02, later still — Unity's axis prefix and trailing dot.** A closer reference
> screenshot showed the floating field is not bare: it reads `X 0 •` — the port's own id as a plain
> unstyled prefix, then the value box, then a small filled dot the stub actually runs into. `GraphView`
> now builds that whole row (`discoverPortEditors`) around whatever `NodePort.getDefaultEditor()`
> returns, rather than mounting the bare control — the getter's own contract is unchanged (still the raw
> `NumberControl`/`ColorControl`/etc., per its javadoc), so nothing downstream that reads it needed to
> change. `graph.css`'s `.__editor__` compound selectors (`.__editor__.__color__`) had to become real
> descendant selectors (`.__editor__ .__color__`) now that the kind class sits on a nested control rather
> than the row itself — the exact "resolves to zero, not to an error" trap this file's own CSS comments
> already warned about, just relocated one level down. The dot's colour is read from
> `NodePort.typeColor()` every tick alongside the reposition, not hard-coded per type in CSS — one number,
> three consumers (the port's own dot, the wire, this dot) rather than a fourth copy of the palette.
>
> **Update 2026-08-05 — the master type-checks its ports.** `CgShaderEmitter` gave up silently on any
> conversion it could not write, so a texture wired into `Base Color` emitted
> `vec4(node_t_Out, cg_alpha)`: a `.shader` that **parses**, fails in the driver, and reaches the user as
> a white material with the real complaint in a GL log nothing shows. The hole was structural —
> `CgGraphCompiler` validates a link while resolving the *consuming* node's inputs, and the master emits
> no code, so it has no inputs to resolve and never reached that path. The type resolution now lives in
> one place (`feedInto`/`MasterFeed`) shared by the emitter and `checkMasterPorts`, rather than being
> asked twice by two things that could drift.
>
> **Still to do:**
> - **Mapping a driver error back to a node.** `Result.ownerOfLine` exists and is populated; nothing
>   consumes it yet. This is the item that decides whether the editor is usable on a real failure.
>   **Blocked on a backend capability, found 2026-08-05:** `CgMaterialShader` writes its compile log to
>   `LOGGER` and retains nothing, so there is no log for CrystalGUI to parse. Retaining the last log and
>   exposing it is a small additive change in CrystalGraphics, and it is where this has to start.
> - **Compiler errors should carry a node id as DATA.** They are formatted `Node '<id>' input '<port>'
>   wants …` today, so the id exists but only inside the sentence. Regexing it back out is the "second
>   statement of the same fact" trap this file keeps recording; the fix is a small carrier
>   (`nodeId?` + `message`) on `Result.errors()`. It is a wide but shallow change — every consumer and
>   test currently treats an error as a `String` — and it is what lets BOTH halves (compiler errors and,
>   later, mapped driver errors) light up the same node marking.
> - **Debounced recompile.** Currently on connection change and property change, both discrete. A
>   per-keystroke trigger needs real debouncing.
> - **Dynamic ports resolve to a static grey, not their resolved colour** — `graph.css`'s
>   `nodeport.type-dynamic` entry exists (it is not simply missing, as this bullet used to say), but it
>   is one fixed grey rather than Unity's behaviour of colouring a dynamic port by whatever concrete type
>   it resolved to, which the compiler already computes and records in `Result.typeOf`.

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

### 6.3.9 Search — ranked, highlighted, and **reusable** · `DONE` (2026-08-03)

> **Layer 1 shipped 2026-08-03 — `com.crystalgui.core.search`, 16 tests, headless.**
> `SearchQuery` (normalised once, because a query is matched against every candidate),
> `SearchMatch` (score + kind + **ranges**) and `SearchMatcher`. Field weights are 1000 apart so no kind
> bonus can cross them, which is what makes *any* name hit outrank *any* category hit — the Enter bug,
> pinned by `theWeakestNameMatchStillBeatsAnExactCategoryMatch`. Deliberately **not** a general fuzzy
> matcher: `ACRONYM` covers word starts (`cp` → Cross Product) and an arbitrary subsequence is refused,
> which `anArbitrarySubsequenceIsNotAMatch` holds to.
>
> **②③④ shipped 2026-08-03.** `NodeTypeRegistry` ranks (score desc, then alphabetical — without that
> tiebreak a `LinkedHashMap` orders equal scores by registration, which the user cannot see or predict);
> `NodeMenuTree.ranked` replaced `flat`, which had been **re-sorting alphabetically and throwing the
> ranking away**; rows carry their category; `EntryRenderer` registers match ranges under
> `::highlight(search-match)` on every bind; `SearchField` is a general widget (icon / field / clear),
> and the menu now uses it.
>
> **Two things the screenshots caught that the plan did not.** The category separator was `▸` — which
> `MinecraftRegular.otf` does not have, so it drew a **blank advance** and read as a spacing bug, the
> same trap `UIText`'s ellipsis fallback already documents. It is now a drawn `CgUiShape`
> `triangle-right`, and the category is a list of **segments** rather than one joined string — which also
> makes the tint exact for free, since each segment is matched on its own and there is no offset
> arithmetic to get wrong. The magnifier is deliberately **not** drawn: the shape catalog has no
> magnifier, and both cheap fakes (a font glyph, a wrong shape) are worse than the element sitting
> `display: none` as a hook until one exists.
>
> **Follow-up fixes 2026-08-03**, from using it: the menu is a `Popover` and therefore the SAME element
> every open, so its scroll offset survived hiding and reopening showed the list already scrolled — reset
> with `setScrollImmediate` before `rebuild()`. Category casing now comes from the id **verbatim**
> (`categoryOf` and `categorySegments` both stopped upper-casing the first letter), because deriving it
> is right for `math` and silently wrong for an acronym: `uv` rendered as the category "Uv". Port labels
> are humanised at the draw site (`RadialScale` → `Radial Scale`, `UV` stays `UV`) while the id is left
> alone — it is simultaneously a GLSL template key, the emitted variable name and `PortSpec.portId`, so it
> must stay a single identifier. Derived rather than declared, deliberately: an explicit label would mean
> 93 nodes each carrying a second string, and it can still be added per-port later if one needs it.
>
> ⚠ **Node ids were re-cased** (`cg:uv/…` → `cg:UV/…`, `cg:math/basic/…` → `cg:Math/Basic/…`; 115
> replacements). An id is the **persisted `typeId`**, so a graph saved before this reads as unknown types
> and renders through `NodeWidgetFactory.placeholder`. Harmless while nothing durable has been saved,
> which is true today — but if that stops being true, this is the change that needs a migration, and it
> is cheap to write as an id alias map. Only CATEGORY segments were re-cased; leaves are untouched
> (`cg:UV/polar-coordinates`), since a leaf is never displayed.
>
> Tests: 16 headless (`SearchMatcherTest`) + 4 in `NodeCreationMenuTest` — name-beats-category ranking,
> ranking-does-not-filter, category shown only while searching, and highlight ranges registered **and
> cleared** (the row-recycling guard).

> Motivation: the create menu side by side with Unity's, 2026-08-03. Four differences, each verified
> against our code rather than inferred from the screenshot.

#### The audit

| # | Defect | Where it lives |
|---|---|---|
| 1 | **The match reason is invisible — and it is most of the list.** `NodeType.matches()` ORs over label, **category** and synonyms. For `vec`, ten of thirteen visible rows (Cross Product, Distance, Dot Product, Fresnel Effect, Projection, Reflection, Rejection, Rotate About Axis, Sphere Mask, Transform) matched *only* on their `Math/Vector` category — and `NodeMenuTree.flat()` then builds every leaf with `""` as its category, throwing the reason away. Unity lists the same nodes under a highlighted `Math ▸ Vector`, so they read as obvious rather than arbitrary. | `NodeType.matches`, `NodeMenuTree.flat` |
| 2 | **No ranking, and it makes Enter create the wrong node.** `flat()` sorts alphabetically by label, full stop. But `rebuild()` pre-selects row 0 so Enter takes "the best match" — with `vec` that is **Cross Product**, which does not contain the string at all, while `Vector 2` sits 12th. The class doc already claims a result set is "ranked rather than filed"; the code only filters. | `NodeMenuTree.flat`, `NodeCreationMenu.rebuild` |
| 3 | **No match highlighting.** Unity tints the matched substring in both leaves and category names. Ours is plain text — which is what makes #1 unreadable. | the row builder |
| 4 | **Flat list vs. preserved tree.** A genuine design disagreement, not an oversight: our own note argues that burying matches under collapsed folders is what the user typed to avoid. But Unity **auto-expands** the matched branches, so nothing is buried — the rationale does not survive the comparison intact. | see the open decision below |

> **Not a defect, and it validates a design we already have:** Unity shows `Float` under `Input ▸ Basic`
> for `vec` because Shader Graph's Float was formerly *"Vector 1"* and still carries it as a synonym —
> exactly the mechanism `NodeType.synonyms` exists for ("typing `plus` finds `Add`"). We need the data,
> not a feature. (`URP Sample Buffer` also appears there and I cannot account for it; not worth guessing.)

#### This is NOT a create-menu feature — three layers, and only the last is the menu's

The create menu is the first consumer, not the owner. Splitting it now rather than after a second
consumer forces the issue is the whole point.

**1. `com.crystalgui.core.search` — the matcher. Headless: no widgets, no GL.**
Sits beside `core.command` / `core.undo` / `core.property`, which is the family it belongs to — small,
general, testable without a window. Shape:

- `SearchQuery` — a parsed, normalised query
- `SearchMatch` — `score`, **which field matched**, and the matched **character ranges**
- `Searchable` — the SPI a candidate implements, or a `Function<T, List<Field>>` adapter so a caller does
  not have to modify its own types

The ranges are the load-bearing part and the reason this cannot return a boolean: **both** the
highlighting (#3) and the "why did this match" annotation (#1) are downstream of knowing *where* the hit
landed. A yes/no matcher forces every consumer to re-derive it, badly and differently.

**2. Highlighting reuses the CSS Custom Highlight API already shipped.**
`ui/text/HighlightRegistry` + `TextRange` + `::highlight(name)` exist and are precisely this: styling text
ranges **without wrapping them in elements**. So match tinting is `::highlight(search-match) { color: … }`
in a stylesheet — **no new render path, and the colour is a theme's business rather than a constant in
Java**, the same rule the port palette already follows.

Two constraints from that API, both already documented and both fine here: it accepts only non-layout
properties (colour is; `font-size` would reflow the very text being searched), and highlighted text takes
the shaped-span path, so a row with no match is unaffected.

**3. `SearchField` — a real widget in `ui/elements/`. NOT a `ConfigControl`.**
Worth stating plainly, because it is the one place this could go wrong: **a `ConfigControl` edits a value;
a search field filters a view.** Different jobs — and the kit's row height, label column and
`__config-control__` cascade are all built for the first. A `SearchField` *used inside* a configurator
panel is completely fine: that is composition, not membership. Internal children `__icon__` / `__field__` /
`__clear__`, so a theme can draw Unity's magnifier and a clear button.

**Composition** (query → ranked model → selection) stays in `NodeCreationMenu` for now. Its arrow-key
routing and its pre-selection asymmetry are already correct and hard-won — see its own note on why Enter
pre-selects only *with* a query — and extracting behaviour that has exactly one consumer is how a
premature abstraction gets locked in. Extract it when a second consumer disagrees, not before.

#### Ranking — ported, not invented

Scoring buckets are a convention, not a derivable answer, so they come from VS Code's
`vs/base/common/filters.ts` / `fuzzyScorer.ts` (**MIT — port the code, attribute in the class javadoc**),
the same rule `text/cursor/` already follows. Tiers, strongest first:

1. exact label match
2. label **prefix** — `vec` → `Vector 2`
3. label word-boundary / acronym — `cp` → `Cross Product`
4. label substring
5. **synonym** match
6. **category** match — deliberately last, and the direct fix for #2

Ties break alphabetically, so ordering is stable rather than dependent on registry iteration order.

#### Consumers this unlocks

- **Create Node menu** (now), and the contextual wire-drop menu that shares its code
- **Command palette** — `core.command.CommandRegistry` exists with no palette over it, and
  `NodeCreationMenu` already cites "the command-palette rule" for its Enter behaviour
- **Configurator panel** — searching settings is table stakes (VS Code, Unity and Blender all have it)
- `TableView` / `ListView` filtering, and the `__asset__` control's browse

#### Tests

- `core.search` is **headless**, so it belongs in `headlessTest` (no `CgIO`, no fonts, no `StyleSheet`) —
  which is also the standing proof that it carries no widget dependency
- Ranking: `vec` puts `Vector 2` above `Distance`; `cp` finds `Cross Product`; a category-only match ranks
  below every name match
- **Regression for #2**: with a query, the pre-selected row is a *name* match — the "Enter creates Cross
  Product" bug, pinned
- Ranges: a match reports the exact offsets a highlight would tint
- Highlighting asserted through `HighlightRegistry`, never pixels

#### Layout — SETTLED 2026-08-03: flat, ranked, with a category suffix

Chosen over Unity's auto-expanded tree. The argument for flattening was right the first time; its only
real defect was hiding the category that *caused* the match, and that is a dim `Math ▸ Vector` suffix per
row rather than a layout change. Unity only avoids burying results by auto-expanding every matched
branch — at which point the folders are decorative, and it is paying extra rows and indentation for a
grouping nobody navigates while searching.

So: **a query yields a flat, ranked list; each row shows its category dimmed after the label; both the
label and the category tint their matched ranges.** Browsing with an empty query still shows the real
tree, unchanged — that is what the hierarchy is *for*, and 6.3.9 does not touch it.

#### The one constraint that will bite if forgotten — rows are RECYCLED

`TreeView` realises about a dozen rows and reuses them: its own note says a realised row "represents a
*different* row every time it is recycled, so a listener cannot capture an index and must ask at click
time." **Highlight ranges are per-row data and must therefore be applied in the renderer, on every bind
— never registered once against a row element.** Getting this wrong produces the exact failure the
editor's pooled gutter arrows already documented: correct until you scroll, then every row wears the
ranges of whichever result last occupied its slot. The `EntryRenderer` is the only place that may call
`highlights().set(...)`, and it must `set` on every bind (including clearing to empty) rather than only
when there is a match.

---

### 6.3.11 The Vertex and Fragment blocks · `MOSTLY DONE` (2026-08-04) — ports shipped, section headers not

> **Shipped**: `CgMasterNode` restructured into `VERTEX_PORTS` / `FRAGMENT_PORTS` declared as data, with
> `BaseColor` narrowed to `vec3` and real `Alpha` / `AlphaClipThreshold` ports. `CgShaderEmitter`
> generalised to **multiple roots per stage**. 9 new tests in `CgMasterBlocksTest`, all asserting the
> emitted file parses through the real `CgShaderParser`.
>
> **Not shipped**: the two-box *visual*. The master still draws as one flat node with four ports rather
> than `Vertex` / `Fragment` sections — that needs a port-group concept in `GraphNode`, which is a widget
> change rather than a compiler one.
>
> #### Three things contact with the code changed
>
> **1. Vertex `Normal` was dropped, and this reverses the plan below.** The plan argued it was in scope
> because it feeds the normal varying a fragment-stage `Normal Vector` node reads. That is true of
> `CgPreviewEmitter` and **false of the real one**: `cg:Input/Geometry/normal` is a `VERTEX`-domain node
> whose body is `{Out} = cg_Normal;`, so it hoists itself into the vertex stage and crosses as its own
> varying — it never consults the master. A master `Normal` port would therefore be *exactly* the dead
> port the whole scope argument is against, unless the emitter special-cased that node id. Making it real
> means unifying the two emitters on one normal varying, which is worth doing deliberately rather than as
> a rider on this item.
>
> **2. A latent off-by-one in `CgGraphCompiler`'s line map surfaced.** `chunk.split("\n", -1)` on a chunk
> ending in a newline yields a trailing empty element, and mapping it claimed one line *too many* — the
> line after the node's code. It stayed invisible for as long as that next line always mentioned the same
> node's variable (`fragColor = node_c_Out;` did), and broke the moment the emitter started writing a
> `float cg_alpha = …` of its own in between. Fixed, and `CgShaderEmitter.merge` now **shifts** each
> part's line owners instead of keeping only the first part's — latent while only hoisted nodes made a
> second part, routine now that a stage has several roots.
>
> **3. The master narrows where an ordinary port refuses to.** A `vec4` into `BaseColor(vec3)` had to
> become `.xyz`. The compiler's `mayNarrow` never sees it — the master emits no code, so it has no inputs
> for the compiler to resolve — so the swizzle is applied in the emitter, as the same
> "the compiler may narrow what it wired itself" carve-out the implicit-UV links already have.

### 6.3.11 The Vertex and Fragment blocks · original plan (2026-08-03)

Unity's Master Stack, drawn as two blocks. Ours is `CgMasterNode`, which today has exactly two ports —
`Position` (vertex) and `BaseColor` (fragment, `vec4`).

#### What Unity ships, for reference

| Block | Ports |
|---|---|
| Vertex | Position(3), Normal(3), Tangent(3) |
| Fragment (URP Lit) | Base Color(3), Normal Tangent Space(3), Metallic(1), Smoothness(1), Emission(3), Ambient Occlusion(1), Alpha(1), Alpha Clip Threshold(1) |

#### One fact decides most of the scope question

**There is no lighting model.** Not "a simple one" — none:

- `cg_env.glsl`'s `CgFrameBlock` carries `cg_ViewMatrix`, `cg_ProjMatrix`, `cg_Time`, `cg_Resolution`.
  No light direction, no light colour, no ambient term, not even a camera position.
- `CgFrameData.hasDirectionalLight()` is `return false`, commented *"MVP: directional light deferred to
  v2. Shadow pass is not executed in Phase 1."*

So the question "will we need Smoothness?" has a definite answer rather than a taste answer. **A port
whose only consumer is a lighting model that does not exist is a port that silently does nothing** — it
accepts a wire, shows a value, changes no pixel, and gives no indication which of those three it is
failing at. That is worse than its absence, because absence is at least legible.

#### Scope

| Unity port | Verdict | Reasoning |
|---|---|---|
| **Position** (vertex) | **IN** — already exists | |
| **Normal** (vertex) | **IN** — new | *Not* for lighting. It feeds the `normal` varying `CgPreviewEmitter` already computes (`o.normal = CG_NORMAL_MATRIX * cg_Normal`), which is what a fragment-stage `Normal Vector` node reads. The consumer is the graph itself, and it is real today. |
| **Tangent** (vertex) | **OUT** | Exists solely to build the tangent basis for tangent-space normal mapping. No normal mapping without lighting. |
| **Base Color** (fragment) | **IN** — exists, but **`vec4` → `vec3`** | Unity parity, and it has to change anyway to pair with a real Alpha port. See the migration note below. |
| **Alpha** | **IN** — new | The one PBR-adjacent port that is genuinely consumable *today*: `Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA` is expressible in a `.shader` `RenderState`, `Queue = "Transparent"` exists, and `CgTransparentRenderer` actually runs. Nothing is pretending. |
| **Alpha Clip Threshold** | **IN** — new | One `discard` in the fragment body. `Queue = "AlphaTest"` already exists. Cheap, real, and the only way to get cutout foliage. |
| **Normal (Tangent Space)** | **OUT** | Nothing lights it. |
| **Metallic** | **OUT** | Nothing lights it. |
| **Smoothness** | **OUT** | Nothing lights it — this is the direct answer to the question asked. |
| **Ambient Occlusion** | **OUT** | Nothing lights it, and AO is a *multiplier on ambient*, of which there is none. |
| **Emission** | **OUT** | The subtle one. Emission means "colour added after lighting". With no lighting, everything is already emissive — `Emission` and `BaseColor` would be two ports that add together with neither attenuated, i.e. the same port twice. It returns the day lighting does, and not before. |

**Result: Vertex { Position, Normal } · Fragment { Base Color, Alpha, Alpha Clip Threshold }.**

#### Make the port list data, because the OUT column is a queue, not a graveyard

Every rejection above is "no lighting yet", and `CgFrameData` says v2 explicitly. `CgMasterNode.PORTS` is
currently a `static final List.of(...)` with the stage split implied by which port the emitter reads.
Restructure it as a **per-block declaration keyed by `CgShaderDomain`**, so that adding Smoothness later
is one list entry plus one line in `CgShaderEmitter` rather than a re-think. This is cheap now and
expensive to retrofit.

#### One node or two? — recommend **one node, two labelled sections**

Unity draws two boxes joined by a wire. That wire is **decorative** — it is not an edge, it carries no
value, and it exists to show stage order.

| Option | Cost |
|---|---|
| **One master, one widget with `Vertex`/`Fragment` section headers** ✅ | No new concepts. One id, so selection, drag, undo and graph-level settings all keep working unchanged. Visually one box instead of two. |
| Two document nodes (`cg:master/vertex`, `cg:master/fragment`) | Needs a rule for which one owns `#type`/`Queue`/`Tags`/`Properties`, and deleting one half leaves a graph that compiles to nothing. Two things to keep in sync that are conceptually one. |
| One master rendered as two widgets | Breaks the assumption `GraphSelection`, `GraphNode` and the drag path all rest on — that a node id maps to one widget. |

The domain split is already how the compiler thinks (`CgShaderDomain`, and rooting a compile at
`Position` versus `BaseColor` is what tells the emitter which stage it is writing), so sections are a
*presentation* of a distinction that already exists rather than a new one.

#### Migration: `BaseColor` `vec4` → `vec3`

Not free. A graph wiring a `vec4` into `BaseColor` currently works; afterwards it is a narrowing
conversion, and per the existing invariant truncating swizzles are only permitted into `DYNAMIC` ports or
compiler-synthesised implicit links. Options are to let the emitter synthesise a `.rgb` for this one port
(consistent with `wireImplicitDefaults` already existing) or to reject it and make the user add a Split.
**Recommend the former** — it is the same "the compiler may narrow what it wired itself" carve-out.

#### Tests

Follow 6.3.5's precedent: the load-bearing assertion is that the emitted shader **parses through the real
`CgShaderParser`**, because substring assertions pass happily while emitting a file the parser rejects.
Then: Alpha reaches `fragColor.a`; a wired Alpha Clip Threshold emits a `discard`; a vertex `Normal`
reaches the varying rather than being dropped; and the `vec4`→`vec3` narrowing is synthesised rather than
erroring.

---

### 6.3.12 The Main Preview · `DONE` (2026-08-04)

> **Shipped**: `CgMeshBuilder.cylinder` / `capsule` over a shared `revolve` sweep · `CgPreviewMesh` (the
> shape set) · `CgMainPreviewRenderer` (own target, orbit, zoom, redraw-on-change) ·
> `MainPreviewPanel` (title, surface, right-click shape menu, drag to orbit, wheel to zoom) · CSS in
> `default.css` + `graph.css` · wired into the gallery's shader page. 16 new tests.
>
> #### Where it diverged
>
> **It compiles the real `CgShaderEmitter`, not `CgPreviewEmitter`.** The plan said to reuse
> `CgPreviewRenderer.render(graph, nodeId)` rooted at the master. That cannot work — the master has no
> output port for a preview to visualise — and would have been wrong even if it did: the preview wrapper
> only follows the colour chain, so a graph displacing `Position` would draw un-deformed, which is most of
> the reason to want a mesh under it.
>
> **The quad cannot be framed to survive orbit, and that is now a stated decision.** Writing the framing
> test turned up that a flat quad tilted 45° after a 90° yaw presents its *diagonal* — `√2`, not 1. Framing
> for that leaves a permanent 40% margin around every 2D preview, which is exactly the letterboxing
> `ShaderNodePreview` already refuses for quads. So solids fit at every angle and the quad fills at rest
> and clips when tilted, asserted separately in `CgPreviewMeshTest`.
>
> **No checkerboard yet** — the surface is a solid dark colour. The two honest ways to draw one are a
> repeating texture asset (does not exist) or a grid of quads painted in Java (which would put two colours
> in Java, against this project's rule). Recorded in `graph.css` beside the rule it replaces.
>
> #### The bug the main preview existed to find: `struct v2f { };`
>
> The panel drew a plain white sphere for a graph whose GLSL was correct in every other respect.
> **GLSL has no empty struct** — `struct v2f { };` is a syntax error, not an empty type — and the emitter
> has written one for every zero-varying graph since 6.3.5.
>
> It hid because **nothing had ever fed the real emitter's output to a driver.** `CgShaderParser` accepts
> it (structurally it is a fine `.shader`), so every test passed; the editor's source pane displayed it
> looking entirely right; and node thumbnails go through `CgPreviewEmitter`, whose v2f always carries
> `uv`/`objectPos`/`normal` and is therefore never empty. The driver's own report was
> `error C0000: syntax error, unexpected '}'` followed by a cascade about `cg_InstanceId` that had nothing
> to do with anything — and `CgMaterial`'s fallback drew white.
>
> Fixed with a single padding member, and pinned by `aGraphWithNoVaryingsStillEmitsALegalStruct`. The
> broader lesson is the one `--mode=shader-compile-audit` already exists for: **a parse is not a compile**,
> and the first consumer to actually run generated GLSL will find whatever the parser was willing to
> forgive. Every zero-varying graph — which is to say anything purely fragment-side, the most common shape
> there is — was affected.
>
> **A `ByteBuffer.duplicate()` trap, for the next person reading a mesh back in a test:** `duplicate()`
> returns a `BIG_ENDIAN` buffer whatever the original was, so every absolute `getFloat` is byte-swapped —
> silently, into plausible-looking garbage. Both new mesh tests restate `order(nativeOrder())`.

### 6.3.12 The Main Preview · original plan (2026-08-03)

A floating panel showing the **whole graph** on a real mesh, with a right-click menu of mesh presets.

#### Most of this already exists

| Need | Have |
|---|---|
| Floating, resizable, movable panel | `Dialog` + `DialogManager`, `resize:` handles, `UIResizer` |
| Right-click mesh menu | `Menu` / `MenuItem` / `Popover`, with light dismiss and Escape already correct |
| Render a graph to a texture | `CgPreviewRenderer` — FBO pool, MSAA, per-frame budget, visibility culling, caching |
| Show that texture | `ShaderNodePreview` |
| Drive it per frame | `ShaderGraphPreviews`, a `UIFrameTicker` that already holds the master node |

So the Main Preview is mostly **composition**. The genuinely new parts are the mesh set, the orbit
gesture, and one decision below.

#### Meshes — two of Unity's seven do not exist yet

`CgMeshBuilder` ships `unitCube`, `quad2D`, `plane`, `uvSphere`, `icosahedron`.

| Preset | Status |
|---|---|
| Sphere | ✓ `uvSphere` — already what node previews use |
| Cube | ✓ `unitCube` |
| Quad | ✓ `quad2D` |
| **Cylinder** | ✗ **new `CgMeshBuilder.cylinder(...)`** |
| **Capsule** | ✗ **new** — a cylinder with hemispherical caps, so cheapest as a variant of the same builder rather than a second one |
| Sprite | ✓ = Quad. Unity distinguishes them by *material* (a sprite is unlit and premultiplied); with no lighting ours would render identically. **Recommend dropping it** rather than shipping two menu entries that produce the same picture. |
| Custom Mesh | no-op, as asked |

New procedural meshes are a legitimate CrystalGraphics addition under the ownership rule — a new backend
capability goes in the backend.

#### The decision to make before building: **the preview cannot be lit**

Unity's preview ball is shaded, which is what makes it read as a ball. Ours cannot be, for the same
reason Metallic and Smoothness are out of 6.3.11: there is no light.

| Option | Verdict |
|---|---|
| **Unlit — draw exactly what the engine draws** ✅ | Honest. Any graph whose output varies with UV, position or normal reads perfectly; only a *constant* colour renders as a flat silhouette, and a constant colour has nothing to show anyway. |
| Preview-only headlight/Lambert | Pretty, and a lie. The user would tune a shader against shading the pipeline cannot produce and never see it in game. This is the worst outcome available: it looks the most finished. |
| Unlit with a toggle | The toggle is a setting for a feature we decided against; revisit if lighting lands. |

**Recommend unlit**, recorded alongside 6.3.11 because it is the same fact — when lighting arrives, the
preview shading and the PBR ports come back together, and they should be one piece of work.

Add a **checkerboard backdrop** instead. That is not decoration: with `Alpha` becoming a real port,
transparency is the one thing a flat colour field genuinely cannot show against a solid background.

#### Orbit and zoom

Drag to orbit, wheel to zoom. Without it a cube is a square and the mesh menu is close to pointless. The
gesture machinery is all present (pointer capture, `UIDragController`).

**Rotation and zoom are view state** — never an `Edit`, per the boundary the invariants already draw for
scroll and selection. Same for the **chosen mesh**: which shape you are looking at does not change what
the shader does, so it must not enter `UIDescriptionCodec`/the graph document and must not be undoable.

#### Rendering cost

`CgPreviewRenderer` defaults to 256px with a budget of 4 draws per frame. The Main Preview is larger
(~320–400px) and must **share that budget** rather than bypass it, or one big preview starves every node
thumbnail on a busy graph. It re-renders when the graph changes or the view moves — not per frame.

Rooting: reuse `CgPreviewRenderer.render(graph, nodeId)` with the **master node** as the root rather than
compiling the real emitted `.shader` separately. One code path, one set of bugs, and the preview stays
consistent with the node thumbnails it sits beside.

#### Tests

Mesh builders are headless and GL-free: cylinder and capsule produce closed manifolds, unit-length
normals, sane vertex/index counts, and UVs in range. Then: the mesh choice does not appear in the encoded
document and leaves `UndoStack` empty; the panel requests a re-render on a graph change and *not* on an
idle frame.

### 6.3.13 The Graph Inspector · `DONE` (2026-08-05) — all three tabs shipped

The last big missing surface. Unity calls it the **Graph Inspector** and it is the panel marked **F** in
`docs/research/unity-inspector/07-full-window.png`.

> **Shipped**: `ShaderGraphInspector` (the `TabView` frame), `ShaderNodeInspector` rewritten from
> placeholder to five live selection states, `ShaderGraphSettingsPanel` (shader settings generated from
> declarations, preview view-state shared with `MainPreviewPanel`, compile stats), `ShaderGraphSettings`
> (the three `Setting<T>` declarations), `SettingsConfigurator`, `ConfiguratorPanel.addRow`/`clearRows`,
> `NodeFieldBinder.buildMultiControl`. `CrystalEditor` re-pointed. Tests:
> `ShaderGraphSettingsTest` (9), `ShaderNodeInspectorTest` (7).
>
> **Since**: the Properties form shipped with 6.3.14 — selecting a pill fills the same surface a node
> does, through `ShaderPropertyForm`'s typed `Default` editor. Multi-select editing is wired through
> `buildMultiControl` but still has no test of its own.
>
> **What contact with the code changed:** the model gap was answered by the general gear (P6's 6.1.13)
> rather than a map on `GraphDocument`, which is what the user's redirect asked for and is plainly
> better — the settings are now saveable, undoable, content-hashed AND enumerable, and the Graph tab is
> generated from the declarations instead of hand-written.

#### What exists today, honestly

`ShaderNodeInspector` is a **placeholder and says so in its own javadoc**: fifteen rows of fixed sample
values ported from the gallery's configurator page, bound to nothing. Selecting a node does not change
them and editing one changes no node. It is docked in `CrystalEditor` as a tab beside
`compiled_graph.shader`.

What it bought was real — it is how the control kit got looked at in the frame it will actually be used
in — but it is now the thing standing between a working graph and a usable one.

#### What Unity ships, read off the eight reference shots

| Shot | What it establishes |
|---|---|
| `07-full-window.png` **F** | The frame: a title, **two tabs** (`Node Settings` / `Graph Settings`), a label column left, a control column right, a scrollbar |
| `07` under `Graph Settings` | `Precision` dropdown · a `Target Settings` **header** · an `Active Targets` **list** with `+`/`−` · a **foldout** per target holding `Material`, `Allow Material Override`, `Workflow Mode`, `Surface Type` |
| `01-inspector-property.png` | `Node Settings` with something selected: a **bold caption naming the target** (`Property: Vector3`), then `Exposed` / `Reference` / `Default` / `Precision` |
| `06-inspector-precision.png` | The smallest legal panel is **one row**. There is no minimum |
| `03-inspector-keyword-enum.png` | The list editor: reorder handles, two columns, `+`/`−` footer |
| `07` **E**, `08-blackboard-categories.png` | The **Blackboard** is a SEPARATE panel, not a tab — property pills with a dim right-aligned type, a `+`, and foldout categories |

#### The one insight that decides the architecture

From `docs/research/unity-inspector/README.md`, and it is the most useful sentence in the whole
research set:

> the port-attached editors are *the same controls* as the inspector's, only anchored and sized
> differently. A dropdown left of a port and a dropdown in an inspector row are one widget in two hosts.

We are already built for this and did not notice. `NodeFieldWidgets` produces `ConfigControl`s — the
same `NumberControl`, `SelectControl`, `ColorControl`, `VectorControl` the configurator kit registers —
and `NodeFieldBinder` is already the *single writer* that turns a change into a `SetNodeFieldEdit`.

**So the inspector is not a new control layer. It is a THIRD PLACEMENT for `NodeField`**, alongside the
node body and the port editor:

```
NodeField (declaration)
   |
   +-- node body      -> GraphNode.addControl(label, control)      6.3.8
   +-- port editor    -> NodePort.setDefaultEditor(control)        6.3.8
   +-- inspector row  -> Configurator(descriptor, control)         6.3.13  <- new
                              ^ all three build through NodeFieldWidgets
                              ^ all three write through NodeFieldBinder
```

Anything else — a `ConfigDescriptor` mirror of every `NodeField`, an inspector-only widget set — is a
second copy of "what does a partly-typed number mean", which is precisely the duplication
`NodeFieldWidgets`' own javadoc records having already removed once.

**Consequence, and it is the whole reason this is cheap:** undo, gesture bracketing, live recompile and
the document-follows-widget wiring from the scrub-undo fix all come for free, because they live in
`NodeFieldBinder` and the inspector reuses it verbatim. Edit a value in the inspector and the on-node
editor updates; edit it on the node and the inspector updates; Ctrl+Z moves both. **No new code is
needed for any of that** — it is what `followDocument` already does for every binding of the same field.

#### Scope — what of Unity's inspector fits us

| Unity feature | Verdict | Reasoning |
|---|---|---|
| **Two tabs, Node / Graph** | **IN** | The split is real: one is contextual, one is global. Collapsing them means a global setting scrolls off under whatever node is selected |
| **Bold caption naming the target** | **IN** | `ConfigDescriptor.header` exists and was built for exactly this (`Target Settings` in the shot) |
| **Node's own settings** | **IN** | The node's non-port `NodeField`s. Already declared, already rendered on the node — the inspector is a second view of them |
| **Port defaults in the inspector** | **IN**, and **better than Unity's** | On the node a port editor *vanishes* when the port is connected (`nodeport:blank`). In the inspector it stays, **disabled**, with the connection named. A vanished control is indistinguishable from one that never existed |
| **Read-only identity** (type id, node id, category) | **IN** | The line map already reports `line 12 emitted by cg:Math/Basic/multiply` and there is nowhere to look that up. One foldout, free |
| **Port list with resolved types** | **IN** | Dynamic ports resolve their type from what is wired (6.3.8). The resolved answer is currently only visible as a dot colour |
| **Multi-select editing** | **IN**, same-type only | One `CompositeEdit` across the selection. Unity does it; the alternative is that selecting two nodes makes the panel useless |
| **`Precision` (Single/Half)** | **OUT** | `mediump`/`highp` are not emitted, not parsed by `CgShaderParser`, and mean nothing on desktop GL. Exactly the "port whose only consumer does not exist" trap 6.3.11 rejected six ports for |
| **Targets / Active Targets list** | **OUT** | We have one target. A list widget for a set of size one |
| **`Material` / `Workflow Mode`** (Lit/Unlit, Metallic/Specular) | **OUT** | Both are lighting-model selectors. No lighting model — see 6.3.11 |
| **`Surface Type` / `Blend` / `Render Face` / `Depth Write`** | **IN**, as our own vocabulary | These are NOT lighting. They are `.shader` `RenderState` and `Queue`, which `CgShaderParser` genuinely reads and `CgTransparentRenderer` genuinely runs. Spelled in our tokens, not Unity's |
| **Blackboard as a separate panel** | **DEFERRED**, tab for now | See below |
| **Keyword / enum list editor** | **OUT** | `#pragma cg_feature` variants are not reachable from a graph yet |

#### The model gap this exposes: graph settings have nowhere to live

`CgMasterNode` holds `vertexFormat`, `renderType`, `queue` and the shader `Properties` map — and it is
**not a document**. It is a compile-time object `ShaderGraphEditor` constructs once and never
serialises. So today those settings are:

- not saved (`GraphCodecs.DOCUMENT` has never heard of them),
- not undoable (no `Edit` touches them),
- not content-hashed, so two graphs differing only in `Queue` hash identically.

Editing them from an inspector without fixing that would ship a panel whose changes silently evaporate
on reload — a worse outcome than the placeholder.

**The fix, and it is the load-bearing invariant applied literally:**

> *Document state goes through `Edit`s; view state is mutated directly.*

| Setting | Verdict | Because |
|---|---|---|
| `#type` (vertex format) | **document** | A reload must give it back |
| `RenderType`, `Queue` | **document** | Same |
| Shader `Properties` entries | **document** | Same |
| Preview **mesh** | **view** | Which shape you are looking at. Already settled in 6.3.12 and pinned by a test asserting it stays out of the encoded document |
| Preview **lighting** on/off | **view** | Same — it is viewport shading, which `CgShaderEmitter.Shading`'s javadoc is explicit about |

**The fix is NOT a map on `GraphDocument`.** That was the first draft and it is recognisably wrong: it
would be the *third* per-domain answer to "hold some named values" after `NodeData.properties()` and
`ElementStyle`, and the fourth consumer would write a fifth. The same shape has already been solved once
in this engine by the Command/Keymap stack — a declaration addressed by a stable string id, values held
per scope, resolved by walking outward, loadable as data.

So this item is **blocked on P6's 6.1.13**, the general settings gear, which is VS Code's
`platform/configuration/` ported (MIT). `GraphDocument` becomes a `SettingsScope` holding a `Settings`
like anything else, its options are declared as `Setting<T>`s the same way commands are declared, and the
inspector's Graph tab is *generated* from those declarations rather than hand-written — exactly as the
command palette is generated from `CommandRegistry`.

What 6.3.13 then owns is only the shader-specific half: **which** settings exist (`#type`, `RenderType`,
`Queue`), that they resolve onto `CgMasterNode` at compile time so the master goes back to being purely
the compiler's object, and that they reach `GraphCodecs` — which moves the **persistence** gap forward
for free.

#### Properties (the Blackboard) — a tab now, a panel later

Unity's Blackboard is a separate panel because you drag properties *out of it* onto the canvas. That
drag is what makes it worth a permanent pane — and it needs a `Property` node type that references a
declaration, which does not exist.

Until it does, a Properties **tab** is the honest shape: you can declare, rename, retype and delete a
property and watch the generated `Properties { }` block change in `compiled_graph.shader`. The model —
the document map, the edits, the codec — is identical either way, so promoting it to a panel later is a
re-host and not a rewrite. **Say so in the class javadoc**, the way `ShaderNodeInspector` currently says
it is a placeholder, so nobody has to guess whether the tab is the intended end state.

#### The three tabs, in detail

**Tab 1 — `Node`.** Rebuilt on `GraphSelection.onChanged`. Five states, and each needs to be legible:

| Selection | Shows |
|---|---|
| nothing | `Nothing selected` — a plain message, not an empty panel. An empty panel reads as broken |
| one node | header `<label>` · body fields · port defaults (disabled when connected) · `About` foldout: type id, category, node id, ports with types |
| the master | header `Output` · its `Vertex` and `Fragment` blocks as read-only port lists · a line pointing at the `Graph` tab, because that is where its settings actually are |
| one wire | header `Connection` · from `node.port(type)` to `node.port(type)`, read-only |
| N nodes | header `N nodes selected` · if all one type, the shared fields, writing to every one through a `CompositeEdit`; otherwise a per-type count |

**Tab 2 — `Graph`.** Rebuilt on `document.onChanged`.

- header `Shader`
  - `Vertex Format` — SELECT (`spatial`, `pos3_uv2_col4ub`, `pos2_uv2_col4ub`)
  - `Render Type` — SELECT `Opaque` / `Transparent`
  - `Queue` — SELECT `Background` / `Geometry` / `AlphaTest` / `Transparent` / `Overlay`
- foldout `Preview` — **view state, written directly, no undo**
  - `Mesh` — SELECT over `CgPreviewMesh`
  - `Lighting` — BOOLEAN
  - both already exist behind the Main Preview's context menu; this is a **second host for the same
    state**, so they must read from and write to `MainPreviewPanel` rather than keeping a copy
- foldout `Compile` — read-only: nodes, edges, varyings, characters, errors. From
  `ShaderGraphEditor.lastCompile()`, which already carries every number

**Tab 3 — `Properties`.**

- header `Properties`, an `Add` button, and one row per declaration: name (TEXT), type (SELECT over the
  `CgShaderType`s whose `propertyTypeName()` is non-null), default (TEXT), and a remove button
- every write is a `SetGraphSettingEdit` against the document's settings map
- empty state: `No properties. A property becomes a uniform the material can set at runtime.`

#### Wiring, A to Z

```
CrystalEditor
  +-- inspector()  ->  ShaderGraphInspector(shaderGraph())         NEW  -- the tabbed frame
                         +-- TabView
                         +-- Tab "Node"        -> ShaderNodeInspector    REWRITTEN, no longer a placeholder
                         +-- Tab "Graph"       -> ShaderGraphSettings    NEW
                         +-- Tab "Properties"  -> ShaderGraphProperties  NEW

ShaderGraphEditor  gains  master() / selection() / document()         (accessors only)
GraphDocument      implements SettingsScope                           6.1.13 -- the general gear
ShaderGraphSettingKeys  the shader domain's Setting<T> declarations   NEW
GraphCodecs        gains  the settings map                            CHANGED
ShaderGraphBridge  applies settings onto CgMasterNode at compile      CHANGED
ConfiguratorPanel  gains  addRow(descriptor, prebuiltControl)         NEW  -- the seam that lets the
                                                                            inspector host a widget
                                                                            NodeFieldWidgets built
```

**Two traps to write down before they are hit:**

1. **Rebuild-on-selection collides with the "never rebuild what is being dragged" invariant.** The
   inspector rebuilds its rows when the selection changes — and a *press* changes the selection. If a
   rebuild ran while a scrub was live inside the panel, `screenToLocal` would go stale exactly the way
   the table header froze. It cannot happen for the inspector's own controls today (selection changes
   come from the canvas, not the panel) but the rebuild must still be **guarded on
   `ConfigControl.isInteracting()`** rather than assumed safe, because the multi-select path is
   reachable from a Shift+click while a value is being dragged.

2. **The panel must not rebuild on its own writes.** Every field write emits `document.onChanged`, and
   the `Graph` tab listens to it. Rebuilding there would destroy the control mid-edit. Same fix
   `followDocument` already uses: track what this panel last wrote and ignore the echo.

#### Tests

Headless where possible — the whole model half is (`headlessTest`), the panel half needs `test`.

- `GraphSettingsEditTest` (headless): a setting round-trips through the document; the edit undoes; two
  writes to the same key inside a merge window collapse; the settings map survives `GraphCodecs`
  encode/decode; a graph differing only in `Queue` **hashes differently**
- `GraphInspectorSelectionTest`: each of the five selection states produces the right header; selecting
  a node then another rebuilds; deselecting shows the empty state
- `GraphInspectorBindingTest`: editing a row writes through to the document · the on-node editor for the
  same field updates · Ctrl+Z reverts **both** · a connected port's row is present but disabled
- `GraphInspectorMultiEditTest`: three same-type nodes, one write, one undo step, all three changed
- `GraphSettingsCompileTest`: changing `Queue` in the panel changes the emitted `.shader` text, and the
  result still parses through the real `CgShaderParser`
- the existing 6.3.12 test asserting the preview mesh stays **out** of the encoded document must keep
  passing, which is what proves the view/document line was drawn in the right place

#### Steps

1. `GraphDocument.settings()` + `SetGraphSettingEdit` + codec + hash — model only, headless tests
2. `ShaderGraphBridge` applies settings to `CgMasterNode`; `ShaderGraphEditor` accessors
3. `ConfiguratorPanel.addRow(descriptor, control)` — the pre-built-control seam
4. `ShaderNodeInspector` rewritten: the five selection states, real bindings
5. `ShaderGraphInspector` — the `TabView` frame; `CrystalEditor` re-pointed
6. `ShaderGraphSettings` — shader settings, preview settings shared with `MainPreviewPanel`, compile stats
7. `ShaderGraphProperties` — declare/edit/remove
8. Multi-select editing
9. Styling pass in `default.css` / `graph.css`

---

### 6.3.14 Properties and the Blackboard · `IN PROGRESS` (2026-08-05) — steps 1–7 shipped, styling remains

> **Shipped**: `GraphProperty` + `PropertyEdits` (Add/Remove/Change/Move) + codec; `CgShaderType`'s
> `propertyDeclarationType()`/`propertyAccessSuffix()` with the vec3 landmine pinned;
> `ShaderPropertyNodes` + `ShaderGraphBridge` declaration and preview-literal path; `BlackboardPanel`
> with pills, the `+` menu, add/remove/duplicate/rename, panel-scoped commands; drag onto the canvas;
> the property form in the Node Settings tab; **drag back onto the list to reorder**; **categories**,
> folding, and category rename/remove. `PropertyPill` and `CategoryHeader` share `InlineRename`.
> Tests: `BlackboardPanelTest` (32), `ShaderPropertyCompileTest`, `CgPropertyDeclarationTest`.
>
> **Not shipped**: step 8, the styling pass.
>
> **What contact with the code changed, worth recording:**
> - A drop slot is **not** a document index. `GraphDocument.moveProperty` takes the position the row
>   ends at *after* being lifted out, which is one less than the slot pointed at whenever the drag went
>   downward. Quiet when wrong — the row lands one place short and reads as an imprecise drag.
> - The drop indicator has to be **absolutely positioned**. An in-flow one moves the rows whose
>   boundaries decide where it goes, so a pointer near a boundary oscillates between two slots.
> - **An empty category cannot be stored.** A category is a field, so nothing carries the name of a group
>   with no members. Unity's `+` creates one anyway, so the panel holds it as view state and it does not
>   survive a reload — the honest consequence of "a field, not a tree", recorded rather than papered over.
> - Vector adaptation was forced by this item: a Vector 2 into a dynamic port widened it and made an
>   already-drawn edge illegal mid-recompile. `CgShaderType.canFeed`/`promote` now pad and truncate.

Research: `docs/research/unity-blackboard/` — 12 images and a README, pulled and captured 2026-08-04.
**Read that README first**; this section is the decisions, not the observations.

#### Why this is one item and not two

The Properties *tab* 6.3.13 deferred cannot be built on its own, because a property is not a panel
feature — it is a **document entity** that a node references, a uniform the emitter declares, and a
value a material sets. The panel is the last of the four, not the first. Building the tab alone would
mean a list of things that exist nowhere else.

#### What a property IS here

```java
record GraphProperty(String id, String name, String reference, String typeId,
                     String defaultValue, boolean exposed, String category,
                     Map<String, String> options)
```

- **`id`** — generated and stable. What a node stores, so renaming a property cannot orphan its nodes.
  Unity keys on its own object id for the same reason.
- **`reference`** — the uniform's name in the generated shader. Derived from `name` on first entry
  (`Vec prop` → `_Vec_prop`) and independently editable after, exactly as Unity does.
- **`defaultValue`** — text, like every other value in this document layer. `NodeData.properties()` and
  `Settings` both already make that trade and for the same reason: the storage layer stays free of value
  types, so a server can author a graph it cannot render.
- **`options`** — per-type extras (`mode`, `min`, `max`, `hdr`, `fallback`). A map rather than fields
  because the set differs per type and a schema change would break stored documents.

**Not in `Settings`.** The general gear is right for scalar options keyed by a stable name; a property is
an *ordered list of records that other entities reference by id*. Encoding that into a flat key space
(`property.0.name`) is possible and is the wrong shape — properties are peers of nodes and edges, and
belong beside them on `GraphDocument`.

#### Categories — a field, not a tree

Unity's `+` menu offers `Category` as its first entry, so a category is created like a property rather
than being a container filled afterwards. Ours is a **string on the property** (`""` = uncategorised),
which is how `NodeType.category()` already works in this codebase. That buys grouping and ordering with
no second entity, no tree to serialise, and no "deleting a category deletes its properties" rule to get
wrong. Collapse state is view state and lives on the panel, like every other foldout (6.3.13).

#### Type scope — and one landmine found while scoping it

**`CgPropertiesParser` hard-bans `vec3` as a material property type** ("STD140 pads vec3 to 16 bytes but
the GLSL compiler places the next field 12 bytes later"). And `CgShaderType.propertyTypeName()` returns
`"vec3"` for `VEC3`. So **declaring a Vector 3 property emits a `.shader` that fails to parse** — for one
of the three most common property types there is. Nothing hits it today only because no code path can
reach `CgMasterNode.property()` yet; this item is the path.

> **Fix in CrystalGraphics, where the constraint lives.** `CgShaderType` gains
> `propertyDeclarationType()` (the token to *write* — `vec4` for `VEC3`, itself otherwise) and
> `propertyAccessSuffix()` (`.xyz` for `VEC3`, empty otherwise). The emitter declares one and reads the
> other. Putting the mapping in CrystalGUI would be the second place that knows about an alignment rule
> belonging to the parser.

| Unity type | Verdict | Reasoning |
|---|---|---|
| **Float** | **IN** — `float` | Plus `Mode`: `Default`, `Slider` (the parser already understands `Range(min,max)`), `Integer` (`int`) |
| **Vector 2** | **IN** — `vec2` | |
| **Vector 3** | **IN** — declared `vec4`, read `.xyz` | See the landmine above |
| **Vector 4** | **IN** — `vec4` | |
| **Color** | **IN** — `color` | The parser has a real `color` type. `Mode: Default/HDR` maps to nothing yet — HDR is recorded and unused, so it stays **OUT** until something consumes it |
| **Boolean** | **IN** — `boolean` | The spelling trap `propertyTypeName()` already documents: GLSL says `bool`, the Properties block says `boolean` |
| **Texture 2D** | **IN** — `sampler2D` | Default is a fallback name (`"white"`), which is what the parser's quoted-string sampler default already is |
| **Texture 2D Array** | **IN** — `sampler2DArray` | |
| **Texture 3D** | **IN** — `sampler3D` | |
| **Cubemap** | **IN** — `samplerCube` | |
| **Matrix 2 / 3 / 4** | **OUT** | `propertyTypeName()` returns null for matrices and the parser has no matrix property type. Adding one is a CrystalGraphics change with a real STD140 question in it, not a rider on this item |
| **Gradient** | **OUT** | Unity bakes a gradient into a struct plus a sampling function; it is not a uniform type. A genuine feature, and a large one |
| **Virtual Texture** | **OUT** | Streaming virtual texturing does not exist here |
| **Sampler State** | **OUT** | The material system has no notion of a sampler object separable from a texture |
| **Keyword** | **OUT this pass, and the most portable of the five** | `#pragma cg_feature` and `CgMaterial.enableKeyword` are *already real*. A Boolean keyword is a genuinely small follow-up; Enum and Material Quality are not |

**Result: Float (3 modes) · Vector 2/3/4 · Color · Boolean · Texture 2D / 2D Array / 3D / Cubemap.**
Ten of Unity's sixteen, every rejection tied to something that does not exist rather than to taste.

#### The property node

Dragged from the board onto the canvas, per `14-drag-property-to-node.png`. It is a pill with **one
output, no inputs and no settings** — the node *is* a reference.

- Type id `cg:property`, with the property's `id` stored in `NodeData.properties()`.
- Its label and its port type are **read from the document at build time**, not copied — so renaming or
  retyping a property updates every node referencing it, which is the whole point of referencing by id.
- It emits `{Out} = <reference><accessSuffix>;` and nothing else.
- A node whose property has been deleted becomes an **error node**, not a silent one: the same choice
  `GraphDocument` makes for an unknown node type, for the same reason.

`ShaderGraphBridge` synthesises a `CgShaderNode` per referenced property at compile time (via
`CgTemplateShaderNode`), and declares each *exposed* property on `CgMasterNode` before emitting. An
unexposed property is still a uniform — `exposed` controls the material inspector, not existence.

#### The Blackboard panel

**A floating overlay on the canvas, exactly like the Main Preview** — same `graph.addOverlay` seam, same
anchored placement, same resize grip. 6.3.12 already built that machinery; this is its second consumer,
which is the test of whether it was built as a widget or as a one-off.

| Part | Detail |
|---|---|
| Title | The graph's **file name**, not "Blackboard" |
| Subtitle | Its asset path, dim |
| `+` | Opens the type menu — with a separator under `Category`, which `Menu` gained in 6.3.12 |
| Body | A `ScrollerView` of pills, grouped by category |
| Pill | Rounded capsule: exposed dot, name; type right-aligned, dim, **outside** the capsule |

Behaviour taken from the docs: rename by double-click, reorder by drag, `Delete` to remove, and it
**cannot be dragged off the graph** — which our `placeAt` clamp already enforces for the Main Preview.

#### Selection, and where the form lives

Unity puts the property form in the **Node Settings tab**, not in a panel of its own — selecting a pill
fills the same surface selecting a node does. So the Blackboard needs to be able to *own* the
inspector's subject.

`GraphSelection` holds nodes and one wire. Rather than teach it about properties — it is a **graph**
selection, and a property is not in the graph — the panel exposes `onPropertySelected`, the inspector
listens, and each clears the other. Two sources, one subject, and neither has to know what the other can
hold.

The form itself is `Property: <name>` plus Name, Reference, Default, Exposed. **`Default` is a typed
editor**, which is why the form cannot be a fixed row list: Vector 2 draws two boxes, Color a swatch,
Texture a path field. That mapping is one method, and it is the same `ConfigDescriptor` selection
`SettingsConfigurator.describe` already makes for settings.

`Precision` and `Override Property Declaration` are **OUT**, on the 6.3.11 test: no precision modes are
emitted and nothing would read either.

#### Tests

- **model** (headless): a property round-trips through `GraphCodecs`; add/remove/rename/reorder each
  undo; a reference is sanitised (leading underscore, illegal characters replaced); two graphs differing
  only in a property hash differently
- **compile**: each of the ten types emits a `Properties` block that **parses through the real
  `CgShaderParser`** — the assertion that would have caught the `vec3` landmine before a user did
- **vec3 specifically**: declared as `vec4`, read as `.xyz`, and the emitted shader parses
- **node binding**: renaming a property relabels its nodes; retyping changes the port type; deleting it
  leaves an error node rather than a crash
- **panel**: `+` adds; `Delete` removes; a drag onto the canvas creates a node referencing the right
  property; the panel cannot be dragged out of the viewport
- **inspector**: selecting a pill shows the property form; selecting a node clears it and vice versa

#### Steps

1. `GraphProperty` + document list + edits + codec — model only, headless
2. `CgShaderType.propertyDeclarationType()`/`propertyAccessSuffix()` in CrystalGraphics, with the vec3 test
3. The property node type + bridge: declare on the master, synthesise the node, emit the reference
4. `BlackboardPanel` — the floating panel, pills, `+` menu, add/remove
5. Drag a pill onto the canvas
6. The property form in the Node Settings tab
7. Rename, reorder, categories — **DONE** (2026-08-05)
8. Styling pass

---

---

## Ordering, and what blocks what

**Status as of 2026-08-03:** 6.3.1–6.3.5 `DONE` · 6.3.7 `DONE` · 6.3.6 `IN PROGRESS` (93 nodes; Math
63/64 done, Channel 3/4, UV 6/10, Utility Logic 6/12, Procedural 6/9, Input 8/54, Artistic untouched)
· 6.3.8 `IN PROGRESS` (dropdowns, inline value editors, implicit-UV port defaults and dynamic port
arity/colour-by-resolved-type all done; error mapping and debounce remain) · 6.3.9 `DONE` ·
6.3.11 `MOSTLY DONE` (ports shipped; the two-box visual is not) · 6.3.12 `DONE` ·
6.3.13 `PLANNED` (the Graph Inspector — the last big missing surface).
The whole stack runs end to end in the gallery's **shadergraph** page.

**6.3.9 blocks nothing and is blocked by nothing** — it is a general search facility the create menu
happens to be the first consumer of, so it can be picked up whenever, independently of the node library.

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
