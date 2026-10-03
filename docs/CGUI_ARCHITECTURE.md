# CrystalGUI — modules and packages

Where everything lives: what each module is, and what each package of `core/` owns. Moved from [`AGENTS.md`](../AGENTS.md), which keeps a one-line summary of each.

## Module layout — what actually compiles

`settings.gradle.kts` includes the engine modules, the loader trees and `gl-debug-harness`. `taffy` and
`gl-debug-harness` are **git submodules** that are ordinary Gradle subprojects (no settings file of their
own); CrystalGraphics is a submodule included as a composite `includeBuild`, whose
`dependencySubstitution` entries resolve `com.crystalgraphics:*:<version>` — and each modern node's
common — to local source (`gradle/module_integration/composite.settings.gradle.kts`).

| Module | In build? | State |
|---|---|---|
| `core/` | ✅ | The engine. Java 25, an abstract module (above). Everything below lives here. |
| `language/` | ✅ | The language stack — everything with a native or an engine behind it. Depends on `core/`; **`core/` must never depend on it**, which is what keeps tree-sitter's `.so`s and ECJ's ~13MB off a dedicated server. **Since J8 it ships as its OWN MOD, `crystalgui_language`**, and the rule now reaches the loader hosts too: `:language` is on their `lang` source sets and not on `main`, so no host class can name it. Its hosts are the `lang` source sets of `runtime/mc/1710`, the legacy tree and the modern tree (`common`, plus one entry class per loader). `.grammar` (six tree-sitter grammars), `.engine` (band selection, the ONE shared loader per band — `EngineHost` — the language-neutral `Analysis` answer and the `AnalysedLanguageServices` attachment every engine extends), `.java` (everything Java, split by what a class is FOR — `.ecj` the adapters, `.classpath` what a script compiles against, `.assist` completion and Quick Documentation, `.fix` the Alt+Enter catalog over `.fix.catalog`/`.fix.ast`/`.fix.edit`, `.exec` the `ScriptHost` runtime), `.js` (everything JavaScript, split by WHICH LOADER defines a class — `.host` may name `language.run`/`language.java` and never Rhino, `.rhino` is the reverse and holds `.rhino.resolve`/`.rhino.fix`/`.rhino.exec`), `.map` (the readable↔runtime boundary, on ASM), `.run` (the **engine-neutral** Run shell: `ScriptRuntime` SPI + `ScriptRuntimes` registry and `ScriptPolicy` at the root — which lives there because three of its four consumers are not JavaScript — over `.exec` (capture, stop, cache), `.console` (the transcript, UI-free) and `.view` (the only one that may import `com.crystalgui.ui`). `RunShellIsEngineNeutralTest` forbids the whole tree naming `.java`, `.js`, ECJ or Rhino, and still needs no change after the split because it matches by path PREFIX). `.resolve` is reserved. *(Was `syntax-treesitter/` until M4.)* |
| `taffy/` | ✅ | **The layout engine, VENDORED.** Git submodule ([`CrystalGraphics/taffy-java`](https://github.com/CrystalGraphics/taffy-java), branch `master`) — so `git clone --recursive`, like the other two. A fork of the published sources of `dev.vfyjxf:taffy:1.1.4` (MIT), carrying our own fixes to its measure path — see `taffy/MODIFICATIONS.md`, which is the statement of changes MIT requires, and `plan/engine-rewrite.md` D3. The package stays `dev.vfyjxf.taffy` because the shipped jar relocates it, so 165 call sites needed no edit and a stock copy in another mod cannot win a classloader race. **Depends on nothing** since 2026-09-10: the seven fastutil types it used are reimplemented in `dev.vfyjxf.taffy.collection`, which took the merged jar from 31.20 MB to 8.44 — fastutil was 63% of it. `MODIFICATIONS.md` §2 has the two behaviours that are silent when wrong. |
| `gl-debug-harness/` | ✅ | Git submodule (branch `master`), Java 25. **CrystalGraphics-only**: it names no CrystalGUI type, so one branch serves a CrystalGraphics project with no CrystalGUI too. The fastest way to run the UI — [Render testing](../AGENTS.md#render-testing--the-gl-debug-harness). |
| `harness-scenes/` | ✅ | CrystalGUI's scenes for the harness (`com.crystalgui.harness`), reaching it as a `HarnessExtension`. Java 25. Its build script puts it on `:gl-debug-harness:runHarness` — engines, `crystalgui.*` flags, asset roots — so the run command is unchanged. Included whenever the harness is. |
| `CrystalGraphics/` | ✅ (composite) | The rendering backend. Consumed, never reimplemented. |
| `runtime/mc/1710/` | ✅ | **In `settings.gradle.kts` and compiling** (`./gradlew :runtime:mc:1710:compileJava`). The real 1.7.10 host, and since W3 a HOST rather than a product — and since `plan_host` a host that decides nothing: `CgUiScreen` is Minecraft's screen lifecycle mapped onto `HostSession`'s, `Host1710` answers `HostServices`, `CgUiHud` answers `HostSession.PaintHost`, `CgUiInput` converts LWJGL2's origin and notch size and leaves the conventions to `HostPointer`, and `com.crystalgui.mc.v1710.probe` holds every probe adapter, of which `CgUiServerSmoke` is five facts over `probe.ServerSmoke`. `Mc1710Workspace` and `CgUiWindowMount` were **deleted**; anything still naming them is describing history. **It stays a module of its own on RetroFuturaGradle, deliberately** (legacy D3): it is aligned with the legacy host rather than folded into it — what the two share is in `runtime/mc/launchwrapper` and `core`, and the rest is how each version spells it. **Verified by `serverSmoke` and by running the client**; a green compile was never the claim. |
| `runtime/mc/modern/` | ✅ | **Forge 1.13.2+, NeoForge 1.20.2+, Fabric 1.14.4+**, a Stonecutter tree — one source tree, a node per Minecraft version, `:runtime:mc:modern:<branch>:<version>`; **read `runtime/mc/modern/README.md` before touching it**. `common` holds the host — `CgUiScreen`, `HostModern`, `CgUiInput`, `CgUiHud`, `Connections`, `WorkspaceHostModern` and `LifecycleCrystalGUI`, **the one class a loader talks to**; `forge`/`neoforge`/`fabric` are registration only. Every one of those is wiring: what opens, when it is raised, which arm paints and who may write are `core`'s. Each loader node compiles against the `common` node of its own version, and against CrystalGraphics' node of that version, and builds a **thin** jar — its own classes plus `common` relocated under `com.crystalgui.mc.<loader>.common` — which is what the root merge consumes. |
| `runtime/mc/shared/` | ✅ | **Java 8, merged once and never relocated**, for anything every loader variant must share without naming Minecraft. It holds the node mixin plugins: `CrystalGuiForgeMixins` and `CrystalGuiFabricMixins`, which gate the HUD mixins of the Forge 1.21.6 and Fabric 1.14.4 nodes (a node mixin's plugin must load on every loader, 1.7.10 included). A dev run takes it as a library, not a mod. `LoaderProbe`, `CrashVariant` and (since J11.0) the whole **variant selector** are CrystalGraphics': CrystalGUI requires CrystalGraphics on every loader, so a second copy bought nothing. The hosts register under their own heading, `CrashVariant.label(NAME)`, and `checkSingleJar` forbids `com/crystalgraphics/` so a copy cannot creep back in as a split package. |
| `runtime/mc/legacy/` | ✅ | **Forge 1.8–1.12.2**, a second Stonecutter tree: one branch, `forge`, a node per SRG plateau (`1.8.9`, `1.10.2`, `1.12.2`), MCP names through Unimined, each shipping in `com.crystalgui.mc.v<digits>`. The host is 1.7.10's ported: `CrystalGUILegacy` (both sides) and `CrystalGUILegacyClient` stand in for the `@Mod` and its proxies, and `Game`/`client.ClientGame` spell every member Minecraft renamed between plateaus, so nothing else carries a directive. **No mixin**: Forge 1.8 added the cancellable screen input events 1.7.10 lacked. It claims every Forge version from 1.8.8 to 1.12.2 — not 1.8, which no MixinBooter boots — and prodSmoke has drawn on all eleven (`188forge` … `1122forge` in `local.properties`). The language host is `src/lang`'s `com.crystalgui.mc.legacy.lang`: MCP stable names per Minecraft version, fetched through `forge/mcp-stable/{version}`. The player needs MixinBooter. **There is no legacy dev `serverSmoke`**: `runtime/mc/legacy/server_smoke.py` boots the shipped jars on a real installed Forge server per version instead, which is what caught the production-only defects dev servers hide (client classes absent, a `jar:` code source, FML trapping every exit). `CrystalGraphics/singlejar-logic/README.md` § *the legacy tree*. |
| `runtime/mc/launchwrapper/` | ✅ | **What the two LaunchWrapper hosts share** — 1.7.10 and Forge 1.8–1.12.2 — where the code would otherwise be one copy per host: `LaunchWrapperBytes` (live and pre-transform class bytes, and Notch → SRG names, through LaunchWrapper's own renamer) and `LaunchWrapperLanguageProbe`. The language half only, so far: on both hosts' `lang` compile path and merged once into the language jar. Java 8 out, compiled by a 21 javac so it can read `:language`. |
| `runtime/mc/modern-shared/` | ✅ | **`launchwrapper`'s modern counterpart**: what every ModLauncher and Knot node shares and names no Minecraft and no per-node class — `MinecraftBytes`, `MojangMappings`, `LanguageProbeModern`, `LanguageLifecycle` — merged once into the language jar instead of once per node. Package `com.crystalgui.mc.shared.modern`, clear of `com.crystalgui.mc.modern`, which the node relocation matches as a string prefix. A class whose only per-node reference is a service takes it as an argument (`LanguageLifecycle.bootstrapClient(ScriptServiceModern::forThisClient)`). Java 8 out. |
| `runtime/mc/forge-bootstrap/` | ✅ | **The `@Mod` classes for every Forge, 1.8 onward**: `ForgeBootstrap` (`main`, into the host jar) and `LanguageForgeBootstrap` (`lang`, into the language jar). Modern Forge and legacy FML scan for the same annotation, so one class per mod serves both eras; it names no loader type, compiles against CrystalGraphics' `forge-stubs`, and hands off to `ForgeStart`. Java 8, merged once, never relocated — `singlejar-logic/README.md` has the pattern. |

> **The two `java`/`js` axes differ on purpose** (`language/`, above). In `.java` the loader question
> is mechanical — a class that imports `org.eclipse.jdt` is child-side, and that is thirty-six of its
> fifty — so directories spend themselves on the axis that is *not* readable off the file. In `.js`
> it is the loader question that cannot be read: six classes import neither Rhino nor anything of
> ours and are child-side only because every one of their callers is.
>
> *Below the table on purpose: a blockquote inside a row ends the table, and this one silently
> collapsed every module from `taffy/` down into one paragraph until 2026-09-12.*

`core/build.gradle.kts` runs an **import guard** as a `doLast` on `compileJava`: any source line
importing `net.minecraft.*`, `cpw.mods.fml.*`, `net.minecraftforge.*`, or `org.lwjgl.*` fails the
build. There are currently **no exemptions** — the guard is clean.

## Package map

```
com.crystalgui.core            CrystalGuiCore — the global LOGGER, and nothing else. The platform
                               registry it used to hold now lives in CrystalGraphics; see below.
  .data                        CacheCell / IntCacheCell / LongCacheCell (dirty-flag memoization),
                               ReadOnlyVec2f (immutable view over a mutable JOML Vector2f), Transform2D
  .dispose                     Disposable, Disposable.Gl, Disposer — the ownership tree. NOT a
                               replacement for CgGraphicsLifecycle's registry sweep; it exists to
                               release on CLOSE rather than on exit, and to reach createOwned GL
                               objects no registry can see. docs/CGUI_WORKBENCH_SERVICES.md
  .provider                    Providers — every provider of a service: ServiceLoader, plus the
                               Copies slot a host fills where its classloader cannot list a resource
                               across mod files (ModLauncher 5). Every registry discovers through it
  .property                    Property<T> — a value held here or DERIVED from a model (read and
                               write through, polled or announcedBy, the history its edits go into,
                               map), what every config control binds to; ObservableList<T>
  .signal                      Signal.Action/Value/Pair, SignalBase, Connection, ConnectionGroup
  .storage                     WHERE ANYTHING PRIVATE GOES. ConfigStorage (the key/value SPI),
                               LocalConfigStorage (one real directory, atomic writes, and `scoped`
                               answering a real SUBDIRECTORY), ScopedConfigStorage (the default
                               `scoped` — a key prefix), InMemoryConfigStorage, and StorageLayout —
                               the `crystalgui/` tree stated ONCE: workspace-config/ (durable —
                               apps/ an application's, projects/<key>/ a workspace's, extensions/
                               what a user made with an extension), cache/ (deletable at any
                               moment), projects/ (the user's own files). ConfigRecord<T> is one
                               typed JSON record in a store: codec + default, update, onChanged,
                               an unreadable file left alone.
                               A host answers WHERE its installation is and nothing else; nothing
                               outside StorageLayout may spell those segments.
                               plan/crystalgui/fs-rewrite/fs-storage-layout.md
  .cache                       FETCHING A FILE AND KEEPING IT, for any module. Downloads (a described
                               transfer: named, reported, cancellable, resumed, retried, verified),
                               DownloadLocations (download/locations.json -- every address in one
                               file, repairable from master after release), CacheFiles (verified,
                               atomic installs), TarArchive. The language stack was its first user
  .command                     Command (a named invocable action), CommandContext, CommandRegistry —
                               what a key binding, a menu item and the palette all point at. Plus the
                               MENU MODEL: MenuId (a named place a menu is drawn, interned, with nested
                               submenus), MenuSection (a group + its rows — what a separator is drawn
                               from), MenuEntry (Item/Submenu, sealed; an Item carries enabled/checkable/
                               checked so the RENDERER decides), MenuContributor (rows computed at open
                               time — the Window menu's editor list). CommandRegistry.sections() is the
                               one query every menu renderer reads; menu() is its deprecated flat view.
                               ActionIcons is the VOCABULARY of marks a row may draw -- a constant per
                               shipped icon, since an id naming no file draws an empty column and
                               reports nothing -- and carriesItsOwnPalette() is the line between a
                               chrome mark that follows the cascade and artwork that must not be tinted
  .cursor                      CursorService (a CLASS, one static method -- resolves a keyword to a
                               picture and hands it to CrystalGraphics' CgCursorService, whose LWJGL
                               adapters know no keywords), Cursor (the keyword set — CSS UI 4's, plus three the web never named:
                               slide-arrow, skew, pivot), CursorBitmaps (procedural 32x32
                               ARGB art, AND `artFor` — the ONE keyword->picture table every platform
                               reads), CursorArt (one picture: name, drawing, hotspot; shared across the
                               keywords that want it, so an adapter caches one native per PICTURE),
                               CursorService (+ its CgService slot). Was CrystalGraphics' `platform.input`
                               / `platform.service` until CgService gave CrystalGUI a way to own a
                               service; it names no GL and no loader
  .trace                       WHAT A FRAME COST, and who asked. UiTrace (CrystalGUI's three channels —
                               `crystalgui.frame` for phases and counters, `.flow` for chains that
                               outlive a frame, `.blame` for the stack walk — plus `useCacheRoot`, the
                               run directory, `writeReport()` and `writeMeta()`, and the frame boundary a
                               UIDocument brackets its frame with -- `frameBegin`/`frameEnd`, `blame`),
                               SlowFrameLog (the `[frame]` line, read back off the ring) and
                               FrameStats (the readout's model — a VIEW over the ring, with no storage
                               of its own). The capture engine itself is CrystalGraphics'
                               `com.crystalgraphics.trace`, in its PLATFORM module rather than core, so
                               a dedicated server and `headlessTest` can both reach it. Call sites
                               write to it DIRECTLY -- `CgTrace.zone(UiTrace.FRAME, "...")`,
                               `CgTrace.add` for a per-frame count, `CgTrace.stamp` + `zoneDone` for
                               a split begin/end -- and CrystalGraphics' own on `CgChannels.TEXT`/
                               `GL`/`WORLD`/`ASYNC`/`MISC`. There is no facade left: `FrameProfile` and
                               `CgProfiler` were deleted in T9, and the harness reads the ring through
                               `harness.trace.TraceReport`/`TraceDump`. TraceFiles is EXPORTED TRACES:
                               the Frame Profiler's Export writes Chrome JSON (ui.perfetto.dev opens it)
                               to `cache/trace-exports/`, BESIDE the runs because the run directory is
                               pruned, and Compare's "Load trace as B" reads one back. A host gives
                               the directory through `Desktop.useStorage`, which calls
                               `UiTrace.useCacheRoot` -- until 2026-09-27 nothing did, so no run wrote
                               a directory at all. The GPU is `CgGpuTrace` (T7): timer queries
                               landing in the frame they were issued in, one to three frames late, as
                               `gpuNanos` and a `gpu:<name>` counter per zone -- absent, never zero.
                               Frame images are `CgFrameImages` (T8), on the `images` channel: the
                               paint context's finished frame every 30 frames, read back through
                               CrystalGraphics' `CgPixelReadback` without a stall.
                               **A run writes `cache/trace/latest/`** — `trace.log` (off the frame
                               thread, on a bounded queue that drops and COUNTS), and on exit
                               `report.txt` (the tiered text report, with each frame's unzoned time and
                               GAPs) and `meta.json` (which channels were and were not recording).
                               Profiling end to end: docs/CGUI_PROFILING.md. NOTHING goes to the game console unless
                               `-Dcrystalgui.frameprofile=true`, which adds it rather than replacing it.
                               **The ring is sized at run time**: `CgTrace.configure(first, newest,
                               zonesPerFrame)` keeps the FIRST frames of a recording for good (their own
                               arenas and counters, never overwritten) and the NEWEST after them; 0
                               newest stops once the first are full. `frames()` skips the gap between.
                               `stopAfterHitch(ns, framesAfter)` stops recording after a slow frame.
                               A thread's zone arena starts small and doubles up to the ceiling, so a
                               worker never holds the frame thread's arena. The Frame Profiler's
                               settings (`apps/crystalgui.frameprofiler/settings.json`) drive all three.
                               A viewer refreshing on a clock reads `frameSnapshot()` (no zones) plus
                               `zonesBetween` for its selection: a full `snapshot()` copies every zone.
                               plan/platform-trace-engine.md
  .undo                        Edit (one undoable change), CompositeEdit, UndoStack — one history per
                               DOCUMENT, never per window
  .window                      WindowState, WindowPolicy, DesktopPresentation — three types BOTH engines
                               name, so a package both may name. The D27 rule that gave 6.3
                               `core.collection` and 6.4 `graph.port`; a pure enum, a policy record and a
                               presentation enum, none of them naming an engine type at all. ScreenOverlay
                               was the fourth candidate and does NOT qualify: it holds a document and reads
                               its focus owner, so it is a facade OVER the engine rather than an SPI a host
                               implements, and it lives in `desktop.host`. plan/engine-port.md 6.6

com.crystalgui.widget.texteditor  THE EDITOR ON THE NEW ENGINE (M6.5) — TextEditor and
                               EditorCommands (its named actions). TWO files at the root; everything
                               else is in a sub-package below.
  .part                    VS Code's VIEW-PART decomposition: EditorViewPart (the base + Monaco's
                             shouldRender protocol), DecorationPool (the pool/hide idiom), and one
                             part each -- LineNumbersPart, ViewCursorsPart, SelectionsPart,
                             CurrentLinePart, IndentGuidesPart, WhitespacePart, RulersPart,
                             GutterEdgePart, FoldingDecorationsPart, ZoomIndicatorPart,
                             ErrorStripePart, SquigglesPart, QuickFixBulbPart, InspectionWidgetPart,
                             DiffBandsPart, DiffChevronPart.
                             `render(int, int)` is PUBLIC here, which is the boundary's whole cost and
                             an honest statement of what it always was: the contract between an editor
                             and the things that draw it
  .fold                    EditorFolding — folding is VIEW STATE by the engine's own rule, and this
                           is the package that says so
  .diff                    DiffDecorations — the diff model the two diff view parts read
  .suggest                 CompletionPopup, CompletionSession, CompletionRanking, EditorSuggest
  .doc                     DocumentationPopup, HoverDocumentation
  .find                    SearchReplaceBar, EditorFind
  .lang                    EditorLanguageFeatures, EditorDiagnostics, DiagnosticActions

com.crystalgui.desktop         CRYSTALOS ON THE NEW ENGINE (M6.6) — Desktop (the compositor, found with
                               `Desktop.of(document)` and never built by a caller: the engine may not
                               name a compositor, so the compositor names the document), DesktopCommands,
                               DesktopSession, DesktopKinds (the layer's NodeKinds service). FOUR classes
                               at the root, which is the whole of what a layer root is for.
  .window                      WindowFrame and everything a window IS: WindowChrome, WindowCommands,
                               WindowRegistry, WindowIcon, WindowMove, WindowKeyboardMove, SnapZones,
                               SystemMenu
  .motion                      WindowAnimator over WindowAnimation (transform + opacity, what a
                               compositor does) and WindowGeometryAnimation (layout, because a size
                               change REFLOWS), behind WindowMotion. Writes through Box's compositor
                               overrides, never the cascade; under an async driver a WindowAnimation is
                               a ui.service.CompositorAnimation instead, played on the render thread
                               over the window's retained picture, recorded at rest
  .taskbar                     Taskbar (the registry RENDERED), TaskbarEntryMotion, TaskbarPreviews,
                               TaskbarDesigner, WindowPreview, WindowThumbnail
  .switcher                    WindowSwitcher — Mod+Tab, MRU order, live thumbnails
  .host                        ScreenOverlay, HostServices, DesktopHost, DesktopWindowMount — what a
                               LOADER talks to. Four questions (where private files go, how big a pixel
                               is, is there a connection, what language the player reads) and it gets a
                               desktop, a workspace that
                               follows the wire, and somewhere for a server's windows to land
  .launcher                    WHAT CAN RUN: LauncherButton (the taskbar's start button) and Launcher
                               (the ApplicationRegistry, rendered -- searched by name, id and the
                               keywords() slot that was deliberately withheld until this existed to
                               read it). It names no concrete application, which is the whole reason
                               a launcher can live in `desktop` at all: a launcher that named
                               CrystalEditor would have to sit above `app` and could never be
                               reached from the taskbar
  .app                         WHAT AN APPLICATION IS, and it names no workbench: ApplicationKinds (the
                               ServiceLoader SPI a layer declares its products through -- nothing
                               installs one, the way nothing registers a widget tag), ApplicationKind (the
                               manifest — freedesktop's .desktop entry, macOS's Info.plist: id, name,
                               icon, keywords, the files it opens, singleInstance, a launch factory,
                               and `autostart` -- run once per desktop the moment it has storage,
                               before the first frame, whether or not the application ever opens:
                               how the profiler records from launch), Application (one running instance: kind, mainWindow, open,
                               activate, dispose — where dispose is QUITTING and closing the window is
                               not), ApplicationRegistry (per Desktop: install/installed/launch/running/
                               handlerFor — a launcher, "open with" and taskbar grouping all answerable
                               with nothing running; runs the ApplicationKinds services once per
                               DESKTOP, since discovery is per process and installing is per shell),
                               LaunchContext (the Exec line's arguments)

com.crystalgraphics.platform   NOT CrystalGUI's code — CrystalGraphics' platform SPI, which CrystalGUI
                               consumes. Listed here because the engine's input, sound and clipboard
                               seams all live in it — the CURSOR does not; see `core.cursor` above.
  (root)                       CgPlatform (the registry, both halves), CgPlatformService (the CLOSED
                               bundle a loader registers), CgService (the OPEN half — a slot a consumer
                               declares, a loader fills and anyone reads, each with its own absent-value.
                               CgCursorService.SERVICE and CgNetworkChannel.SERVICE are ours)
  .input                       CgSystemInput (raw Mouse/Keyboard event sink + event types),
                               CgKeyCodes (LWJGL2-shaped, no LWJGL import), CgMouseCodes,
                               CgModifiers (bitmask)
  .service                     CgInputService (codes, modifier/key/button state, AND the clipboard),
                               CgSoundService — plus CrystalGraphics' own six

com.crystalgui.lifecycle       CgUiLifecycle — the ONE CgLifecycleListener CrystalGUI registers with
                               CrystalGraphics; drives paint-context teardown + cache invalidation

com.crystalgui.render          CgUiPaintContext (one per document: records), UiGpu (one per process: executes), UiFrame
                               (a sealed frame), UiStages (SCREEN and HUD: where CrystalGUI draws in a game's frame,
                               render stages a mod draws at too), Surface (a moved box's kept texture), CgUiRenderer,
                               ScissorStack,
                               SvgRasterCache — icon fills rasterised once by additive accumulation into an
                               RGBA16F atlas and drawn as a tinted quad; sits beside the paint context and
                               reaches it through package-private members, as the backdrop does.
                               CgUiBackdrop — the backdrop primitive under backdrop-filter: capture the region
                               behind an element, blur it (separable Gaussian at 1/4 res), hand back the
                               sharp and blurred textures with UVs. Sits BESIDE the paint context and
                               reaches it through package-private members, as TextEditor's view parts do
  .text                        FontFamilyCache — (font stack, px) -> CgFontFamily
  .texture                     CgUiDrawable (SPI), CgUiRect (a Fill + radii + border: the one
                               drawable a background resolves to), CgUiSprite (a 9-slice FILL,
                               not a drawable — see toRect()),
                               CgUiCrossFade, CgUiLayers (a comma-separated STACK, first on
                               top -- every drawable property takes one), CgUiLayerBox (the
                               rect ONE layer paints into, not a stack), CgUiRepeat,
                               ArgbMath, CgUiSvg,
                               CgUiBackdropFilter (liquid glass — blur, luminosity blend, refraction, specular, noise, over a live
                               backdrop), CornerRadiusAware (the seam that stops a self-clipping drawable
                               being wrapped in a CgUiRect it cannot survive),
                               CgUiTransformDrawable (stub)
    .svg                       A full SVG renderer, parse to cached draw ops: SvgScanner (nested tags),
                               SvgPath (the d grammar), SvgTransform, SvgColor, SvgStyle (inheritance),
                               SvgGradient, SvgTriangulator (scanline fills, holes cut), SvgDocument
    .asset                     CgUiSpriteRegistry — lazy "ns:name" -> sprite from ui/sprites/*.json;
                               FileIconTheme — VS Code's file-icon-theme model, ui/icons/*.json
    .geometry                  Position, Size

com.crystalgui.style           ElementStyle, StyleGroup, GeneralGroup, LayoutGroup, StyleOrigin,
                               Styleable (what the cascade MATCHES) and StyleScope (what a sheet
                               can be SCOPED to -- a strictly larger set, because a scope root is
                               only walked to; a ShadowRoot is the difference),
                               TaffyBridge, PseudoClasses, StyleEngine, CssParsingUtil, CssAngle
  .sheet                       StyleSheet (+DEFAULT), StyleRule, DeclarationParser (var(--x)),
                               StyleSheetRegistry
  .selector                    Selector, CompoundSelector, SelectorType
  .transition                  TransitionEngine, TransitionSpec, ActiveTransition, TransitionValue
  .property                    StyleProperty<T>, StylePropertyRegistry, StyleSlot, StyleValue,
                               IValueInterpolator
    .general.{bools,enums,floats,ints,strings}   scalar StyleValue/StyleProperty flavors
    .layout                    LayoutProperties, BoxEdgeShorthands
    .layout.{dimension,grid,length}              Taffy-shaped value types (LPA*, LPSize, Grid*)
    .visual                    Overflow, OverflowClip, ScrollBehavior, BoxOrigin, DrawableAlign,
                               DrawableFit, OutlineShorthand, OutlineOffsetShorthand (per-edge,
                               unlike CSS)
    .visual.border             BorderRadiusProperties, BorderRadiusShorthand, LengthPercent(+Property/Value)
    .visual.color              ColorProperty, ColorValue
    .visual.text               FontFamilyValue
    .visual.texture            TextureProperty, TextureValue
    .visual.transform          Transform (the ORDERED LIST of ops -- not translate/scale/rotate
                               fields, because CSS composes left-to-right as matrix multiplication;
                               was `ui.Transform`, and the prefix went with the move: `style` is
                               written against `Styleable` so a cascade bug is fixed once, and a UI
                               prefix in it asserts an engine dependency the file does not have),
                               TransformProperty, TransformValue, TransformOriginShorthand

com.crystalgui.ui.contract     WHAT A KIND OF WIDGET IS — one declaration, four readers.
                               WidgetContract (name + ordered State slots + Event slots),
                               State<W,V> (a wire key, a getter/setter pair, an omitted-when value and
                               an optional sanitizer — DECLARATION ORDER IS APPLY ORDER, which several
                               widgets depend on), Event<W,P> (a kind, a payload codec, and HOW A
                               CLIENT LISTENS — which is what deleted the instanceof switch),
                               StateType/StateTypes, RatePolicy (immediate/typing/dragging; a widget
                               knows its own tempo and a handler cannot), WidgetContracts (the
                               registry, and the local-only list with reasons). NOTE there is no kind
                               vocabulary class: a kind is a string an Event declares, unique only
                               within its own widget's contract, so a third party mints one without
                               editing anything of ours. plan/engine-rewrite.md M1

com.crystalgui.ui              A NAMESPACE, with nothing at its root -- a root with no shared subject
                               is where things land when nobody decides.
  .dom                         The node tree. -> Stack 1
  .box                         The box tree. -> Stack 3
  .service                     Input, Focus, Animation, Lifecycle, Dismiss. -> Stack 4
  .contract                    What a KIND of widget is: one declaration, four readers. See its own
                               entry above.
  .event                       UIEvent, PropagationPhase, and the concrete Close/DOM/Drag/Focus/
                               Keyboard/Mouse types -- SHARED, dispatched by `service.Input`.
  .input                       FocusPolicy, ButtonState, and the keymap (`.keymap`).
  .text                        TextRange, HighlightRegistry -- the CSS Custom Highlight API: ranges
                               in Java, styling in CSS through ::highlight(name).
  .data                        UiDataKeys -- the UI layer's DataKey vocabulary. Names only `core`,
                               which is why the SPI it points at (ClipboardActions) lives in
                               `core.data` beside DataProvider while the KEYS stay here.

com.crystalgui.widget          THE WIDGETS, layered so a build fails when a layer reaches upward
                               (LayeringTest): .control/.display/.text/.scroll < .overlay/.layout/
                               .dnd < .collection < .composite < .config < .canvas < .graph <
                               .texteditor. `.display` is a bottom tier not by assertion but because
                               between them ProgressBar and SymbolIcon import `ui` and `style` and
                               NOT ONE widget. `.scroll` is a SIBLING of `.layout`, never a child:
                               nesting it there cannot be expressed at all, since a prefix rule makes
                               the package fail against its own parent. -> Widgets

com.crystalgui.desktop         CRYSTALOS. Desktop (found with Desktop.of(document), never built by a
                               caller: the engine may not name a compositor, so the compositor names
                               the document), .window, .motion, .taskbar, .switcher, .host.
com.crystalgui.workbench       The shell, and THE ROOT IS THE HUB ONLY. Everything whose imports
                               point at ONE sub-package now lives in it; what is left at the top is
                               Workbench plus what genuinely coordinates several of them, which is the
                               honest reading of a hub's own package.
                               At the root: Workbench (the engine, implementing WorkbenchContext — the
                               surface an extension is written against, which stays here because it is
                               the engine's own contract), WorkbenchSession (the arrangement record —
                               the engine owns the bytes), WorkbenchSettings (the settings the whole
                               engine resolves), WorkbenchKinds, WorkbenchMenus, and the three that
                               COORDINATE rather than serve — DocumentTabs (dock+editor+explorer+
                               decoration), SaveActions (dock+diff+status), NetworkedPanels.
  .app                         WorkbenchApplication — the runtime EVERY workbench-shaped product
                               shares: the workbench, its window, preferences, session and initial
                               focus, built from a manifest's list of extension ids — and
                               WorkbenchApplicationCommands (Save File, Save/Restore Layout: the
                               ENGINE's, resolved from the data context so two applications on one
                               desktop each save their own)
  .extension                   THE SEAM AND EVERY PANEL THAT SHIPS ON IT: WorkbenchExtension,
                               WorkbenchExtensions (ServiceLoader — a jar on the classpath offers its
                               features), SessionSlice (an extension's corner of the session record),
                               and the engine's own five — ProjectExtension, ProblemsExtension,
                               NotificationsExtension, PresenceExtension, InspectorExtension.
                               `new Workbench(workspace, List.of())` has NO tool windows, which is
                               asserted: the built-ins are the only real test of the seam, and a
                               first-party path more capable than the public one is how an extension
                               API rots
  .chrome .dock .explorer .region .stripe .toolwindow .view .decoration .diff .search
                               ...and each owns the binding that serves it: ProblemsBinding and
                               PresenceBinding are `.chrome.status` (both produce a STATUS ENTRY, not
                               a panel, whatever their names suggest), ExplorerBinding and
                               ProjectSourcesIndex are `.explorer`, WorkbenchOpener is `.dock`
  .editor                      EditorService — ONE lane for opening anything — TextEditorView (a
                               TextEditor as a DocumentEditor) and TextFileKind
com.crystalgui.app             The MANIFESTS, and what each product declares about itself:
                               .crystaleditor (CrystalEditor — an ApplicationKind and three choices,
                               and no longer an element at all), .shadergraph, .machine.

com.crystalgui.text            Rope, TextBuffer, TextSummary, Change/ChangeSet, Selection,
                               SelectionModel, TextPoint, TextRange, WordClassifier, WordOperations,
                               LineEnding — the document model, all headless. Plus the two utilities
                               BOTH engines need and neither may own: SimilarNames (how close is close
                               enough for a "did you mean") and DerivedNames (a name for something the
                               author has not named). Each was in an engine until the second engine
                               wanted it — a child-side class may not be imported across, so a shared
                               utility is MOVED here rather than referenced in place
  .cursor                      CursorColumns, MoveOperations, TypeOperations, LineOperations,
                               MouseSelection, ColumnSelection — VS Code's boundaries, but NOT
                               file-for-file: MouseSelection and LineOperations come from
                               browser/controller/ and contrib/linesOperations/. See "Port the module
                               boundaries too" for the full mapping and the ONE unimplemented gap left
                               (atomic tab moves for the ARROW keys; Backspace already steps by column)
  .syntax                      Language, LanguageRegistry, SyntaxToken, SyntaxTokenizer (SPI),
                               KeywordTokenizer — the ENGINELESS tier, and what a dedicated server has —
                               plus LanguageKinds, the ServiceLoader seam a jar declares its languages
                               through. `LanguageRegistry.bootstrap()` runs it on the FIRST READ, so the
                               grammars and engines are in front of the built-in lexers before anything
                               is classified, and no host calls anything; a host that calls it anyway is
                               WARMING it (443ms, measured — see the invariant row)
  .lang                        The semantic layer's contracts, INTERFACES ONLY: LanguageServices (the
                               per-DOCUMENT facade), SemanticTokenProvider, Resolver, CompletionProvider
                               + CompletionItem/CompletionList, SymbolInfo/SymbolKind/SymbolModifier,
                               TypeRef, DeclarationSite, Versioned, and TypeSearch + TypeSearchRegistry
                               ("which types are on the classpath" — what Go to File asks, inverted for
                               the same reason as the rest: the INDEX lives in language/ and core/ may
                               never name it). Every engine lives in language/; this package is the whole
                               footprint in core/, and its absence at runtime is the only feature flag.
                               docs/CGUI_WORKBENCH_SERVICES.md
  .diagnostic                  Diagnostic, DiagnosticSet, DiagnosticSeverity, DiagnosticTag, Markers,
                               RelatedInformation — LSP-shaped, per-owner. NOT duplicated in .lang
  .wrap                        LineProjection, ProjectedLines, LineBreaksComputer (SPI),
                               MonospaceLineBreaks, ShapedLineBreaks, BreakOpportunities, WrapIndent —
                               soft wrap, and the model/view coordinate seam the whole editor rests on
  .view                        IndentLevels, WhitespaceMarkers, RenderWhitespace
  .fold                        FoldingRegions (+Region), FoldingModel, FoldingRangeProvider (SPI),
                               IndentRangeProvider — folding. INDENT-based by default, which is Monaco's
                               default too and deliberately not brackets; see the class javadoc for why

com.crystalgui.document        WHAT AN OPEN DOCUMENT IS, headless and below `widget`. Document (the
                               identity — a rename MOVES it, `onDidChangeResource` is the one event a
                               store subscribes to), DocumentModel (the SPI: encode/adopt/version/
                               history/onChanged, and `version() != savedVersion` IS dirtiness),
                               AbstractDocumentModel (`apply(Edit)` as the one door), TextDocumentModel
                               (a TextBuffer plus the language, the tokenizer and the services — which
                               are the MODEL's, so two split panes share one parse tree),
                               BytesDocumentModel, DocumentKind + DocumentKinds (one declaration: model,
                               editor, icon, status; at most one `.fallback()`), DocumentEditor (the ONE
                               type here that names an element, and it names UIElement), Documents +
                               DocumentReference (open by Resource, disposed by the LAST holder — never
                               by a tab closing, which is the "Parser is closed" defect inverted),
                               DocumentState, EditorInput, RecentFiles.
                               Plus the CREATION side, a separate question from opening:
                               NewDocumentKind (one row of New -- suffix, where it is offered, its
                               template, and VARIANTS, declaring which is what gets a contributor
                               IntelliJ's name-and-kind prompt with no widget of its own),
                               NewDocumentContext (where a New would land: the directory and the
                               source root holding it, asked per CLICK rather than at registration)
                               and NewDocumentKinds (the registry, per workbench, reached through
                               WorkbenchContext.newDocuments()). Most openable things cannot be
                               conjured from nothing and some creatable things are not documents, so
                               the two registries do not merge

com.crystalgui.fs              FOUR classes, and each is vocabulary every tier below names: Resource
                               (a tab's input, whether or not it is a file — the project scheme keeps
                               CgPath's exact text, so every saved document and session keeps parsing),
                               CgPath, CgFileError and CgFileSystemException. They import nothing from
                               `fs` at all, which is what makes the root a root rather than a drawer —
                               and is why `LayeringTest` needs no entry for it. Everything else moved
                               into a tier below; twenty top-level files became four.
  .project                     ProjectRegistry, ProjectInfo, WorkspaceProject, SourceRoots, Excludes,
                               ProjectProvider. The bottom tier.
  .provider                    A FILESYSTEM AND NOTHING ABOUT A WORKSPACE: CgFileSystem (the SPI),
                               LocalFileSystem, InMemoryFileSystem (a complete one, on a monotonic
                               clock, which is what makes an etag reproducible), CgFileEntry (+Type),
                               CgFileCapability, CgFileEvent (+Source) + NioFileEventSource. It names
                               `.project` and NOT the reverse — a project is a named root and a
                               filesystem is what resolves one to a directory
  .protocol                    THE WIRE, shared by both halves and naming neither: FsMethods (the method
                               names), FsMessages (every payload as a record with a codec, so a field
                               written on one side is provably the field read on the other), FsError (a
                               code and fields — a conflict carries the etag the file actually holds),
                               FsHello (the greeting: case rule, reserved names, size tiers)
  .server                      WorkspaceService (the server's own filesystem: authorise, etag, cap,
                               trash), WorkspacePermission + WorkspaceActor + WorkspaceOperation,
                               WorkspaceTrash, WorkspacePresence, WorkspaceConflictException,
                               ServerWorkspace (the service with the actor already decided — what a
                               panel is handed), WorkspaceBinding (one CONNECTION's end — decode, ask the service, encode;
                               owns this actor's audit, its idempotency table and its entry in the hub),
                               WatchHub (ONE subscription table for the whole server: a path is stat-ed
                               once per tick however many peers watch it, a save's several events
                               coalesce into one change, and a delete plus a create carrying one etag
                               pair into a RENAME; `update` is a host's ONE call a tick, and it owns the
                               reconcile cadence, stat-ing on its own JobScheduler off the host's thread),
                               WorkspaceAudit, RecentOperations
  .client                      Workspace (the entry point; facades by noun — files(), presence(),
                               capabilities(), health() — and the ONE door a provider is reached
                               through, which is where UiBudget times it), FileOperations (every answer
                               a Reply, serialised per resource, undoable), FsCall (coalescing, cancel,
                               one failure-parse site), WorkspaceDocuments (where the headless document
                               model meets the wire: open, save, and what a change on the server means
                               depends on whether the document is dirty), ContentProvider +
                               ContentProviders (where a NON-project scheme's content comes from — a
                               decompiler, a generator; contributed statically, drained per workspace),
                               Backup (hot exit), LocalHistory (per-save, and the merge base), Health

com.crystalgui.serialization   JsonOps. The codecs themselves (CgCodec, CgDynamicOps, CgPlainOps,
                               CgStateMap, CgContentHash) are CrystalGraphics' com.crystalgraphics.serialization
  .style                       StyleValueCodecs, InlineStyleCodec

com.crystalgui.net             ServerUiSession, ClientUiSession, ClientUiSessions, UiWindowMux, SheetRef,
                               over CrystalGraphics' com.crystalgraphics.net (transports, the envelope,
                               the router, connections, the wire). Ids live in
                               ui.dom.UIElementTreeSource, not here. -> Server layer
  .mirror                      ServerTreeMirror<N,T>, ClientTreeMirror<N,T>, NodeMirror<N,T> (the
                               per-tree seam -- BOTH halves on one interface, so an encode with no
                               matching apply is a compile error), UIElementMirror over today's tree,
                               TreeOps. It names no widget, no session and no transport: a second
                               engine supplies a TreeSource and a NodeMirror and nothing else.
  .protocol                    UiMethods, the ui/* vocabulary.
  .window                      A WINDOW'S LIFETIME, and the layer a mod actually uses: Networked<M>
                               (one class per UI, widgets as FIELDS), UiType, ServerScope/ClientScope,
                               ServerWindow<P>, ServerWindows/ClientWindows, WindowMount, Presentation,
                               RowSource/RemoteRows.  -> docs/CGUI_BUILDING_UIS.md
  .projection                  Declare a read/write pair once; the engine compares and writes only on
                               change, and skips the set while no viewer is watching. `each` for KEYED
                               lists; AutoProjection maps a panel field name to a model accessor and
                               REPORTS what it could not wire.

                               NOTE the three senses of "window": UIDocument is the ENGINE for one
                               surface, WindowFrame is the CHROME on a desktop, ServerWindow is the
                               NETWORKED UNIT. The protocol has meant the third since windowId existed.

com.crystalgui.probe           WHAT A RUNNING GAME IS ASKED TO PROVE ABOUT ITSELF, and the only code in
                               core/ whose consumer is a build task. ServerSmoke (a dedicated server
                               came up and loaded no client-only class), ConnectionProbe (the whole
                               stack over a REAL connection -- topology is a parameter, and a check
                               that means nothing here is SKIPPED with its reason rather than silently
                               passed), DesktopProbe (a scripted run through the compositor), AutoTest
                               (open, photograph, quit -- the only one that also runs on a SHIPPED jar,
                               which is what prodSmoke drives; StageProbe is its renderer on UiStages),
                               ProbeReport (verdict on line 1; an
                               ABSENT FILE IS A FAILURE). Each takes a `Host` a loader implements and
                               decides everything else itself; they SHIP deliberately, one
                               Boolean.getBoolean each when off. See the package note for why a test
                               source set cannot hold them
```

**Naming corrections vs. older notes:** `render/` is top-level (`com.crystalgui.render`), *not* nested
under `core/`. There is **no `core/input/` or `core/sound/` package any more** — the raw platform I/O
layer moved wholesale to `com.crystalgraphics.platform`, and `com.crystalgui.core` is now the logger plus
three small utility packages. Dispatch and focus were always in `ui/input/` and stayed there. The
three-phase event types are in `ui/event/` — there is no `core/event/` package.

---

## The headless classpath

**The classpath is a dedicated server's.** A server ships CrystalGraphics `core` and `platform`, so both are here:
`core` holds shared utilities the engine names in field and signature types (`com.crystalgraphics.easing`, which the
style engine's transitions and the `Animation` service use). What a server lacks is a GL context and fonts, and no
test here has either, so code that reaches the GPU outside a paint-method body fails here rather than in production.
`HeadlessClasspathSanityTest` pins what must be present. *(Until 2026-10-02 `core` was excluded, and the guard was a
`NoClassDefFoundError` on any core type; moving easing into core ended that.)*

JOML and Taffy **must stay** on the headless classpath: `UINode` and `ElementStyle` have *fields*
of those types (`Matrix4f`, `NodeId`, `TaffyStyle`), and field descriptors resolve at class load —
unlike method-body references, which don't. Someone will eventually try to strip them; don't.

> **`StyleSheet` loads headlessly now.** `StyleSheet.DEFAULT` reads `default.css` through `CgIO` at class-init,
> which made the whole class unloadable while `core` was off this classpath; `HeadlessClasspathSanityTest` now
> asserts it loads.

---

## The platform seam: a closed bundle and open slots

A loader registers exactly one `CgPlatformService` bundle (**closed** — nine methods, no defaults) and fills any
number of `CgService` **slots** (**open** — contracts the rendering framework must not name, each with its own
absent-value).

| Need | Reached via | Lives in |
|---|---|---|
| Modifier, key and button state, the clipboard, UI sounds, the cursor — **from UI code** | `PlatformPort.current()` | `ui/service/PlatformPort`: the running document's port, which a document on its own thread routes to the render thread (plan engine-threaded-ui). Calling the services below directly from a widget bypasses that |
| Key/mouse codes, modifier state, **and the clipboard** | `CgPlatform.input()` — hosts and `PlatformPort.INLINE` only | `platform/service/CgInputService` |
| UI sounds | `CgPlatform.sound()` — hosts and `PlatformPort.INLINE` only | `platform/service/CgSoundService` |
| Raw event sink (`Input` implements it) | — | `platform/input/CgSystemInput` |
| Code constants | — | `platform/input/CgKeyCodes`, `CgMouseCodes`, `CgModifiers` |
| **Presenting a cursor** | `CursorService.setCursor(...)` | **`core.cursor`, ours** — its guide, `core/src/main/java/com/crystalgui/core/cursor/CLAUDE.md`, has why the cursor is split |

> **The clipboard is on `CgInputService`, not a service of its own.** It is not conceptually input, but it
> is reached the same way and needed by exactly the code that handles keys — two methods do not earn a
> registration slot. Both are abstract, like everything in the bundle.

**No method in the BUNDLE has a default, and `CgSoundService` ships no `NOOP` constant.** A default is
an answer chosen for someone who never saw the question: a new platform compiles cleanly while silently
inheriting "no sound, no clipboard", and inheriting a no-op is indistinguishable from deciding on one.
Abstract methods make the compiler the reminder — and a platform with nothing to offer still says so, with
an empty body in its own source.

**A `CgService` slot is the deliberate opposite**, and the cursor is why the distinction exists: an
unpresented cursor is *cosmetic*, and the engine runs where there is nothing to present to — a dedicated
server, a headless test, a fixture with no window. Those must not register a stub to stay silent, so the
slot answers `CursorService.NONE` and `CgService` logs the absence once, on first read.

> **Why this stopped being CrystalGUI's own registry.** `CrystalGuiCore` used to hold four static fields
> with setters. CrystalGraphics is the parent project and is always present, so two registries meant a
> loader had to find both — and could wire up one, leaving a UI with a working GL backend and no keyboard.
> One bundle makes a platform either registered or not. `CrystalGuiCore` now holds only `LOGGER`.

---

## Docs, and when to read each

`ls docs/*.md` is the list; this says which one to open. Each is written to be read on its own, so a
row here says what a doc is **for** and nothing about what it contains — the doc's own header does that
better and does not go stale when it changes.

| Doc | For |
|---|---|
| **`CGUI_SETUP.md`** | **Setting up a mod on CrystalGUI**: one Minecraft version (the `com.crystalgui` plugin) or one jar across many (`targets {}`), against Maven or a checkout. What a consumer reads first |
| **`CGUI_BUILDING_UIS.md`** | **Using CrystalGUI rather than building it.** A client-only UI, a networked one, and how to choose. The whole `Networked` authoring surface by example, ending in a symptom→cause table for the failures that are silent |
| **`CGUI_BUILD.md`** | The build: layout, commands, what each check can see, and adding a Minecraft version |
| **`CGUI_CROSS_VERSION.md`** | Code against every Minecraft version and loader — seams, eras, directives, verification. The `cross-version` skill is its checklist |
| **`CGUI_PROFILING.md`** | Profiling CrystalGUI in one run: its channels, what a frame records, the Frame Profiler, scenes and the game, what is not yet instrumented. Read after CrystalGraphics' `docs/PROFILING.md`; the `profiling` skill is its checklist |
| **`CGUI_WORKBENCH_EXTENSIONS.md`** | The other user-facing guide: getting a panel, a file type, a command or a status entry into somebody else's workbench |
| **`CGUI_ARCHITECTURE.md`** | Where everything lives: each module, and the package map of `core/` |
| `CGUI_SHIPPED_ASSETS.md` | Everything under `assets/crystalgui/`: sheets, themes, icons, fonts, shaders |
| **`CGUI_INVARIANTS.md`** | What is invisible from any single class and expensive to rediscover, by subsystem. **Read the section for what you are touching** |
| `CGUI_STYLE_RENDER_PIPELINE.md` | The cascade and the paint path in full — origins, selectors, transitions, drawables, compositing, `background:` grammar, the visual-layer FBO pass |
| `CGUI_WIDGETS.md` | Per-widget API, `::part()` names, pseudo-classes, and the harness scene that covers each |
| `CGUI_WORKBENCH_SERVICES.md` | What a widget may *ask* rather than reach through the application for: `Disposer`, `DataContext`, `Resource`, the document layer, `Workspace`, `EditorService`. **New service API is added here in the same commit** |
| `CGUI_SERVER_AND_SERIALIZATION.md` | Codecs, descriptions, content hashing, sessions and RPC — and the headless contract underneath them |
| `CGUI_NETWORKING_PRIMER.md` | Networking from the bottom up, ELI5 first: what a frame, a session and a peer each are, how a `CgProtocolConnection` is established, and how to define a packet contract on both halves |
| `CGUI_THEMING.md` | Themes, editor colour schemes, the token vocabulary. Its token table is generated and machine-checked — regenerate it from the failing test, never by hand |
| `CGUI_COMMANDS.md` | Every command the codebase declares, by area, with its menus and keys — the sweep behind the menu-icon pass. **A snapshot, not a contract**: it is regenerated, not maintained, so trust the code where the two disagree |
| `CGUI_NEW_ENGINE.md` | Reading a commit or a comment that still names the old engine: what replaced what, and the six habits that are now wrong |
| `CGUI_MODERN_UI_RENDERING_RESEARCH.md` | The primary sources behind glass, blur, gradients and the taskbar, with their exact numbers. **Read the relevant section before touching any of them** — each was first built from memory and each was wrong in a way only the source showed |

---

## External references

- **LDLib2** — pattern prior art for widgets and the Ore theme. An **in-repo checkout** at
  `research_repos/LDLib2`, never a dependency. Stylesheets at
  `research_repos/LDLib2/src/main/resources/assets/ldlib2/lss/` (`gdp.lss`, `mc.lss`, `modern.lss`).
  Java sources under `src/main/java/com/lowdragmc/lowdraglib2/`; note `bin/` also holds compiled
  `.class` files, so search `src/` explicitly. *(Was documented as a sibling checkout at `../LDLib2`,
  which does not exist.)*
- **Taffy** — consumed as the Gradle artifact `dev.vfyjxf:taffy` (version in `gradle.properties`), but
  **extracted Java sources are checked in** at `research_repos/taffy/dev/vfyjxf/taffy/`. Read them
  directly — there is no need to decompile through the IDE, and no need to guess at layout semantics
  (containing blocks, absolute positioning, flex-wrap cross-sizing) that the engine's own behaviour
  depends on. *(Previously documented as "no source checkout"; it exists.)*
- **Monaco** — an in-repo checkout at `research_repos/monaco`, which is where every "VS Code does
  X" claim in `com.crystalgui.text.cursor` was read rather than remembered. See *Port, don't reinvent*.
- **Minecraft sources** — not extracted at the paths the MC modules would produce
  (`runtime/mc/modern/*/build/mc-src/`, `build/rfg/minecraft-src/java`), since neither MC module is in the build.
  **But an extracted 1.20.1 tree is checked in** at `research_repos/mc1201_sources/`
  (`com/`, `mcp/`, `net/`). Cite that path, not the build ones.
