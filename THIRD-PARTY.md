# Third-party notices

Everything in this repository that was written by somebody else, what it is licensed under, and what that
obliges us to do. **This file is an obligation, not documentation** — MIT requires its copyright notice to
travel with the distribution, and Apache 2.0 requires the licence, any `NOTICE`, and a statement of
modifications. A javadoc comment naming the source is good practice and is not the same thing.

Per-directory detail lives beside the assets it covers; this is the index.

| What | Where | Licence | Notes |
|---|---|---|---|
| IntelliJ Platform icons | `core/src/main/resources/assets/crystalgui/ui/icons/filetypes/` | Apache 2.0 | © 2000–2021 JetBrains s.r.o. Verbatim. See [ATTRIBUTION.md](core/src/main/resources/assets/crystalgui/ui/icons/ATTRIBUTION.md) |
| Feather icons | `core/src/main/resources/assets/crystalgui/ui/icons/` | MIT | © 2013–2023 Cole Bemis. Verbatim |
| Minecraft fonts | `core/src/main/resources/assets/crystalgui/ui/fonts/` | Public domain | `Minecraft.otf`, `MinecraftRegular.otf` |
| JetBrains Mono | `core/src/main/resources/assets/crystalgui/ui/fonts/` | **SIL OFL 1.1** | `JetBrainsMono-Regular.ttf`. The **code** face — the editor and anything carrying `.__syntax__`. See [Fonts](#fonts) |
| Taffy | Gradle dependency `dev.vfyjxf:taffy` | — | Extracted sources checked in at `research_repos/taffy/` for reference only |
| LDLib2 | `research_repos/LDLib2/` | — | In-repo checkout, read for pattern prior art. **Not** a dependency and nothing is copied from it |
| Minecraft 1.20.1 sources | `research_repos/mc1201_sources/` | Proprietary | Decompiled reference. Not redistributed, not built |
| tree-sitter binding + six grammars | `lib/tree-sitter/` | MIT | See [lib/tree-sitter/README.md](lib/tree-sitter/README.md) for per-jar provenance |
| Eclipse JDT (`org.eclipse.jdt.core` + the platform closure) | `language/build.gradle.kts`, bands 8/11/17 | **EPL-2.0** | The Java engine. See [Engine bands](#engine-bands-ecj-and-rhino) |
| Rhino | `language/build.gradle.kts`, bands 8/11/17 | **MPL-2.0** | The JavaScript engine. See [Engine bands](#engine-bands-ecj-and-rhino) |
| ASM (`asm`, `asm-commons`, `asm-tree`) | `language/build.gradle.kts` | **BSD-3-Clause** | © INRIA, France Télécom. The bytecode reader/writer behind the readable↔runtime mapping boundary. 0.24 MB, no transitive dependencies, real classes at class-file major 49 so it runs on every band |

## Engine bands: ECJ and Rhino

Three sets of jars, one per host-JVM band (`plan_syntax.md` §6). Declared in
`language/build.gradle.kts` as resolvable configurations that **nothing consumes** — they are loaded
reflectively into an isolated classloader at runtime, never onto a compile classpath.

| Band | Host JVM | `org.eclipse.jdt:org.eclipse.jdt.core` | `org.mozilla:rhino` | Closure |
|---|---|---|---|---|
| 8 | Java 8–10 | 3.26.0 | 1.7.15.1 | 15 jars, ~13 MB |
| 11 | Java 11–16 | 3.33.0 | 1.9.1 | 18 jars, ~12 MB |
| 17 | Java 17+ | 3.46.0 | 1.9.1 | 20 jars, ~16 MB |

The remaining jars in each closure are `org.eclipse.platform:*` (EPL-2.0), pulled in by JDT, plus
`org.eclipse.jdt:ecj` (EPL-2.0) and — in band 17 only — `net.java.dev.jna:jna` and `jna-platform`
(dual **Apache-2.0 / LGPL-2.1**; we take Apache-2.0). Every platform artifact is pinned explicitly
rather than resolved through JDT's open version ranges; the reason is in the build file and it is a
correctness one, not a licensing one.

### What the two licences require

| Licence | Obligation when we distribute a jar containing it |
|---|---|
| **EPL-2.0** (JDT, Eclipse platform, ECJ) | Ship the licence text; state that the code is available under EPL-2.0 and where to get the source (Maven Central, at the exact coordinates above); do not remove existing notices. EPL is file-level copyleft — **modifying** any of these files would oblige us to publish those files under EPL, which is why they are consumed as unmodified binaries |
| **MPL-2.0** (Rhino) | The same shape: licence text, source availability for the covered files, notices intact. Also file-level, so the same "unmodified binary" reasoning applies |

**Neither is viral into our code.** Both are file-level, and CrystalGUI calls them across a
classloader boundary without linking against modified copies — which is a consequence of the
isolation `EngineClassLoader` exists for, not a coincidence.

> **This becomes a live obligation the moment a loader module ships.** Nothing in this build
> distributes an engine today: the configurations resolve for tests and for the band-floor check, and
> `mc1710/` and `mc1201/` are commented out of `settings.gradle.kts`. When one of them starts
> bundling a band, the licence texts have to travel in the jar — a row in this table is the index, not
> the discharge. Recorded here rather than left to be discovered at release.

## Fonts

The UI's default face is **JetBrains Mono**, © 2020 The JetBrains Mono Project Authors, under the
**SIL Open Font License 1.1**. <https://github.com/JetBrains/JetBrainsMono>

Three obligations, and the third is the one that catches people:

1. The copyright notice and the licence travel with the distribution — so `OFL.txt` ships **beside the
   font file**, in `ui/fonts/`, not only referenced from here.
2. The font may not be sold on its own. It is not; it is one asset inside a UI engine.
3. **A modified version may not use the reserved font name.** So a subsetted or re-hinted build must be
   renamed. We ship it verbatim, which avoids the question entirely — and that is the reason to keep
   shipping it verbatim rather than trimming it to the glyphs we use.

> **Why monospace, and why only for code.** Anything that lays code out by *counting characters* — the
> Quick Documentation popup's hanging indent under `implements`, indent guides, a column ruler — is exact
> in a monospace face and only approximate in a proportional one, because a space is narrower than an
> average glyph by a ratio that changes with the size. That is not tunable; it is a property of the face.
>
> It was briefly the default for the **whole UI**, and that was an overreach: the argument above is about
> code and says nothing about a menu bar or a tab strip, which read worse in it. Both references split it
> the same way — IntelliJ uses **Inter** for the entire IDE and JetBrains Mono only in the editor, and VS
> Code pairs the system UI font with a mono editor face. So `ua/editor.css` names the code surfaces and
> everything else inherits the proportional default.

**IBM Plex Sans is the UI face**, and also the second entry in the code stack: `font-family` is a
preference list, so it supplies any glyph JetBrains Mono lacks. It ships from CrystalGraphics under the
SIL OFL 1.1 as well.

## Ports, as opposed to assets

Code in this repository ports algorithms and module boundaries from other editors. The rule is recorded in
`AGENTS.md` § *Licences are load-bearing here*, and repeated because it is the thing most likely to be got
wrong by someone moving fast:

| Source | Licence | What is permitted |
|---|---|---|
| VS Code / Monaco, CodeMirror 6 | **MIT** | **Port the code.** Attribute in the class javadoc, naming the source file |
| **Zed** | **GPL-3.0** | **Read for shape only.** Copying it would impose GPL on this repository. `Rope`/`TextSummary` take `SumTree`'s *design*; not a line of its code |

## Trademarks

No licence here grants trademark rights — Apache 2.0 § 6 says so explicitly, and MIT is silent, which
amounts to the same. Product logos and language marks (the JetBrains and IntelliJ IDEA marks, the Java
coffee cup, the Python, Rust and Docker logos) belong to their owners regardless of the licence on the file
they arrive in.

This matters for what we *ship*, not for what we test against. The IntelliJ Platform file-type icons in
`ui/icons/filetypes/` are JetBrains' own drawings of documents and are not marks. `IntelliJ_IDEA_Icon.svg`
**is** a mark, and lives in `core/src/test/resources/` for that reason — it is the SVG renderer's torture
test (nested groups, four `userSpaceOnUse` gradients, entirely filled polygons) and is deliberately not in
the jar.
