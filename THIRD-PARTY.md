# Third-party notices

Everything in this repository that was written by somebody else, what it is licensed under, and what that
obliges us to do. **This file is an obligation, not documentation** — MIT requires its copyright notice to
travel with the distribution, and Apache 2.0 requires the licence, any `NOTICE`, and a statement of
modifications. A javadoc comment naming the source is good practice and is not the same thing.

Per-directory detail lives beside the assets it covers; this is the index.

| What | Where | Licence | Notes |
|---|---|---|---|
| **IntelliJ diff/merge algorithms** | `core/src/main/java/com/crystalgui/text/diff/` | **Apache 2.0** | © 2000–2024 JetBrains s.r.o. **Ported source, modified** — `DiffRange` (`util/Range`), `DiffIterable` (`comparison/iterables/FairDiffIterable`), `MergeRange` (`util/MergeRange`), `MergeRanges` (`ComparisonMergeUtil.FairMergeBuilder` + `ChangeBuilder`), `ComparisonPolicy` (`comparison/ComparisonPolicy` + the equality half of `ComparisonUtil`). Each class names its upstream file and its modifications in its own javadoc, per § 4(b) |
| IntelliJ Platform icons | `core/src/main/resources/assets/crystalgui/ui/icons/filetypes/` | Apache 2.0 | © 2000–2021 JetBrains s.r.o. Verbatim. See [ATTRIBUTION.md](core/src/main/resources/assets/crystalgui/ui/icons/ATTRIBUTION.md) |
| Feather icons | `core/src/main/resources/assets/crystalgui/ui/icons/` | MIT | © 2013–2023 Cole Bemis. Verbatim |
| Minecraft fonts | `core/src/main/resources/assets/crystalgui/ui/fonts/` | Public domain | `Minecraft.otf`, `MinecraftRegular.otf` |
| JetBrains Mono | `core/src/main/resources/assets/crystalgui/ui/fonts/` | **SIL OFL 1.1** | `JetBrainsMono-Regular.ttf`. The **code** face — the editor and anything carrying `.__syntax__`. See [Fonts](#fonts) |
| **Taffy** (`taffy-java`) | `taffy/` | **MIT** | © 2026 vfyjxf. A pure-Java port of Rust [Taffy](https://github.com/DioxusLabs/taffy). **VENDORED AND MODIFIED** — the sources of `dev.vfyjxf:taffy:1.1.4`, checked in and forked, because its leaf-measure path is wrong under `flex-wrap: wrap` and the defect is one line inside the flexbox algorithm. [`taffy/MODIFICATIONS.md`](taffy/MODIFICATIONS.md) is the statement of changes MIT requires; `taffy/LICENSE` is the notice. The package stays `dev.vfyjxf.taffy` because `mc1710` relocates it when shipping. The pre-fork sources remain at `research_repos/taffy/` as the diff baseline |
| fastutil | Gradle dependency, via `taffy/` | **Apache 2.0** | © 2002–2023 Sebastiano Vigna. Taffy's own dependency, for seven types. Shaded and relocated into the mod jar by `mc1710` |
| LDLib2 | `research_repos/LDLib2/` | — | In-repo checkout, read for pattern prior art. **Not** a dependency and nothing is copied from it |
| Minecraft 1.20.1 sources | `research_repos/mc1201_sources/` | Proprietary | Decompiled reference. Not redistributed, not built |
| tree-sitter binding + six grammars | `lib/tree-sitter/` | MIT | See [lib/tree-sitter/README.md](lib/tree-sitter/README.md) for per-jar provenance |
| tree-sitter query families (`folds.scm`, `indents.scm`, `locals.scm`) | `language/src/main/resources/assets/crystalgui/syntax/*/` | **Apache 2.0** | © nvim-treesitter contributors. Six languages × three families. **Modified**: each file's upstream `; inherits:` chain is resolved at vendoring time by concatenation, since this engine implements no query inheritance; the resolved sources are named in every file's header. One pattern is added at load time (`Queries.captureJavaScriptParameters`) and is marked as ours in the query text. See [Query families](#query-families-folds-indents-and-scopes) |
| Eclipse JDT (`org.eclipse.jdt.core` + the platform closure) | `language/build.gradle.kts`, bands 8/11/17 | **EPL-2.0** | The Java engine. See [Engine bands](#engine-bands-ecj-and-rhino) |
| Rhino | `language/build.gradle.kts`, bands 8/11/17 | **MPL-2.0** | The JavaScript engine. See [Engine bands](#engine-bands-ecj-and-rhino) |
| Minecraft name mappings (MCP `stable_12` for 1.7.10) | **Not in this repository** — fetched at runtime to the user's own config directory | See [Name mappings](#minecraft-name-mappings-fetched-never-redistributed) | `methods.csv` / `fields.csv` / `params.csv`, read from MinecraftForge's FML repository. We do not redistribute them, and that is the whole licensing position |
| ASM (`asm`, `asm-commons`, `asm-tree`) | `language/build.gradle.kts` | **BSD-3-Clause** | © INRIA, France Télécom. The bytecode reader/writer behind the readable↔runtime mapping boundary. 0.24 MB, no transitive dependencies, real classes at class-file major 49 so it runs on every band |
| CFR | `language/build.gradle.kts`, bands 8/11/17 | **MIT** | © Lee Benfield. The decompiler behind the library viewer, for a class that ships no source. Version 0.152, ~2 MB, **no transitive dependencies**, and written in Java 6 — so unlike ECJ and Rhino one artifact serves every band. See [The decompiler](#the-decompiler-cfr) |

## The decompiler (CFR)

The library viewer opens a classpath type in a read-only tab. Where the type has attached source —
`src.zip`, a `-sources.jar`, a bundled `assets/<ns>/sources/` — that is what it shows. Where it has
none, CFR reconstructs the class from its bytecode.

| | |
|---|---|
| Artifact | `org.benf:cfr:0.152` |
| Licence | **MIT** — © Lee Benfield |
| Size | ~2 MB, no transitive dependencies |
| Runs on | Java 6 and up |
| Decompiles | up to roughly Java 17 features — records, sealed classes, `instanceof` patterns, switch expressions |

**The MIT obligation is the copyright notice travelling with the distribution**, which this file is.
Nothing here is modified: it is consumed as a published binary through
`org.benf.cfr.reader.api.CfrDriver`, the stable entry point its author documents as such.

### Why CFR rather than Fernflower or Vineflower

Fernflower is what IntelliJ ships, so its output would match the reference exactly — and recent
Vineflower needs Java 11, which rules out band 8 outright, while Apache 2.0 would add a NOTICE and a
statement-of-modifications obligation on top of the licence text. CFR is Java 6, so **one jar serves
every band**: it is the only artifact in that configuration with no per-band pin, where ECJ and Rhino
each need a version chosen against the band's class-file ceiling.

### The version is old, and that is a measured trade

0.152 is from **December 2021** and is the last published release; the author resumed work in March 2026
with nothing published yet. Its ceiling sits above everything this feature will actually be handed:

- **A mod jar cannot be newer than the JVM that loads it** — 1.7.10 is Java 8 bytecode, 1.20.x is 17.
- **The JDK never reaches the decompiler at all**, because it comes from `src.zip`.

What it does not cover is Java 21's record patterns and anything after, which would only appear if a
modern library were put on a script's classpath. When that happens the failure is contained: the adapter
answers null, the viewer says so for that class alone, and the answer is cached so it is not retried on
every click. The seam is one bridge method (`engine.bridge.Decompiler`), so swapping in Vineflower for
band 11+ later is a jar change rather than a rewrite.

### What a decompiled view is not

It reconstructs, so **there are no comments** and local names survive only where a `LocalVariableTable`
does (Gradle passes `-g`; Minecraft and Forge jars carry debug info). Kotlin and Scala come back as
plausible but ugly Java. The viewer says which of the two forms is on screen rather than leaving a
reader to conclude that somebody's class was written without a single comment.

**On an obfuscated 1.7.10 host the bytes come from `TypeBytes.readable`**, which is the runtime's own —
post-transformer, post-mixin, already remapped — so the view shows `getBlock` rather than
`func_147439_a`, and shows the class as the running game has it. That is a thing a decompiler pointed at
a jar cannot do.

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

> **This is now a LIVE obligation, and the note here used to say it was not.** It read "nothing in this
> build distributes an engine today … `mc1710/` and `mc1201/` are commented out of
> `settings.gradle.kts`". Both halves stopped being true at M12: `settings.gradle.kts` carries
> `include("mc1710")`, and that module's jar task bundles a band — **by default band 8's fifteen jars,
> about 13 MB**, which a client with no engine staged falls back to. This is the sentence the old note
> asked somebody to come back and change.
>
> **Which bands ship is `-PcgBundleBands`** (default `8`; also `8,17`, or `none`), so what this obligation
> covers varies per build. And it introduces a second position beside the first: a band the jar does *not*
> carry is **fetched by the user's client from Maven Central**, verified against a digest computed at build
> time from the artifact Gradle resolved. We do not redistribute those — the same position the MCP mapping
> data is in, and worth stating rather than assuming.
>
> **What discharges it today.** `bundleEngineBands` is a `Sync` of *whole, unmodified jars* rather than a
> shadow or a class merge, so each artifact's own notices travel inside it — verified rather than
> assumed: the Eclipse jars carry `about.html` and are signed, and Rhino carries `META-INF/LICENSE.txt`
> and `META-INF/NOTICE.txt`. Nothing is repackaged, relocated or stripped, which is also what keeps the
> file-level copyleft of EPL-2.0 and MPL-2.0 away from our own code. The "where to get the source" half
> is discharged by the exact coordinates in the table above.
>
> **What to check before a release**, since a row in this table is an index and not a discharge: that the
> band is still bundled as whole jars (a future shadow/relocation step would break both the notices and
> the unmodified-binary reasoning), and that `mc1201/` gets the same treatment when it lands rather than
> merging classes.

## Minecraft name mappings (fetched, never redistributed)

Readable Minecraft names (`getUnlocalizedName` rather than `func_149739_a`) come from the **MCP** name
data, taken from MinecraftForge's FML repository at
`https://raw.githubusercontent.com/MinecraftForge/FML/1.7.10/conf/` — `methods.csv`, `fields.csv` and
`params.csv` for `mcp_stable/12`.

**None of it is in this repository and none of it is in any jar we build.** A client fetches what it
needs on first use into its own config directory (`config/crystalgui/mappings/<mc>/<channel>-<version>`)
and reuses it thereafter. That is not a caching optimisation that happens to have a licensing
side-effect — it is the licensing position, chosen because MCP's terms have historically permitted use
while restricting redistribution, and it is why `plan_syntax.md` §22 row 11 asks for the sourcing
decision rather than for a bundled file.

Three properties follow from it, and all three are enforced in code rather than remembered:

- **Version-addressed, never discovered.** `MappingCoordinates` pins the channel and version in the mod
  rather than reading them from the running environment, because a published mapping version is
  immutable — `mcp_stable/12` will never change content under that name. A version read from the
  environment is one that can differ between dev and production.
- **Absent is a first-class state.** A clean install has no mappings, an offline first run says so and
  falls back to runtime names rather than failing, and nothing about the editor depends on the fetch
  having happened.
- **The cache is the user's, not ours.** It lives under their game directory; deleting it re-fetches.

> **Open, and honest about it:** no digests are pinned, because upstream publishes no `.md5` beside the
> CSVs. A corrupted download is currently caught by the parse rather than by a digest. The verification
> machinery exists and is tested (`MappingCacheTest` covers corrupt-then-repair and reject-on-mismatch);
> it is the reference data that is missing. Recorded here as well as in `plan_m12.md` §26.13a because
> this file is where somebody checks before a release.

## OpenJDK sources (fetched, derived on the user's machine, never redistributed)

Hovering `java.util.List.add` in the editor quotes the JDK authors' own declaration and javadoc instead
of a form assembled from the binding. M13 §25.5, and it is a licence question before it is a feature one.

**OpenJDK source is GPLv2 with Classpath Exception.** The exception covers *linking*, not redistributing
a modified extract, and a body-stripped copy is arguably a derivative work. There is no LICENSE file in
this repository at all today, so "GPL-compatible" is not established and nothing derived from OpenJDK may
travel in any jar we build.

**So nothing does, and the chain is built so that it cannot.** Three steps, in order:

1. **The running JVM's own `src.zip`.** Free, already on the machine, nothing fetched.
2. **Any other JDK installed on that machine** — `JAVA_HOME`, `JDK_HOME`, the conventional install roots,
   the Gradle and SDKMAN toolchain caches. This is the step that fires most often in production: a modded
   player launches on a jlink'd JRE, which carries no `src.zip`, while frequently having installed a full
   JDK because a pack's guide told them to.
3. **A fetch, and only when asked.** `Download JDK Sources` in the command palette. The archive is
   downloaded **by the user's own client, over HTTPS, from whoever publishes the JDK** — we are not in the
   distribution chain — and the body-stripping transform then runs **on that machine, for that machine**.
   Producing a derived work for your own use is not distributing it; building the same extract at *our*
   build time and shipping it would have been redistribution of a modified GPL work, which is exactly what
   this arrangement refuses.

The default source is Eclipse Adoptium's published `sources` artifact for the running feature version.
`crystalgui.jdk.sources.url` overrides where it is fetched from, and `crystalgui.jdk.sources` points
straight at a `src.zip` for anyone who would rather supply their own and fetch nothing.

> **Never automatic**, and that is a licence decision as much as a bandwidth one. The engine bands and the
> mapping data are fetched on a first launch because without them the feature does not work at all; this
> one only improves a feature that already works, so it waits to be asked. IntelliJ's *Download
> documentation* is the same affordance for the same reason.

> **What is ours here:** `SourceHeaders` (the transform), `TarArchive` (a minimal `.tar.gz` reader, since
> the JDK ships one for zip and not for this) and `JdkSourceExtract`. No OpenJDK code was read to write
> any of them — the tar format is a published specification and the transform is a scanner over Java's
> own grammar.

## Chromium — `RateEstimator` (BSD-3-Clause)

`core/src/main/java/com/crystalgui/core/async/RateEstimator.java` is a port of Chromium's download rate
estimator — `components/download/public/common/rate_estimator.h` and its implementation under
`components/download/internal/common/` — Copyright The Chromium Authors, licensed BSD-3-Clause.

A fixed ring of one-second buckets, ten of them, giving a rate over a ten-second sliding window, with the
divisor counting only the buckets that have actually been populated so an early reading is not divided by
a window that has not elapsed.

**Ported rather than written, after three home-grown attempts failed in front of a user.** A cumulative
average cannot track a throughput that changes; an exponential moving average tracks it far too well and
yo-yos. `AGENTS.md`'s *"Port, don't reinvent"* rule names exactly this class of problem, and both
references consulted — Chromium and wget — independently land on a sliding window.

> **wget's `progress.c` is GPL-3.0 and none of its code is here.** What was taken from it is one design
> idea, stated in its own comment: *"Don't refresh the ETA too often to avoid jerkiness in predictions.
> This allows ETA to change approximately once per second."* That throttle lives in `JobContext` and is
> written from scratch. The same read-for-shape-only rule this repository already applies to Zed.

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
| **IntelliJ Community** | **Apache 2.0** | **Port the code**, with attribution and the statement of modifications § 4(b) asks for. **Source is now ported, not only behaviour** — the diff/merge algorithms in the table above, each carrying its own § 4(b) statement. *(This row read "behaviour rather than source" until 2026-08-22, which was true when written and stopped being true the moment `com.crystalgui.text.diff` took `FairMergeBuilder`. A licence index that describes an older state of the repository is worse than none, because it is the thing somebody checks instead of looking.)* Behaviour-only ports remain: `JavaDocs` follows `JavaDocInfoGenerator`’s block-tag section order (deprecated → params → return → throws → since → author/version → the API tags → see-also → unrecognised last) and its inline-tag mapping, each named at the rule it decides. The file-type icons in the table above are the same licence arriving as assets |
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


## Query families: folds, indents and scopes

`highlights.scm` and `injections.scm` come from **the grammar author's own** `queries/` directory, which
is the rule `lib/tree-sitter/README.md` records and the reason those are not listed separately here: they
travel under their grammar's own licence.

The other three do not exist upstream. `tree-sitter/tree-sitter-java` ships `highlights.scm` and
`tags.scm`; `tree-sitter-grammars/tree-sitter-glsl` ships `highlights.scm` alone. The pattern is general —
a *grammar* repo ships highlights and tags, and the richer families live in editor **runtime** repos — so
for `folds.scm`, `indents.scm` and `locals.scm` there is no author's file to match, and the choice was a
licence one:

| Source | Licence | What it would cost |
|---|---|---|
| **nvim-treesitter** ✅ | Apache-2.0 | Licence, notice, and a statement of modifications — the terms the IntelliJ file icons already ship under |
| Helix runtime | MPL-2.0 | File-level copyleft: each `.scm` stays MPL and carries its own notice |
| Write our own | — | Eighteen files, ours, no notice — and a maintenance line nobody would keep up |

**nvim-treesitter, under Apache-2.0.** Not because the vocabulary is nicer — Helix's indent dialect is
smaller and is the one actually written down as a specification — but because it is the only source of
maintained files for all six of our languages under terms this repository already satisfies.

### The statement of modifications Apache-2.0 § 4(b) asks for

1. **The `; inherits:` chain is resolved by concatenation.** nvim-treesitter's loader reads
   `; inherits: ecma,jsx` at runtime and loads those files ahead of the language's own. This engine has no
   such mechanism, and adding one to read eighteen files would be machinery for a feature nobody asked
   for — so the inherited files are concatenated ahead of the language's own, in inheritance order, at
   vendoring time. Every file's header names exactly which upstream sources it was built from. No pattern
   is edited, removed or reordered.
2. **One pattern is added, at load rather than in the file.** Upstream's ECMAScript `locals.scm` captures
   `var` declarations, imports, functions and methods and **no parameters at all**, so every JavaScript
   argument resolves to nothing and renders as an ordinary local — which is the one distinction that
   family exists to draw. Three patterns are appended by `Queries.captureJavaScriptParameters`, marked in
   the query text as ours. The vendored resource is untouched.
3. **`css/locals.scm` is absent** because upstream ships none. CSS keeps the grammar's own colouring.

`@indent.align` is the one capture of the dialect that is read and ignored: it needs a *column* rather
than a level, which is not what `IndentationProvider` answers in. `TreeIndents` says so in its own header.
