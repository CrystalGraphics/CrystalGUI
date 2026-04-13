# Crystal GUI:

The idea of this mod is to be UI engine similar to a lightweight web browser.

## Core library
The core of CrystalGUI can be written in versions of Java newer than 8 and depends on CrystalGraphics.
- DOM-style component tree 
- It uses Taffy as a layout backend (already included as a dependency)
- The renderer is supposed to be platform agnostic 
- Supports DOM-style three-phase events (capture/target/bubble)
- Mouse/Keyboard/Input events. (Signal/Slot design pattern? I heard EventBus is unrecommended to use. If you know of any better design patterns for a web-browser like layout engine lemme know) 
- Data-Driven Reactivity (Property Binding)
- RPC Events
- XML-based GUI creation (Delegate to V2)
  - need a component registry for that in the future though
- Code based GUI creation
- Stylesheet support

## UI Render Architecture (V3.1 Draw-List)

The primary UI rendering model uses a **painter's-order draw list** instead of typed layers.

### Key concepts
- `CgUiDrawList` — packed `int[]` command pool recording draw commands in DOM traversal order
- `CgUiPaintContext` — paint surface passed through UI traversal (recording side)
- `CgUiDrawListExecutor` — stateless sequential replay
- `CgUiDrawState` — cached command-local draw state (reference-identity merge)
- `CgUiBatchSlots` — `Map<CgVertexFormat, CgBatchRenderer>` with stable slot indices
- `ScissorStack` — allocation-free nested clips (dual-mode: logical + GL apply)
- `CgScissorRect` — lives in CrystalGraphics `api/state/`, pooled by ScissorStack

### Source package guide
- `src/main/java/com/crystalgui/core/render/AGENTS.md` — authoritative package guide

### UI element and test packages
- `ui/elements/` — reusable `UIElement` subclasses (`UiPanel`: filled rectangle via draw-list)
- `ui/test/` — reusable demo/test UI factories (`CguiTestUi`: static factory building a test `UIContainer`)

### Frame lifecycle
```
paintContext.beginRecord()
  root.drawSubtree(paintContext)   // DOM traversal, painter's order
paintContext.endRecord()
executor.execute(drawList, slots, projection)  // replay
```

## CrystalGraphics Ownership Boundary (Critical)

CrystalGraphics **must own the rendering backend**.

- CrystalGUI may define renderer-facing abstractions and scene/UI draw orchestration.
- CrystalGUI must **not** become the owner of low-level OpenGL backend concerns.
- Fonts, shaders, framebuffers/render targets, VAO/VBO concerns, draw submission plumbing, GPU resource ownership, and modern GL pipeline capabilities belong in **CrystalGraphics**.
- CrystalGUI should consume those APIs and stay backend-using, not backend-owning.

Because CrystalGraphics lives in this same repository and is directly writable here:

- if CrystalGUI needs new rendering backend capabilities, we are **allowed and expected** to add them to CrystalGraphics directly;
- CrystalGUI should then integrate against those new CrystalGraphics APIs rather than reimplementing the backend itself.

Rendering direction going forward:

- We are not treating Minecraft 1.7.10 fixed-function rendering as the target architecture.
- We are moving toward **modern core GL 3.0+ style rendering pipelines**.
- CrystalGraphics will gradually backport 1.20.1-like rendering frameworks and capabilities to 1.7.10 where needed.
- CrystalGUI should be architected around those CrystalGraphics APIs from day one.


# For future reference:
Cg -> acronym for CrystalGraphics
Cgui -> CrystalGUI


## Code Style: Lombok

**Rule: Prioritize Lombok annotations to eliminate handwritten getter/setter boilerplate in all new code.**
Lombok generates Java 8-compatible bytecode. All annotations listed above work correctly with Java 8 and LWJGL 2.9.3. No runtime dependency is added — Lombok is `compileOnly`.

### When to Use Each Annotation

| Annotation | Use When |
|---|---|
| `@Data` | Simple POJOs / value objects with all fields participating in equals/hashCode/toString |
| `@Getter` / `@Setter` | Selective access — when you need getters on all fields but setters on only some, or vice versa |
| `@RequiredArgsConstructor` | Immutable classes — generates constructor for all `final` fields (pairs well with `@Getter` only) |
| `@Builder` | Complex object construction with many optional parameters |
| `@Value` | Fully immutable data carriers (makes class final, all fields private final, no setters) |
| `@ToString` / `@EqualsAndHashCode` | When you need only one of these without full `@Data` |
| `@Slf4j` / `@Log` | Logger field generation (prefer `@Slf4j` if SLF4J is available) |

### Guidelines

1. **Prefer `@Data` for simple POJOs** that are pure data holders with no complex logic.
2. **Use `@Getter` + `@RequiredArgsConstructor` for immutable classes** — avoid `@Data` when you don't want setters.
3. **Use `@Builder` for classes with 4+ constructor parameters** or when many parameters are optional.
4. **Apply `@Getter`/`@Setter` at field level** when only specific fields need accessors.
5. **Do NOT use `@Data` on entities or classes with inheritance** — use explicit `@Getter`/`@Setter`/`@ToString`/`@EqualsAndHashCode` instead to control behavior.
6. **Always use `@EqualsAndHashCode(callSuper = true)`** on subclasses to avoid subtle bugs.

---


## 1.7.10 Module
The main module for now. other modules will come in the future, but we must ensure all code added is fully cross-platform applicable, 
and thats also where the future abstraction layer comes in.
The 1.7.10 module of CrystalGUI contains the version-specific implementations, uses JVMDowngrader to make CrystalGUI & its dependencies run in Java 8.
Most of the logic should be handled in the core. 



LDLib2 Repo: research_repos/LDLib2/
Minecraft 1.20.1 source | `C:\Users\mazen\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.20.1-47.4.10_mapped_parchment_2023.09.03-1.20.1\forge-1.20.1-47.4.10_mapped_parchment_2023.09.03-1.20.1-recomp.jar`
AND DECOMPILED AT `X:\projects\mc1201_sources`


