# CrystalGUI — modules and packages

Where everything lives: each module, and what each package of `core/` owns.

## Module layout — what actually compiles

`settings.gradle.kts` includes the engine modules, the loader trees and `gl-debug-harness`. `taffy` and
`gl-debug-harness` are git submodules built as ordinary subprojects; CrystalGraphics is a submodule included as a
composite build, whose `dependencySubstitution` resolves `com.crystalgraphics:*` and each modern node's common to
local source (`gradle/module_integration/composite.settings.gradle.kts`).

| Module | What it is |
|---|---|
| `core/` | The engine. Java 25, with a Java 8 copy. Everything below lives here. |
| `language/` | The language stack: everything with a native or an engine behind it. Ships as its own mod, `crystalgui_language`. **`core/` never depends on it**, and it is on the loader trees' `lang` source sets only, so no host class can name it. Packages: `.grammar` (tree-sitter grammars), `.engine` (band selection, the one shared loader per band `EngineHost`, the `Analysis` answer, `AnalysedLanguageServices`), `.java` (`.ecj` adapters, `.classpath`, `.assist` completion and Quick Documentation, `.fix` the Alt+Enter catalog, `.exec` the `ScriptHost` runtime), `.js` (`.host` never names Rhino; `.rhino` is child-side, with `.resolve`/`.fix`/`.exec`), `.map` (readable↔runtime names, on ASM), `.run` (the engine-neutral Run shell: `ScriptRuntime`, `ScriptRuntimes`, `ScriptPolicy`, `.exec`, `.console`, `.view`; `RunShellIsEngineNeutralTest`). |
| `taffy/` | The layout engine, vendored: a fork of `dev.vfyjxf:taffy:1.1.4` (MIT), our changes in `taffy/MODIFICATIONS.md`. The package stays `dev.vfyjxf.taffy`, relocated in the jar. Depends on nothing: its collections are `dev.vfyjxf.taffy.collection`. |
| `gl-debug-harness/` | Submodule, Java 25. Takes CrystalGraphics and CrystalGUI's `core` as libraries; builds on its own from `local.properties`. |
| `harness-scenes/` | CrystalGUI's harness scenes (`com.crystalgui.harness`), a `HarnessExtension` its build puts on `:gl-debug-harness:runHarness`. |
| `CrystalGraphics/` | The rendering backend (composite build). Consumed, never reimplemented. |
| `runtime/mc/1710/` | The 1.7.10 node on RetroFuturaGradle, with no source: its dev run, `serverSmoke` and the thin jar. The `@Mod` is `forge-stubs`' `CrystalGUIForge`; everything starts from `core`'s lifecycle listeners. |
| `runtime/mc/modern/` | Forge 1.13.2+, NeoForge 1.20.2+, Fabric 1.14.4+: a Stonecutter tree, a node per version (`:runtime:mc:modern:<branch>:<version>`). Holds no CrystalGUI code; each loader node compiles against its version's `common` and CrystalGraphics' node and builds a thin jar. Read `runtime/mc/modern/README.md` first. |
| `runtime/mc/shared/` | Java 8, merged once, never relocated, for what every variant shares without naming Minecraft. Empty today; the variant selector is CrystalGraphics', and `checkSingleJar` forbids `com/crystalgraphics/` here. |
| `runtime/mc/legacy/` | Forge 1.8.8–1.12.2: a Stonecutter tree, a node per SRG plateau (`1.8.9`, `1.10.2`, `1.12.2`), MCP names through Unimined, no mixin. Language host: `src/lang`'s `com.crystalgui.mc.legacy.lang`. Players need MixinBooter. No dev `serverSmoke`: `runtime/mc/legacy/server_smoke.py` boots the shipped jars on real servers. |
| `runtime/mc/forge-stubs/` | The `@Mod` classes for every Forge and NeoForge: `CrystalGUIForge`, `CrystalGUINeoForge` (host jar), `CrystalGUILangForge`, `CrystalGUILangNeoForge` (language jar). Each Forge class carries both eras' `@Mod` and hands off to `ForgeStart` / `NeoForgeStart`. Java 8, merged once. Fabric needs none. |

`core/build.gradle.kts` runs an **import guard** on `compileJava`: a line importing `net.minecraft.*`, `cpw.mods.fml.*`,
`net.minecraftforge.*` or `org.lwjgl.*` fails the build, with no exemptions.

## Package map

```
com.crystalgui.core            CrystalGuiCore: the global LOGGER
  .async                       UiSequence (a document's sequence; services(); -Dcrystalgui.ui.singleSequence=true
                               makes one for all), Broadcast<T> (an event across sequences), Published<T> (state as
                               immutable generations), Contributions<T> + Owner (declared contributions, leaving with
                               their owner), JobScheduler, UiThread, UiBudget
  .data                        CacheCell / IntCacheCell / LongCacheCell, ReadOnlyVec2f, Transform2D
  .dispose                     Disposable, Disposable.Gl, Disposer: the ownership tree (CGUI_WORKBENCH_SERVICES.md)
  .provider                    Providers: every provider of a service, ServiceLoader plus the copies a host fills
                               where its loader cannot list resources across mod files. Every registry uses it
  .property                    Property<T> (held or derived from a model; what every control binds to),
                               ObservableList<T>
  .signal                      Signal.Action/Value/Pair, SignalBase, Connection, ConnectionGroup
  .storage                     CrystalGuiStorage: the crystalgui/ tree, stated once (workspace-config/ apps/,
                               projects/<key>/, extensions/; cache/; projects/). ConfigRecord<T>: one typed JSON
                               record. CrystalGuiDownloads binds CrystalGraphics' downloads to
                               download/locations.json. The stores themselves are CrystalGraphics' (CgConfigStorage)
  .cache                       the downloads guide (CLAUDE.md) only; the machinery is CrystalGraphics'
  .command                     Command, CommandContext, Commands (a Contributions list: lookup, menu rows and keymap
                               per generation); the menu model: MenuId, MenuSection, MenuEntry (Item/Submenu),
                               MenuContributor. ActionIcons: the marks a row may draw, and carriesItsOwnPalette()
  .cursor                      CursorService (keyword → picture → CrystalGraphics' CgCursorService), Cursor (CSS UI 4's
                               keywords plus slide-arrow, skew, pivot), CursorBitmaps (the keyword→picture table),
                               CursorArt
  .trace                       UiTrace (channels crystalgui.frame, .flow, .blame; frameBegin/frameEnd, blame),
                               SlowFrameLog, FrameStats (a view over the ring), TraceFiles (exported Chrome JSON in
                               cache/trace-exports/), UiHints. The engine is CrystalGraphics' com.crystalgraphics.trace;
                               call sites use CgTrace directly. CGUI_PROFILING.md
  .undo                        Edit, CompositeEdit, UndoStack: one history per document, never per window
  .window                      WindowState, WindowPolicy: types both engines may name

com.crystalgui.widget.texteditor  TextEditor and EditorCommands at the root; the rest below
  .part                        VS Code's view parts: EditorViewPart (base, Monaco's shouldRender), DecorationPool,
                               LineNumbersPart, ViewCursorsPart, SelectionsPart, CurrentLinePart, IndentGuidesPart,
                               WhitespacePart, RulersPart, GutterEdgePart, FoldingDecorationsPart, ZoomIndicatorPart,
                               ErrorStripePart, SquigglesPart, QuickFixBulbPart, InspectionWidgetPart, DiffBandsPart,
                               DiffChevronPart. render(int, int) is public: the editor's contract with what draws it
  .fold                        EditorFolding (view state)
  .diff                        DiffDecorations
  .suggest                     CompletionPopup, CompletionSession, CompletionRanking, EditorSuggest
  .doc                         DocumentationPopup, HoverDocumentation
  .find                        SearchReplaceBar, EditorFind
  .lang                        EditorLanguageFeatures, EditorDiagnostics, DiagnosticActions

com.crystalgui.desktop         CrystalOS: Desktop (found with Desktop.of(document), never built by a caller),
                               DesktopCommands, DesktopKinds, DesktopWindowMount (where a server's windows land),
                               SnapPreview
  .manager                     WindowManager (stacking, the active window, placement, snapping, the HUD),
                               WindowRecord, SessionRecord
  .window                      WindowFrame (a WindowCaption and the content slot), WindowChrome, WindowCommands,
                               WindowRegistry, WindowIcon, WindowMove, WindowKeyboardMove, SnapZones, SystemMenu
  .motion                      WindowAnimator over WindowAnimation (transform + opacity) and WindowGeometryAnimation
                               (layout), behind WindowMotion; writes Box's compositor overrides, never the cascade.
                               Under an async driver a WindowAnimation is a CompositorAnimation
  .taskbar                     Taskbar, TaskbarEntryMotion, TaskbarPreviews, TaskbarDesigner, WindowPreview,
                               WindowThumbnail
  .switcher                    WindowSwitcher: Mod+Tab, MRU order, live thumbnails
  .launcher                    LauncherButton, Launcher (the ApplicationRegistry rendered and searched); names no
                               application
  .app                         ApplicationKinds (the ServiceLoader SPI), ApplicationKind (the manifest: id, name, icon,
                               keywords, files opened, singleInstance, launch, autostart), Application (a running
                               instance; dispose is quitting), ApplicationRegistry (per Desktop), AppContext (windows(),
                               storage, cache, files), AppWindows / AppWindow / WindowSpec (an application asks for a
                               window and never builds a frame)

com.crystalgraphics.platform   CrystalGraphics' platform SPI, consumed here
  (root)                       CgPlatform, CgPlatformService (the closed bundle a loader registers), CgService (an open
                               slot with its own absent value; CgCursorService.SERVICE and CgNetworkChannel.SERVICE)
  .input                       CgSystemInput, CgInput, CgClipboard, CgKeyCodes, CgMouseCodes, CgModifiers

com.crystalgui.lifecycle       CgUiClientLifecycle (the desktop, loading tasks, reloads, paint teardown),
                               CgUiCommonLifecycle (protocols, the server workspace, the server smoke)

com.crystalgui.render          CgUiRenderer and its View per document (a CgUiPaintContext recording on CrystalGraphics'
                               CgCanvasRecorder, and the CgCanvasPresenter), Surface (a moved box's kept picture),
                               PaintSegments, InkOverflow, UiPaintWarmup
  .text                        FontFamilyCache: (font stack, px) → CgFontFamily
  .image                       CgUiImage (CSS's <image>, sealed records), CgUiImagePainter, CgUiMark, CgUiPainter
                               (hand-drawn images: paint(name) in CSS)
  .texture
    .asset                     FileIconTheme (VS Code's file-icon-theme model)

com.crystalgui.style           ElementStyle, StyleGroup, GeneralGroup, LayoutGroup, StyleOrigin, Styleable (what the
                               cascade matches), StyleScope (what a sheet can be scoped to), TaffyBridge,
                               PseudoClasses, StyleEngine, CssParsingUtil, CssAngle
  .sheet                       StyleSheet (+DEFAULT), StyleRule, DeclarationParser, StyleSheetRegistry
  .selector                    Selector, CompoundSelector, SelectorType
  .transition                  TransitionEngine, TransitionSpec, ActiveTransition, TransitionValue
  .property                    StyleProperty<T>, StylePropertyRegistry, StyleSlot, StyleValue, IValueInterpolator,
                               Shorthand, Shorthands, EdgeShorthand, PairShorthand
    .general.{bools,enums,floats,ints,strings}   scalar flavours
    .layout                    LayoutProperties, FlexShorthand
    .layout.{dimension,grid,length}              Taffy-shaped value types
    .visual                    Overflow, OverflowClip, ScrollBehavior, BoxOrigin, DrawableAlign, DrawableFit,
                               OutlineShorthand, OutlineStyle, BackgroundShorthand
    .visual.border             BorderRadiusProperties, BorderRadiusShorthand, BorderShorthand, BorderStyle,
                               LengthPercent
    .visual.color              ColorProperty, ColorValue, StyleColor, StyleColorProperty (currentcolor)
    .visual.text               FontFamilyValue
    .visual.image              ImageProperty, ImageValue, ImageKinds (the CSS image functions)
    .visual.transform          Transform (the ordered list of ops), TransformProperty, TransformValue,
                               TransformOriginShorthand

com.crystalgui.ui.contract     What a kind of widget is: WidgetContract (name, ordered State slots, Event slots),
                               State<W,V> (declaration order is apply order), Event<W,P> (a kind, a payload codec, how a
                               client listens), StateType/StateTypes, WidgetContracts (the registry and the local-only
                               list). Rates are CrystalGraphics' CgRatePolicy. A kind is a string an Event declares

com.crystalgui.screen          The client's one desktop: CgUiScreen (install, open, close, Mode, the tick, the paint at
                               HUD and SCREEN), CgUiScreenInput (the one input router), DesktopConnection, GameState,
                               DesktopContent, HitRegions; game screens: ScreenKind, ScreenContext, GameScreen,
                               ServerScreens; UiScale (the owner of the UI scale)

com.crystalgui.viewport        One document on screen: CgUiViewport (the document's thread, input, frames) over a
                               DocumentThread per threading (inline, lockstep, sequenced), CommitQueue,
                               CompositorMotion. Nothing in `ui` names it

com.crystalgui.ui              a namespace, nothing at its root
  .dom                         the node tree
  .box                         the box tree
  .service                     Input, Focus, Animation, Lifecycle, Dismiss
  .contract                    above
  .event                       UIEvent, PropagationPhase, Close/DOM/Drag/Focus/Keyboard/Mouse events
  .input                       FocusPolicy, ButtonState, the keymap (.keymap)
  .text                        TextRange, HighlightRegistry (::highlight(name))
  .data                        UiDataKeys; the SPI they point at (ClipboardActions) is core.data

com.crystalgui.widget          the widgets, layered (LayeringTest): .control/.display/.text/.scroll < .overlay/.layout/
                               .dnd < .collection < .composite < .config < .canvas < .graph < .texteditor.
                               .scroll is a sibling of .layout, never a child

com.crystalgui.workbench       The shell; the root holds only the hub: Workbench (implementing WorkbenchContext, the
                               surface extensions are written against), WorkbenchSession (the arrangement record),
                               WorkbenchSettings, WorkbenchKinds, WorkbenchMenus, and what coordinates several
                               sub-packages: DocumentTabs, SaveActions, NetworkedPanels
  .app                         WorkbenchApplication (the runtime every workbench product shares, built from a
                               manifest's extension ids), WorkbenchApplicationCommands (Save File, Save/Restore Layout)
  .extension                   WorkbenchExtension, WorkbenchExtensions (ServiceLoader), SessionSlice, and the engine's
                               own: ProjectExtension, ProblemsExtension, NotificationsExtension, PresenceExtension,
                               InspectorExtension
  .chrome .dock .explorer .region .stripe .toolwindow .view .decoration .diff .search
                               each owns the binding that serves it (ProblemsBinding and PresenceBinding are
                               .chrome.status; ExplorerBinding and ProjectSourcesIndex .explorer; WorkbenchOpener .dock)
  .editor                      EditorService (one lane for opening anything), TextEditorView, TextFileKind
com.crystalgui.app             the manifests: .crystaleditor (CrystalEditor), .shadergraph, .machine, .screens

com.crystalgui.text            Rope, TextBuffer, TextSummary, Change/ChangeSet, Selection, SelectionModel, TextPoint,
                               TextRange, WordClassifier, WordOperations, LineEnding: the headless document model.
                               SimilarNames and DerivedNames, shared by both engines
  .cursor                      CursorColumns, MoveOperations, TypeOperations, LineOperations, MouseSelection,
                               ColumnSelection: VS Code's boundaries (text/cursor/CLAUDE.md)
  .syntax                      Language, LanguageRegistry (bootstrap() on first read), SyntaxToken, SyntaxTokenizer
                               (SPI), KeywordTokenizer (the engineless tier), LanguageKinds (the ServiceLoader seam)
  .lang                        interfaces only: LanguageServices (per document), SemanticTokenProvider, Resolver,
                               CompletionProvider, CompletionItem/CompletionList, SymbolInfo/SymbolKind/SymbolModifier,
                               TypeRef, DeclarationSite, Versioned, TypeSearch + TypeSearchRegistry
  .diagnostic                  Diagnostic, DiagnosticSet, DiagnosticSeverity, DiagnosticTag, Markers,
                               RelatedInformation: LSP-shaped, per owner
  .wrap                        LineProjection, ProjectedLines, LineBreaksComputer (SPI), MonospaceLineBreaks,
                               ShapedLineBreaks, BreakOpportunities, WrapIndent: soft wrap and the model/view seam
  .view                        IndentLevels, WhitespaceMarkers, RenderWhitespace
  .fold                        FoldingRegions, FoldingModel, FoldingRangeProvider (SPI), IndentRangeProvider
                               (indent-based by default, as Monaco's)

com.crystalgui.document        What an open document is, headless: Document (the identity; onDidChangeResource),
                               DocumentModel (the SPI), AbstractDocumentModel (apply(Edit) the one door),
                               TextDocumentModel (owns the language services, shared by split panes),
                               BytesDocumentModel, DocumentKind + DocumentKinds, DocumentEditor (names UIElement),
                               Documents + DocumentReference (disposed by the last holder), DocumentState,
                               EditorInput, RecentFiles. Creation: NewDocumentKind, NewDocumentContext,
                               NewDocumentKinds

com.crystalgui.fs              Resource, CgPath, CgFileError, CgFileSystemException: vocabulary every tier names
  .project                     ProjectRegistry, ProjectInfo, WorkspaceProject, SourceRoots, Excludes, ProjectProvider
  .provider                    CgFileSystem (SPI), LocalFileSystem, InMemoryFileSystem (monotonic clock, reproducible
                               etags), CgFileEntry, CgFileCapability, CgFileEvent, NioFileEventSource
  .protocol                    FsMethods, FsMessages (records with codecs), FsError, FsHello (the greeting)
  .server                      WorkspaceService (authorise, etag, cap, trash), WorkspacePermission, WorkspaceActor,
                               WorkspaceOperation, WorkspaceTrash, WorkspacePresence, WorkspaceConflictException,
                               ServerWorkspace, WorkspaceBinding (one connection's end), WatchHub (one subscription
                               table; update() once a tick), WorkspaceAudit, RecentOperations
  .client                      Workspace (one per connection, on the services sequence), WorkspaceClient (one per
                               application: files(), presence(), capabilities(), health(), and the provider door
                               UiBudget times), FileOperations, FsCall, WorkspaceDocuments, ContentProvider +
                               ContentProviders, Backup (hot exit), LocalHistory, Health

com.crystalgui.serialization   style codecs only; the codecs are CrystalGraphics' com.crystalgraphics.serialization
  .style                       StyleValueCodecs, InlineStyleCodec

com.crystalgui.net             ServerUiSession, ClientUiSession, ClientUiSessions, UiWindowMux, SheetRef, over
                               CrystalGraphics' com.crystalgraphics.net. Ids live in ui.dom.UIElementTreeSource
  .mirror                      ServerTreeMirror<N,T>, ClientTreeMirror<N,T>, NodeMirror<N,T> (encode and apply on
                               one interface), UIElementMirror, TreeOps
  .protocol                    UiMethods
  .window                      the layer a mod uses: Networked<M>, UiType, ServerScope/ClientScope, ServerWindow<P>,
                               ServerWindows/ClientWindows, WindowMount, Presentation, RowSource/RemoteRows
                               (CGUI_BUILDING_UIS.md)
  .projection                  Declare a read/write pair once; written only on change and only while watched. `each`
                               for keyed lists; AutoProjection reports what it could not wire

                               "Window" has three senses: UIDocument (the engine for one surface), WindowFrame (the
                               chrome on a desktop), ServerWindow (the networked unit, which the protocol means)

com.crystalgui.gauntlet        CrystalGUI's tests for CrystalGraphics' gauntlet (CrystalGraphics/docs/GAUNTLET.md):
                               DesktopTest (crystalgui:desktop; DesktopTest.onFrame is a mod's step),
                               DesktopScriptTest, OpenCloseTest, ConnectionTest (over a real connection; a
                               meaningless check is skipped with its reason), ServerTest. They ship
```

`render/` is top-level (`com.crystalgui.render`). There is no `core/input/`, `core/sound/` or `core/event/`: platform
I/O is `com.crystalgraphics.platform`, dispatch and focus are `ui/service` and `ui/input`, event types `ui/event`.

## The headless classpath

**The classpath is a dedicated server's**: CrystalGraphics `core` and `platform`, which a server ships (`core` holds
utilities the engine names in its types, such as `com.crystalgraphics.easing`), and no GL context or fonts. Code that
reaches the GPU outside a paint body fails here rather than in production. `HeadlessClasspathSanityTest` pins what
must be present, `StyleSheet` included.

JOML and Taffy **must stay** on it: `UINode` and `ElementStyle` have fields of their types (`Matrix4f`, `NodeId`,
`TaffyStyle`), and field descriptors resolve at class load.

## The platform seam: slots, and a GL bundle

A loader registers one `CgPlatformService`, the GL backend and its context alone, and fills `CgService` **slots**, one
per capability, each read through its owner and carrying its own absent value.

| Need | Reached via | Lives in |
|---|---|---|
| Modifier, key and button state, **from UI code** | `document.input().modifiers()`/`isKeyDown`/`isButtonDown`, or `Input.modifiersNow()` | `ui/service/Input`: as of the event being handled; a document on its own thread reconciles with the platform once a host frame |
| What is held, in the engine's codes | `CgInput`: hosts, and `Input` for a document on the client thread | `platform/input/CgInput` |
| The clipboard | on the client thread: `CgHostThread.CLIENT.run(() -> CgClipboard.set(text))`, and `CgHostThread.CLIENT.call(CgClipboard::get).then(paste)`, answered on the document's thread | `platform/input/CgClipboard` |
| UI sounds | `CgSounds.play2D`, from any thread | `com.crystalgraphics.audio.CgSounds` |
| Raw event sink (`Input` implements it) | — | `platform/input/CgSystemInput` |
| Code constants | — | `platform/input/CgKeyCodes`, `CgMouseCodes`, `CgModifiers` |
| **Presenting a cursor** | `CursorService.setCursor(...)`, from any thread (the last asked wins) | `core.cursor`, ours (`core/cursor/CLAUDE.md`) |
| The world: levels, entities, players, camera, world sounds | CrystalGraphics' facades `CgWorldQueries`, `CgEntityQueries`, `CgPlayers`, `CgCameraManager`, `CgSounds` | `platform/world/` |

- **A slot is read through its facade**, never `CgPlatform.get(X.SERVICE)` (CrystalGraphics' `platform/CLAUDE.md`
  § *The wiring standard*).
- **Every one is a slot with an absent value**, never a bundle method. A host with nothing to present to (a server, a
  headless test) registers no stub: the cursor slot answers `CursorService.NONE` and the absence is logged once.
- CrystalGUI has no registry of its own: `CrystalGuiCore` holds only `LOGGER`.

## Docs, and when to read each

| Doc | For |
|---|---|
| **`CGUI_SETUP.md`** | **Setting up a mod on CrystalGUI**: one Minecraft version (the `com.crystalgui` plugin) or one jar across many (`targets {}`) |
| **`CGUI_BUILDING_UIS.md`** | **Using CrystalGUI**: a client-only UI, a networked one, and how to choose, by example, ending in a symptom→cause table |
| **`CGUI_BUILD.md`** | The build: layout, commands, what each check sees, adding a Minecraft version |
| **`CGUI_CROSS_VERSION.md`** | Code against every Minecraft version and loader; the `cross-version` skill is its checklist |
| **`CGUI_PROFILING.md`** | Profiling CrystalGUI in one run; after CrystalGraphics' `docs/PROFILING.md`. The `profiling` skill is its checklist |
| **`CGUI_WORKBENCH_EXTENSIONS.md`** | Getting a panel, a file type, a command or a status entry into somebody else's workbench |
| **`CGUI_ARCHITECTURE.md`** | Where everything lives |
| `CGUI_SHIPPED_ASSETS.md` | Everything under `assets/crystalgui/`: sheets, themes, icons, fonts |
| **`CGUI_INVARIANTS.md`** | What is invisible from any single class, by subsystem. **Read the section for what you touch** |
| `CGUI_STYLE_RENDER_PIPELINE.md` | The cascade and the paint path: origins, selectors, transitions, images, compositing |
| `CGUI_WIDGETS.md` | Per-widget API, `::part()` names, pseudo-classes, and the harness scene covering each |
| `CGUI_WORKBENCH_SERVICES.md` | What a widget may *ask* for. **New service API is added here in the same commit** |
| `CGUI_SERVER_AND_SERIALIZATION.md` | Codecs, descriptions, content hashing, sessions, RPC, and the headless contract |
| `CGUI_NETWORKING_PRIMER.md` | Networking from the bottom up: frames, sessions, peers, a `CgProtocolConnection`, a packet contract on both halves |
| `CGUI_LANGUAGE_STACK.md` | How analysis resolves a name, and why it answers differently on one host than another |
| `CGUI_THEMING.md` | Themes, editor schemes, the token vocabulary. Its token table is generated: regenerate it from the failing test |
| `CGUI_COMMANDS.md` | Every declared command, by area, with menus and keys. A regenerated snapshot: trust the code where they disagree |
| `CGUI_NEW_ENGINE.md` | Reading a commit or comment that names the old engine |
| `CGUI_MODERN_UI_RENDERING_RESEARCH.md` | The sources behind glass, blur, gradients and the taskbar. **Read the section before touching any of them** |

## External references

In-repo checkouts under `research_repos/`, never dependencies:

- **Taffy** (`research_repos/taffy/dev/vfyjxf/taffy/`): the upstream sources our `taffy/` fork came from. Read them
  rather than guess at layout semantics.
- **Monaco** (`research_repos/monaco`): where every "VS Code does X" in `text.cursor` was read.
- **Minecraft 1.20.1** (`research_repos/mc1201_sources/`): an extracted tree to cite. A real node's
  `extractMcSources` writes `build/mc-src/` (`CGUI_CROSS_VERSION.md`).
