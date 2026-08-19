# M13 — Documentation and names in production

Detail for the M13 row in `plan_syntax.md` §20. Everything the Quick Documentation popup shows is
correct in a dev environment and mostly absent in a shipped one, and the two halves of that fail for
opposite reasons.

## Status

| § | Item | State |
|---|---|---|
| 25.1 | Parameter names from the class file | **done** — `ClassFileParameterNames`, read through the analysis classpath and then the running loader (which is what reaches the JDK's runtime image); `JavaSignatures` falls back to it when the unit does not declare the method. `-parameters` on `core` and `language` is the other half and is asserted, not trusted |
| 25.2 | The header transform | **done** — `SourceHeaders`, a literal-aware scanner rather than a parse. Its consumer turned out to be 25.5 alone: 25.4 ships whole (see the revision below). 14 tests, two of which put a real compiler over the output |
| 25.3 | The provider chain | **done** — `SourceArchives.Archive` is now a seam with two implementations, and `discover` is the precedence rule written down: a named/fetched archive, the running JVM, any other JDK on the machine, `-sources.jar`s on the classpath, ours last. Minecraft's remains M12's mapping data |
| 25.4 | Bundling our own sources | **done** — 601 files under `assets/crystalgui/sources/` in `core.jar`, read by `SourceArchives.ResourceArchive`. **Measured: 5.53 MB of text, 1.84 MB in the jar**, and verified present in the built mod jar. **CrystalGraphics ships its own too** — 249 + 27 files, 775 KB, under `assets/crystalgraphics/sources/`: a namespace per project, because CG is used by mods with no CrystalGUI in the pack and a jar shipping an `assets/crystalgui/` directory to them claims a namespace it does not own. The reader needed no change for it, and neither does anybody else's: `BundledSources` **scans** the classpath for `assets/<namespace>/sources/` rather than consulting a list, so **a mod makes its own API quotable by shipping its sources and nothing else** — no registration, no entry in our source. It began as a two-entry constant, which works for exactly the two projects that can edit that file; a list a third party has to be added to is a list a third party cannot use. Measured: 232 ms cold / ~105 ms warm over 359 jars, once per classpath. It reaches there for free, since `:mc1710`'s shadowJar copies `core.jar` entry for entry — so this needed nothing from M12 after all. **`core` only**: a script may not name `com.crystalgui.language` at all (`ScriptPolicy.ALWAYS_REFUSED`), so shipping that module's sources would be documentation for types the sandbox refuses |
| 25.5 | The JDK, fetched rather than bundled | **done** — a three-step chain (`JdkSourceExtract`), and the licence decision is recorded below and in `THIRD-PARTY.md`: **we host nothing and the extract is derived on the user's machine.** Step two — any other JDK already installed — is the one that fires most often and needs no network at all |
| 25.6 | Rendering the doc body | **done for the hover**; the completion pane is the one consumer left, and needs a re-resolve rather than a line — see below. Otherwise: — `EcjOptions` enables doc-comment support, `JavaDocs` renders the node, `JavaSignatures.documentationOf` finds it here or in the attached source and **inherits it for an override**. 8 tests. Plain text; the styled version is still §24.1`s `CgMarkupParser` call |

### Revisions, recorded — what building 25.2, 25.4 and 25.5 changed in the plan

**§25.2 has one consumer, not two, and it is 25.5.** The section said the transform would be "used where
size or licence demands it, skipped where they do not" and then said our own sources are small enough to
ship whole. Both are right, and together they mean the build-time application to *our* tree — which the
section's title, *"one build step, every producer"*, promised — has no reason to exist. The transform runs
**at fetch time on the user's machine**, over the JDK archive, and nowhere else. That is not a downgrade:
it is what makes the licence position below hold, because the derivation happens where it is not
distribution.

**And therefore it is a scanner, not an application of JDT.** It has to run host-side, on a Minecraft
client, where JDT lives behind the engine band — reaching it would mean opening a band to strip a
download. A scanner also degrades better on exactly the input this meets: a `src.zip` for a JDK newer than
the running band is a file a parser rejects and a scanner copies.

**Bodies become `{}` rather than vanishing.** A concrete method with no body is a *parse* error and a unit
with one produces no bindings at all; a method with an empty body is at worst a missing return, which is
semantic, in a unit nothing ever compiles. The whole contract — *"the output is still valid Java"* —
turns on that distinction, and it is asserted by putting a real compiler over the result rather than by
reading it.

**The initializer half of the rule is not applied.** §25.2 paired the body cut with `JavaSignatures.isValue`
— keep a literal, drop an expression. Dropping one turns `static final int X = compute();` into an
uninitialised final, which *is* a definite-assignment error where the body cut produces none; and the
popup **quotes the initializer** (that is what `appendInitializerExpression` is for), so dropping it would
degrade the one output the transform exists to feed. Initializers are also not where the bytes are.

**§25.5's chain had three steps and the plan only specified the first and the third.** *"The player has a
JDK → read their `src.zip`"* was written as though `java.home` answered it. It does not: a modded player
launches on a jlink'd JRE — Mojang's launcher ships one — while frequently having installed a full JDK
because a pack guide told them to, and that `src.zip` is sitting on their disk unread. `JAVA_HOME`, the
conventional install roots and the toolchain caches are now candidates. **This is the step that fires most
often, costs nothing, needs no network and raises no licence question at all**, and it was one line in a
numbered list.

**There is no downloadable `src.zip`, so the fetch is of the upstream source archive.** `src.zip` is a file
*inside* a JDK installation and nobody publishes it alone; what is publishable is the OpenJDK source tree,
as a gzipped tar. Hence `TarArchive` — eighty lines of a thirty-year-old format, against the alternative of
fetching a 190 MB JDK to read one entry out of it. What lands in the cache is a flat zip in exactly the
shape a real `src.zip` has, which is the risk control: **every developer with a JDK exercises the reader
daily and almost nobody exercises the producer**, so the producer's output is made indistinguishable from
what the common path already reads.

**The licence decision, made explicit.** OpenJDK source is GPLv2 with Classpath Exception; the exception
covers linking rather than redistributing a modified extract, and this repository has no LICENSE file, so
"GPL-compatible" is not established. Two structural consequences rather than a note: **we host nothing** —
the archive is fetched by the user's own client from whoever publishes the JDK, the same position the MCP
mapping data is in — and **the extract is derived on that machine, for that machine**, which is not
distribution. Building it at *our* build time and shipping it would have been redistribution of a modified
GPL work, which is precisely what §25.5 refused. It follows that the fetch is **never automatic**: it is a
command somebody runs, which is also what IntelliJ does and what `plan_m11.md` §24.1 already named.

### The two findings this milestone is built on, both measured

**Parameter names survive compilation.** `java.util.ArrayList.add` carries `e` in its
`LocalVariableTable`; `java.lang.String.format` carries `format` and `args`; our own `core.jar` carries
`newContainerKind` today with no build change, because Gradle passes `-g` by default. So the headline
benefit of source attachment — real parameter names — is reachable in production **on a JRE, with
nothing shipped**. This was assumed impossible for a full session on the grounds that `src.zip` is a JDK
artefact, which is true and was the wrong place to be looking.

**Javadoc does not survive compilation, and there is no attribute that carries it.** So prose is
categorically different: every route to it is "ship something or fetch something", and no amount of
cleverness with bytecode changes that. The two halves of this milestone are therefore priced completely
differently and should not be scheduled as one thing.

### What is NOT a problem here

`net.minecraft`. M12 already ships SRG↔MCP mapping data and names `params.csv` for parameter names in
its own row — and Parchment carries **javadoc** alongside the names for 1.20.x. So Minecraft's names and
docs arrive with work that is already scheduled for a different reason. The only open question there is
licence terms, which differ sharply between MCP and Parchment and are M12's to settle.

---

## 25.1 Parameter names from the class file

**No shipped artifact, no build change, and the insertion point already exists.**
`JavaSignatures.parameterNames(IMethodBinding)` returns null for exactly the symbols this would fill —
it was written to answer only for the unit being analysed, and the classpath case has been falling
through to types-only ever since.

Read the declaring class's bytes and take names from `MethodParameters` if present, else from
`LocalVariableTable`. **ASM is already an `api` dependency of `language/`** (`asm`, `asm-commons`,
`asm-tree`), so this adds nothing to the classpath.

| | concrete methods | abstract / interface |
|---|---|---|
| JDK | ✅ verified (`ArrayList.add` → `e`, `String.format` → `format`, `args`) | ❌ `java.util.List` has neither attribute — verified, zero `MethodParameters` in it |
| Ours | ✅ verified in `core.jar` today | ✅ — `-parameters` is on `core` and `language`, and `Resolver.resolveAt` names `offset`/`answer` in a test that fails without it |
| Mods and libraries built normally | ✅ | ❌ |
| Obfuscated MC, ProGuard'd mods | ❌ stripped | ❌ |

**An abstract method has no `Code` attribute, so it has no `LocalVariableTable`.** That is the whole
shape of the gap, and it matters more than the table suggests: idiomatic Java declares variables as the
interface, so `List.add`, `Map.put`, `Collection.stream` and `Comparator.compare` are exactly the hovers
a reader performs most.

**✅ Built.** The reader is `ClassFileParameterNames` in `language.java.classpath`; the insertion point
was where the plan said it was. Three notes from building it:

- **All three traps were real and all three were measured on the running JDK before being coded against.**
  `String.format` is the static case and its slot 0 is `format`; `ArrayList.add(int, Object)` reports
  `[this, index, element, s, elementData]`, so the trailing locals are not parameters; and a parameter
  after a `long` sits at slot 3.
- **Ambiguity answers null.** Two same-arity overloads this cannot separate give types-only — which is
  exactly what the caller did before — because a signature wearing another overload's names reads as
  authoritative.
- **It inverted an existing test, and the inversion is the point.**
  `aSymbolWithNoAttachedSourceIsAssembledInstead` used the *missing* parameter name as "the one
  difference visible from outside" between the assembled and quoted paths. That premise is the one this
  section corrects, so it is no longer a boundary marker; what still separates the two is the author's
  layout and, later, their javadoc. Its twin inverted the same way when `AttachedSources` landed.

**`-parameters` on `core`, `platform` and `language` is the other half, and only for our own code.**
`MethodParameters` needs no `Code` attribute, so it is the one mechanism that names an *interface*
method's parameters. For an SPI-heavy engine that is most of the interesting surface — `CgUiDrawable.draw`,
`SourceAnalyzer.analyze`, every service seam. One compiler flag, roughly 1% class-file growth.

### Three traps, each silent

- **Slot 0 is `this` for an instance method and the FIRST PARAMETER for a static one.** `String.format`
  is the static case and its slot 0 is `format`. Getting this wrong shifts every name by one and
  produces a signature that is plausible and wrong.
- **`long` and `double` occupy two slots each.** A method taking `(long, String)` has its second
  parameter at slot 3, not 2.
- **Matching the right overload needs the erased descriptor**, and `IMethodBinding.getKey()` already
  contains one — likely cheaper and more reliable than rebuilding a descriptor from `ITypeBinding`s,
  where generics erasure, inner-class synthetic parameters and varargs each have an edge case.

---

## 25.2 The header transform — one build step, every producer

**Strip method bodies; keep declarations, javadoc, and literal field initializers.**

The output is **still valid Java source**, which is the entire point: it flows through `SourceArchives`
and `AttachedSources` unchanged. No new format, no new parser, no second reader, nothing downstream
knows it happened.

And both rules already exist in the engine:

| Rule | Already implemented as |
|---|---|
| cut at the body brace | `JavaSignatures.quotedHeaderOf` |
| keep a literal initializer, drop an expression | `JavaSignatures.isValue` |

So this is a build-time application of decisions the engine has already made, not new judgement. That is
what makes it safe to apply to somebody else's source tree.

**Used where size or licence demands it, skipped where they do not.** Our own sources are small enough to
ship whole, and shipping them whole is *better* — full bodies mean the quoted declaration keeps the
author's real layout. The transform is what makes the JDK viable at all.

---

## 25.3 The provider chain — four producers, one seam

These are not four features. They are four inputs to the question `SourceArchives` already asks:
*where is the source for this type?*

| Producer | Route | Ships? |
|---|---|---|
| Ours | bundled in the mod jar, full sources | 2.8 MB |
| JDK | the player's own `src.zip` if present, else a fetched extract | nothing |
| Minecraft | Parchment/MCP mapping data | M12's, already scheduled |
| Third-party | whatever `-sources.jar` is beside a jar | nothing |

Precedence: **a real file on disk beats a bundled snapshot.** In a dev workspace the working tree and any
`-sources.jar` should win, with the shipped copy as the production fallback — so the resource-backed
producer goes *last* in `SourceArchives.discover`. Namespaces do not collide in practice, but the default
should still be that the more specific artefact wins.

---

## 25.4 Bundling our own sources

**Loose `.java` entries, not a zip inside the jar:**

```
crystalgui-1.0.jar
└── assets/crystalgui/sources/
    ├── com/crystalgui/text/lang/SymbolInfo.java
    └── … 888 files, ~2.8 MB
```

**Why not a nested `sources.zip`.** `ZipFile` requires a real file on disk and cannot open an archive
nested inside another. `ZipInputStream` works over `getResourceAsStream` but is strictly *sequential* —
no lookup by name — so each hover would decompress entries until it found the one it wanted. The only way
out is extracting to disk on first run, which buys a temp file, a write, and a staleness question at
every mod update.

Loose entries give random access **for free**, because the JVM indexed the jar's central directory when
the loader opened it. One `getResourceAsStream`, no scan, no extraction, no temp file.

**The new `Archive` kind is simpler than the existing one.** The file-backed archive has to open a zip,
walk every entry, build a package-path index, and do it lazily precisely because that is expensive. A
resource-backed one needs none of it: the lookup either answers or it does not, because the classloader
already did the indexing.

**Packaging is blocked on M12.** The Gradle side is one block — `from(sourceSets.main.allJava) { into(…) }` —
but it belongs on the **shadowJar** of each loader module, since that is what actually ships and every
bundled module must be named there explicitly rather than inherited. Both loader modules are commented out
of the build today. The *read path* does not have to wait: putting the sources into `core.jar` exercises
the identical code path in the harness, so it can be built, tested and pinned now and simply gains a second
producer later.

---

## 25.5 The JDK — fetched, not bundled

**Not a size problem.** A header+doc extract of the packages a script author actually touches
(`java.lang`, `util`, `io`, `nio`, `time`, `text`, `math`, `net` — 1256 files, 23.9 MB of source) zips to
**≤ 4.2 MB**, against 42.9 MB for the whole `src.zip`. And that 4.2 MB is an *upper bound*: the measuring
strip kept any line ending in `;`, which means it kept body statements too.

Against a jar that already carries **~42 MB of engines** — bands 8, 11 and 17 at 12, 13 and 17 MB, all
three shipped because the band is chosen at runtime from the host's Java version — 4.2 MB is under 10%.

**It is a licence problem.** OpenJDK source is GPLv2 with Classpath Exception. The CE covers *linking*,
not redistributing a derived extract, and a body-stripped transform is arguably a derivative work.
There is no LICENSE file in this repository at all today, so "GPL-compatible" is not established.

**So fetch it.** `plan_m11.md` §24.1 already lists **"Download documentation" for a library with no
sources attached** in the popup's kebab menu — the affordance exists in the design and is IntelliJ's own
model. Nothing GPL-derived in the jar, nothing added to its size, and it works offline after one fetch.

The chain degrades cleanly:

1. the player has a JDK → read their `src.zip` directly, costs nothing
2. else → the downloaded extract, cached under the game directory
3. else → bytecode parameter names for concrete methods (§25.1), and no prose

---

## 25.6 Rendering the body

`SymbolInfo.documentation` has **never been populated** — by any engine, in dev or production. The popup
is a declaration and a location and nothing else. `plan_m11.md` §24.1 called this out and deliberately
shipped without it: *"the popup ships useful on day one and gets a body when the ECJ side learns to read
`Javadoc` nodes off the AST."* This is that.

**ECJ's doc-comment support is off today.** `BodyDeclaration.getJavadoc()` answers null without
`org.eclipse.jdt.core.compiler.doc.comment.support = enabled`, and `JavaSignatures.quotedFragment` already
records the consequence in its own comment — it skips leading comments by *scanning the text* rather than
asking the API, because the node covered the comment while the accessor denied it existed. One line in
`EcjOptions`.

**`{@inheritDoc}` is the trap that would make a first version look broken.** An overriding method usually
carries `@Override` and no doc of its own — `Message.toString()` in the shipped fixture is exactly this
shape. Without walking to the supertype's declaration for the text, a large fraction of methods render an
empty body, which reads as the feature not working rather than as the method having no doc.

**✅ Built — for the hover. One consumer is still unfilled and now has a home rather than a comment.**
The completion popup's documentation pane shows nothing, because the completion path builds its symbols
from bindings without asking for a comment. `JavaCompletionProvider.resolveItem` is exactly where it
would be filled — it is the lazy hook and the pane is the one place a doc is worth the lookup — but it
receives a `CompletionItem` rather than a binding, so filling it means re-resolving the declaring type.
Real work rather than a line, and it belongs to this section rather than to a javadoc nobody owns.

**Javadoc is not plain text**: `{@link}`, `{@code}`, `@param`, `@return`, embedded HTML, entities.
§24.1 already picked the tool and said why — `CgMarkupParser` is right for the *body* and wrong for the
*signature*, because it produces `CgStyledText` directly and so enters the pipeline downstream of the
cascade, where its colours are baked and a scheme switch cannot reach them. That reasoning is unchanged:
the body is prose and may be baked; the signature is code and may not.

---

## Sizes, measured

| | |
|---|---|
| engines already shipped (bands 8 + 11 + 17) | **42 MB** |
| our whole source tree (888 files, 8.2 MB raw) | **2.8 MB** zipped |
| **what actually shipped** — `core` alone, 601 files, 5.53 MB raw | **1.84 MB** in the jar |
| javadoc as a share of our own source | **41%** — unusually high, so the payoff per shipped MB is too |
| JDK header+doc extract, 8 packages, 1256 files | **≤ 4.2 MB** zipped (upper bound) |
| full JDK `src.zip`, for contrast | 42.9 MB |

---

## Exit criteria

- ✅ Hovering a **concrete** classpath method shows its real parameter names with **no source attached** —
  asserted with `src.zip` deliberately out of reach, since that is the production shape and a dev run
  cannot tell the two apart.
- ✅ Hovering one of **our own interface** methods shows its parameter names, which only `-parameters` can
  deliver.
- ✅ The header transform's output **parses and quotes identically** to the source it came from, for a
  fixture containing a record, a sealed interface and a generic method with bounds. Asked of the
  compiler — `Analysis.optionalProblemsAnalysed()` is the published signal for "this file parsed", and is
  the one answer that cannot be confused with "it parsed and then resolved badly", which a stripped unit
  legitimately does. The record and sealed half is gated on a band whose JDT can read that syntax.
- ✅ Our sources resolve **out of the mod jar** through the same `SourceArchives` chain, with a real
  `-sources.jar` taking precedence over the bundled copy where both exist. The precedence is asserted as
  an ordering of `discover`, and the packaging is asserted against the **running classloader** rather than
  a fixture — `tasks.jar` is one line nothing refers to, and a hover cannot tell "no source shipped" from
  "no source found".
- ✅ A javadoc body renders for a symbol with a doc comment, and for an `@Override` with none via
  `{@inheritDoc}`.
- ✅ The JDK producer is absent from the jar; with it fetched, `List.add` shows both its parameter name and
  its prose. **Verified in a real client, against the real upstream**: the Adoptium `sources` artifact for
  Java 21 downloaded, stripped and installed to `crystalgui-cache/jdk-sources/jdk-21-sources.zip` —
  **4.33 MB, 1,178 entries**, `java/util/List.java` carrying its `@param` prose and `boolean add(` with the
  bodies gone. That closes the one caveat this row carried: nothing in the build reaches the network, so
  the URL's shape had been taken from Adoptium's published API rather than from a request anybody had made.
  Somebody has now made it.

> **The real tree is now a test, and it is there because it earned its place.** `TarArchive.next()` skipped
> `padding(remaining)` — correct until a caller reads an entry, after which `remaining` is zero, so the
> content's 512-byte padding stayed in the stream and every later header landed mid-block. Against real
> data it produced **one file out of 14,212 and threw nothing**. Every hand-written fixture passed against
> it, *for the wrong reason*: everything after the first entry turned to garbage and yielded nothing, which
> happened to equal the expected count. `theProducerRunsOverARealJdkSourceTree` re-tars a couple of hundred
> entries out of whatever `src.zip` the machine has, and `aFileReadInFullDoesNotDesynchroniseTheStream`
> pins the specific arithmetic. **Real data has the property a fixture is built without: nobody chose what
> is in it.**

## Order

**25.1 first, alone.** It needs no artifact, no build change, no licence decision and no packaging, and it
delivers the single most visible production improvement on its own — a hover that names its arguments.
Everything else in this milestone is gated on a decision; this is gated on nothing.

Then **25.6's one-line flag plus 25.2**, because together they make our own sources *worth* shipping —
without doc-comment support there is no prose to ship, and without the transform there is no producer for
anything but us.

Then **25.4**, which can be built and tested against `core.jar` today and only needs M12 for the real
packaging. **25.5 last**, because it is the only part that requires a licence decision and a network
path, and because §25.1 will already have taken most of the sting out of a source-less JDK.
