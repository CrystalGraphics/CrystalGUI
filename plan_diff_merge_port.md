# Porting the diff and merge stack — IntelliJ and VS Code

**Researched 2026-08-22, from the sources rather than from articles about them.** Every file named here was
read; the section on each reference states what it actually does, not what it is reputed to do.

The instruction was *"ideally a 1:1 port, such an algorithm has been perfected long ago"*. That is right for
the **algorithms** and only partly right for the **views** — see [What cannot be 1:1](#what-cannot-be-11).

---

## 0. Licences — load-bearing, and the two are not the same

| Source | Licence | What it permits here |
|---|---|---|
| **VS Code / Monaco** (`microsoft/vscode`) | **MIT** | Port the code. Attribute in the class javadoc naming the source file. Already the repo's standing rule. |
| **IntelliJ Platform** (`JetBrains/intellij-community`) | **Apache 2.0** | Port the code — **and Apache 2.0 asks for more than MIT does**: the licence text must travel with the distribution, any `NOTICE` must be reproduced, and **modified files must carry a statement of modification**. |

Every file read for this plan carries `Copyright 2000-2024 JetBrains s.r.o. … Apache 2.0`.

> **The Apache 2.0 obligations are not the MIT ones and cannot be met by the same gesture.** The repo already
> ships IntelliJ Platform *icons* under Apache 2.0 through `THIRD-PARTY.md` and
> `ui/icons/ATTRIBUTION.md`, so the machinery exists — but § 4(b) ("carry prominent notices stating that You
> changed the files") applies to *ported source* in a way it does not to an unmodified icon. A port is a
> derivative work and will be modified by definition, so **every ported class states in its javadoc that it
> is a modified port, of which file, from which project, under which licence.** That is a one-line
> obligation per class and is not optional.

Neither reference is GPL, so unlike Zed both may be read *and* copied.

---

## 1. What IntelliJ actually does — merge logic

Files: `platform/util/diff/src/com/intellij/diff/comparison/` — `ByLineRt.kt`, `ByWordRt.kt`, `ByCharRt.kt`,
`ComparisonMergeUtil.kt`, `MergeResolveUtil.kt`, `ChunkOptimizer.kt`, `TrimUtil.kt`, `ComparisonPolicy.kt`.

### The three-way is the same shape we already have

`ByLineRt.doCompare(lines1, lines2, lines3, …)` — **`lines2` is the base** — does exactly two two-way diffs
against it and merges the results. So the architecture already in `ThreeWayMerge` is not wrong; everything
below is quality on top of it.

### Two-step comparison, and it is the interesting part

```
iwLines = convertMode(lines, IGNORE_WHITESPACES)   // whitespace-insensitive view
iwChanges = compareSmart(iwLines1, iwLines2)       // rough pass, cheap and stable
iwChanges = optimizeLineChunks(...)                // ChunkOptimizer
result    = correctChangesSecondStep(...)          // recover exact-equality distinctions
```

**The rough pass runs whitespace-insensitively even when the policy is DEFAULT**, and the exact answer is
recovered afterwards. That is deliberate: reindentation is the commonest edit in real code and a
whitespace-*sensitive* first pass anchors on lines that only moved sideways, producing a diff nobody can
read. `correctChangesSecondStep` then splits the rough "equal" spans back apart where the text genuinely
differs, and `getBestMatchingAlignment` picks how.

`compareSmart` additionally splits out **big lines** (`getBigLines`) so one enormous minified line cannot make
the comparison quadratic.

### `ComparisonMergeUtil.buildSimple` — intersect the UNCHANGED ranges

This is the piece worth taking first, because it is **better than what we have and strictly simpler**:

```
unchanged1 = fragments1.unchanged()   // base == left
unchanged2 = fragments2.unchanged()   // base == right
while both have next:
    intersect the two ranges in BASE coordinates
    markEqual(startLeft, startBase, startRight, endLeft, endBase, endRight)
    advance whichever ends first
```

Everything *not* covered by an intersection is a changed region, emitted by `ChangeBuilder.finish`.

> **Regions come out overlap-free by construction**, because they are the gaps between agreed spans rather
> than a union of changed spans. Our current `ThreeWayMerge.of` groups *changed* hunks and needs an explicit
> `<=` boundary rule to avoid overlap — a rule that took a mutation test to pin. Intersecting the unchanged
> side removes the failure mode instead of defending against it.

### `MergeResolveUtil` — "magic resolve"

A line-level conflict is re-diffed at **word and character** granularity; if the two sides' real edits do not
touch, the conflict resolves itself. Their own documented rules:

- **insertion + insertion → unresolvable** (the order of two inserted blocks is unknowable, and sorting them
  by length or alphabetically is meaningless)
- **deletion + insertion → apply both**
- **deletion + deletion → merge the deleted intervals**
- **a modification is an insertion plus a deletion** and resolves accordingly

Two strengths: `tryResolve` (conservative) and `tryGreedyResolve`, chosen by
`DiffConfig.USE_GREEDY_MERGE_MAGIC_RESOLVE`. The greedy one explicitly trades a higher chance of a wrong
resolve for a higher chance of any resolve, **on the stated grounds that the user verifies the result and can
undo it**.

### `ComparisonPolicy`

`DEFAULT` / `TRIM_WHITESPACE` / `IGNORE_WHITESPACES`, threaded through every entry point. Not a view toggle —
it changes what "equal" means, so it changes the regions themselves.

---

## 2. What VS Code actually does — diff logic

Files: `src/vs/editor/common/diff/` — `defaultLinesDiffComputer/` (`defaultLinesDiffComputer.ts`,
`heuristicSequenceOptimizations.ts`, `computeMovedLines.ts`, `linesSliceCharSequence.ts`,
`algorithms/{myersDiffAlgorithm,dynamicProgrammingDiffing,diffAlgorithm}.ts`), `rangeMapping.ts`,
`legacyLinesDiffComputer.ts`.

### Two algorithms, chosen by size

Lines are hashed (trimmed) into perfect hashes, then:

- **small inputs → `DynamicProgrammingDiffing`** — optimal, quadratic, affordable below the threshold
- **large inputs → `MyersDiffAlgorithm`** — with a real `DateTimeout`, and a documented degraded answer when
  it expires rather than a hang

### Then a stack of named heuristics

From `heuristicSequenceOptimizations.ts`, all of which exist because a minimal diff is not a readable one:

| Function | What it fixes |
|---|---|
| `joinSequenceDiffsByShifting` | `import {Baz, Bar}` → `{Baz, Bar, Foo}` computing as two edits instead of one |
| `shiftSequenceDiffs` / `shiftDiffToBetterPosition` | slides a diff to land on a word or line boundary |
| `removeShortMatches` | drops matches too small to be meaningful |
| `extendDiffsToEntireWordIfAppropriate` | grows a mid-word edit to the whole word |
| `removeVeryShortMatchingLinesBetweenDiffs` | merges two diffs separated by one trivial line |
| `removeVeryShortMatchingTextBetweenLongDiffs` | the same, at character level |

### Character-level refinement, per block

`refineDiff` re-runs the algorithm over a `LinesSliceCharSequence` for each changed block, producing
`innerChanges: RangeMapping[]` — **this is the word-level marks inside a changed line**, and it is the same
mechanism, not a separate feature.

### Moved-block detection

`computeMovedLines` finds blocks that moved rather than changed, and each move is then refined like any other
change. Nothing in IntelliJ's line comparison corresponds to this.

### The data model

`LineRangeMapping` → `DetailedLineRangeMapping` (adds `innerChanges`) → `MovedText`. Clean, immutable, and
the whole view is driven from it.

---

## 3. What VS Code actually does — merge model

Files: `src/vs/workbench/contrib/mergeEditor/browser/model/` — `modifiedBaseRange.ts`, `mergeEditorModel.ts`,
`editing.ts`, `textModelDiffs.ts`.

`ModifiedBaseRange` is the direct analogue of our `Region`, and carries more:

| Member | Why it matters |
|---|---|
| `input1Diffs` / `input2Diffs` | the **detailed** mappings, so a region knows its inner char ranges |
| `isConflicting` | both sides touched it |
| `canBeCombined` | whether "take both" is even meaningful here |
| `isOrderRelevant` | whether 1-then-2 differs from 2-then-1 — the UI can *say so* |
| `smartCombineInputs(firstInput)` | interleaves the two sides' diffs **by base position** |
| `dumbCombineInputs(firstInput)` | plain concatenation |
| `getEditForBase(state)` | returns a `LineRangeEdit` **against the base** plus the effective state |

### The state model is a sealed hierarchy, and one of its cases is the thing I built badly

`ModifiedBaseRangeStateKind`: **Base · Input1 · Input2 · Both(firstInput, smartCombination) · Unrecognized**.

> **`Unrecognized` is the hand-edit case, per region.** Our `MergeView` has a single global `handEdited`
> latch that disables every control the moment anything is typed. VS Code marks only the region the edit
> landed in as no longer corresponding to any choice, and the rest of the merge keeps working. That is
> strictly better and it is the reason `Region.acceptCustom` was left on our engine — this is what it is for.

> **`Both` carries `firstInput` and `smartCombination`, so "take both" is four answers, not one.** Ours is
> `dumbCombineInputs(1)` and nothing else.

> **Edits are computed against the BASE and applied**, rather than the result being assembled by walking
> regions. That inverts our `mergedLines()` and is what makes a hand-edited result and a state-driven result
> the same kind of object.

---

## 4. The visual layer

### IntelliJ

| Piece | Class |
|---|---|
| Side-by-side / three-side / merge viewers | `SimpleDiffViewer`, `ThreesideTextDiffViewer`, `MergeThreesideViewer` |
| **The slanted ribbons** | `DiffDividerDrawUtil.paintPolygons` → `DividerPolygon`; `paintSeparators` → `DividerSeparator` |
| Line bands, inline word marks, gutter marks | `DiffDrawUtil`, `TextDiffType`, `TextDiffTypeFactory` |
| Collapsed unchanged regions | `FoldingModelSupport` |
| Pane scroll alignment | `SyncScrollSupport` |
| Apply / revert chevrons | `DiffGutterOperation`, `DiffGutterRenderer`, `DiffLineMarkerRenderer` |

`DividerPaintable.Handler` has `process`, `processResolvable`, `processExcludable` **and `processAligned`** —
so IntelliJ can align too; the polygon-with-natural-positions is its *default*, not its only mode.

### VS Code

| Piece | File |
|---|---|
| **Alignment by inserted blank space** | `components/diffEditorViewZones/` |
| Collapsed unchanged regions | `features/hideUnchangedRegionsFeature.ts` |
| Moved-block connector lines | `features/movedBlocksLinesFeature.ts` |
| **Scrollbar-track change marks** | `features/overviewRulerFeature.ts` |
| Apply / revert buttons | `features/revertButtonsFeature.ts` |
| Gutter actions | `features/gutterFeature.ts` |
| Decorations | `components/diffEditorDecorations.ts` |
| Draggable divider | `components/diffEditorSash.ts` |
| Screen-reader view | `components/accessibleDiffViewer.ts` |

### Where they genuinely disagree: alignment

**VS Code inserts view zones — real blank space — so corresponding lines sit at the same height.**
**IntelliJ, by default, does not: the panes run at their natural positions and slanted polygons carry the eye
across the offset.** That is the difference the reference screenshot shows, and it is a real design fork, not
an implementation detail:

- *Padding* makes correspondence trivially readable and makes the two gutters disagree with the file — the
  line numbers on screen are still correct, but the blank rows belong to no line at all.
- *Polygons* keep every row a real row and pay for it with a connector the eye has to follow.

An earlier note in `plan_phase6.md` said IntelliJ "does not pad" — correct for the default, and incomplete:
`processAligned` exists. **Both should be available; the default should be polygons**, because that is the
shape that was asked for.

---

## 5. What cannot be 1:1

Honest, because the ambition was stated as 1:1:

- **The algorithms can be** — `ByLineRt`, `ComparisonMergeUtil`, `MergeResolveUtil`, `ChunkOptimizer`,
  `myersDiffAlgorithm`, `dynamicProgrammingDiffing`, `heuristicSequenceOptimizations`, `computeMovedLines`,
  `ModifiedBaseRange` and its state hierarchy are all **pure functions over text and ranges**. They name no
  UI type. These port essentially line for line.
- **The views cannot be.** They are written against Monaco (`IViewZone`, `IModelDeltaDecoration`,
  `IObservable`, `EditorGutter`) and the IntelliJ editor (`Editor`, `MarkupModel`, `FoldingModel`,
  `RangeHighlighter`, `Graphics2D`). What ports is the **decomposition and the render protocol** — which is
  what this repo already did for `TextEditor`'s ten view parts, and is exactly the rule in AGENTS.md:
  *"Porting the algorithms without the boundaries keeps the algorithms and throws away the testability."*
- **VS Code's observables** (`IObservable`/`derived`/`autorun`) drive its whole diff editor. We have
  `Property`/`Signal`, which cover the same ground; the port is a re-expression, not a copy.

---

## 6. The plan

Ordered so each stage is independently verifiable, and so the riskiest replacement happens while the test net
is smallest.

### Stage 1 — the merge core, replaced · **items 1–3 done 2026-08-22**

1. ✅ **`FairMergeBuilder`** → `MergeRanges`, over new `DiffRange` / `DiffIterable` / `MergeRange`. The
   changed-hunk grouping in `ThreeWayMerge.of` is gone; regions are now the gaps between agreed spans, so
   **overlap is structurally impossible rather than defended against**. The `<=` boundary rule that a
   mutation test had to pin no longer exists.
2. ✅ **`ComparisonPolicy`** — DEFAULT / TRIM_WHITESPACES / IGNORE_WHITESPACES (JetBrains' spelling is
   plural), threaded through `DiffIterable.of` and `ThreeWayMerge.of`. Applied as a **comparison key per
   line** (`ByLineRt.convertMode`'s trick) rather than re-decided per comparison.
3. ⚠️ **Two-step compare** — **written (`TwoStepCompare`), deliberately NOT wired in. Moved to Stage 2.**
   Ported faithfully and then measured before landing, which is what caught it: upstream runs this
   correction as the *third* step of `compareSmart` (Myers) → `optimizeLineChunks` → correct. On top of the
   histogram differ with no chunk optimiser in front, **it makes the answer worse**.

   Over 4,000 random reindent-heavy pairs the partition differs from a single exact pass in **737** cases,
   and in **612 of those the two-step matched FEWER lines** — small separate changes lump into one block.
   The correction can only keep pairs the rough pass aligned at the same offset, so wherever rough and
   exact anchoring disagree, exactly-equal lines the single pass found are lost. `ChunkOptimizer` is what
   repairs chunk boundaries *before* the correction sees them.

   > **Porting a step without its prerequisites is not a partial port, it is a regression.** The class is
   > complete and correct for what it does; it is the *chain* that is missing two links. Landing it on the
   > measurement's strength would have been a port that made the product worse while looking like progress
   > — and no test in the suite would have failed, because the suite has no opinion about diff quality.
4. ✅ The existing 16 `ThreeWayMergeTest` cases carried over **unchanged** and caught nothing — which is the
   result wanted from a replacement under a net. Mutating the new intersection (`max` → `min`) kills 8 of
   them, so the new path is genuinely exercised rather than incidentally green.

**New:** `DiffIterableTest` — 11 cases. The load-bearing one is the *partition* property (changed +
unchanged must cover both texts exactly once, in order, over 300 random pairs), because that is what makes
non-overlapping regions free. Plus the claim `ComparisonPolicy` exists for: a reindent on one side against
an edit on the other **conflicts under DEFAULT and auto-merges under IGNORE_WHITESPACES**, asserted as a
difference in conflict count rather than in appearance.

### Stage 2 — the diff algorithm, replaced · **done 2026-08-22**

5. ✅ **`MyersDiffAlgorithm` + `DynamicProgrammingDiffing`** → `MyersDiff`, `DynamicProgrammingDiff`, over
   `Sequence` / `LineSequence` / `DiffTimeout`. Threshold 1700 combined lines, upstream's. The histogram
   implementation is gone; `LineDiff` survives as a facade, so every existing consumer and test moved onto
   the new engine at once rather than needing a parallel suite.
6. ✅ **The equality score** — an exact line is worth `1 + log(1 + length)`, a blank one `0.1`, a
   normalisation-only match `0.99`. Three numbers encoding readability judgements edit distance cannot.
7. ✅ **The shifting heuristics** — `SequenceOptimizations.optimize` (join-by-shifting ×2, then
   shift-to-better-boundary) and `joinShortMatchesBetween`. The two join passes use **different
   equalities**: leftward on the hashed element, rightward on `stronglyEqual`, so a slide can never land on
   a merely-whitespace-equal line.
8. ✅ **The whitespace-blind rough pass** — both references agree on it, and it nearly went in wrong. The
   algorithms hash the **trimmed** line whatever the caller asked for, or a reindent destroys every anchor.
   `LinesDiff.restoreExactness` then splits the agreed spans wherever the lines are not really equal.

   > **Without that split the rough pass is a lie**, and not cosmetically: a reindented line comes back
   > inside an *unchanged* span, so a three-way merge concludes nobody touched those lines and silently
   > discards a reindent that competed with a real edit. `aReindentIsReportedAsChangedUnderTheDefaultPolicy`
   > is the pin.

9. ❌ **`ChunkOptimizer` not ported, and `TwoStepCompare` deleted.** `restoreExactness` reaches the same
   goal by **splitting only, never re-pairing** — so unlike the fuller correction it cannot lose a match,
   which was exactly the 612-of-737 regression measured in Stage 1. IntelliJ needs the re-pairing because
   its rough pass throws alignment away; VS Code does not, because `stronglyEqual` stops the heuristics
   misaligning in the first place. Carrying both lineages is the "do not carry both blindly" case this plan
   warned about, so the known-worse implementation is **deleted rather than parked** — unwired code that has
   been measured as worse is a liability, not an option.

**New:** `LinesDiffTest` — 9 cases. Validity as a property over 400 random pairs (a diff must pair the texts
so the gaps match on both sides) plus the end-to-end round trip; behaviour on written cases, because the
whole reason for porting these is judgements a validity check cannot see. A timeout degrades to "everything
changed" and says so, rather than hanging.

### Stage 3 — granularity · **done 2026-08-22**

10. OK **`refineDiff` / `LinesSliceCharSequence`** -> `CharSequenceSlice`, `InnerRange`, `DetailedDiff`,
    `LinesDiff.computeDetailed`. The word-level marks inside a changed line, produced by re-running the
    **same** algorithms over the block's characters — which is why a view's word marks and its line bands
    can never disagree.

    The boundary-score table is the feature: a separator scores **30**, a space 3, inside a word **0**, a
    category change 10 (plus one for lowercase->uppercase, which is what lands a change on the hump of
    `observableValue`), and a line break before the change **150**, dominating everything.

    > **A pure insertion gets no inner ranges.** There is no counterpart text, so the only range available
    > would be the whole block restated — which a view draws as a word mark over every character of an
    > added line, on top of the band already there.

11. OK **Magic resolve** -> `MagicResolve`, hooked to `Region.suggestedResolution()` and
    `ThreeWayMerge.resolveConflictsAutomatically()`. **Not ported line-for-line, and deliberately:** upstream
    hand-walks a three-way character comparison with its own append/conflict helpers, but magic resolve
    *is* the three-way merge run again on characters — so this reuses `MergeRanges` over `CharSequenceSlice`.
    Implementing it a second way would be two chances to be wrong about the same thing.

    Upstream's rules then fall out rather than being coded: insertion+insertion is unresolvable (the order
    is unknowable), deletion+insertion applies both, deletion+deletion merges the spans.

    > **Offered, never imposed.** A conflict resolved this way keeps `Kind.CONFLICT`, so a view can show
    > that a decision was made on the user's behalf. Downgrading it to an auto-merge would hide the one
    > place this stack guesses.

12. NO **`ByWordRt` / `ByCharRt` not ported.** They existed on the list to serve magic resolve; the
    character path above serves it, and a second word-level differ would be a second thing to keep in step.

### Stage 4 — the state model · **done 2026-08-22**

13. OK **`ModifiedBaseRangeState`** -> `RegionState`, a sealed interface over records:
    `Base` / `Mine` / `Theirs` / `Both(mineFirst, smart)` / `Custom` / `Unrecognized`. Replaces the
    `Resolution` enum outright.

    Two things the enum could not say. **"Take both" is four answers**, not one — which side leads, and
    whether the two edits are interleaved or concatenated; as constants that is a combinatorial fan-out, as
    a record it is one case. And **`Unrecognized` is a state rather than an absence**.

14. OK **`smartCombineInputs` / `dumbCombineInputs`** -> `Region.combine`, plus `canBeCombined()` and
    `isOrderRelevant()` so a view can offer an order choice only when it changes something.

    > A subtlety found while writing the fixture: **a conflict region can never contain a line both sides
    > left alone**, because `MergeRanges` splits wherever the two diffs agree. So the concatenation problem
    > is not "the untouched middle appears twice" but the broader one — gluing two whole sides together
    > restates every line of the region twice. Measured on the fixture: **6 lines concatenated, 3
    > interleaved.**

15. OK **Per-region hand edits** -> `ThreeWayMerge.attributeHandEdit` + `resultRanges()`. The global
    `handEdited` latch in `MergeView` is gone in substance: an edit is located by diffing what the merge
    *expected* to produce against what is on screen — so paste and undo count exactly as typing does — and
    charged to the regions it overlapped. Every untouched region keeps working.

### Stage 5 - the view · **item 14 done 2026-08-22, the rest open**

14. OK **Line bands and character marks** -> `DiffDecorations` (the model), `DiffBandsPart` (the drawing),
    `TextEditor.setDiffDecorations`, six tokens per theme, and `MergeView` showing each side against the
    ancestor. This was the one genuinely new engine piece the plan predicted: `TextEditor` had exactly one
    decoration lane (`diagnostic`) and nothing generic behind it.

    **Two pools in two layers, and they cannot be one.** A *band* answers "this line differs", which is
    true across the whole visible width however far the text has scrolled sideways - so it lives in the
    viewport, in screen coordinates. A *mark* answers "these characters differ", which is a claim about a
    position - so it lives in the lines layer and scrolls with the text. Putting both in one layer makes
    one of them wrong, and quietly: at scroll offset zero the two agree exactly.

    **The kind is a fact about the change, not about the pane.** An insertion is an insertion on both
    sides; it simply has no rows to band in the original, where it becomes a boundary rule instead.
    Deriving it per pane makes one change read as two different things depending on which side the eye is
    on - the exact failure a side-by-side view exists to prevent. `bothPanesAgreeOnWhatKindOfChangeItWas`
    is the pin.

    Governance caught two real things on the way in: `base.css` derives only, so literals belong in each
    theme, and **both themes must define the same keys** - the light pair is retuned rather than reused,
    because the dark alphas read as a faint glow on near-black and as dirty smudges on white.

15. OPEN **`DividerPolygon` ribbons.** The signature element of the requested shape. Needs a divider
    element painting filled slanted quads through `ctx.curve()`/`ctx.triangle()`, plus `DividerSeparator`.
16. OPEN **`SyncScrollSupport` proper.** `MergeView`'s per-frame ticker works and is honest about what it
    is; upstream's also handles folds, which matters once item 17 lands.
17. OPEN **`hideUnchangedRegions`** over `FoldingModel`, with the enclosing-symbol breadcrumb from the
    language services.
18. OPEN **`overviewRuler` change marks.** New: `ScrollerView` has no track markers.
19. OPEN **`revertButtons` / gutter chevrons.** In scope now that this is a merger rather than a viewer.
20. OPEN **`computeMovedLines` + `movedBlocksLines`.** Last, and genuinely optional.

### Stage 6 - the diff toolbar and header · **researched 2026-08-22, not started**

Read off IntelliJ's own settings model rather than off the menus: `HighlightPolicy`, `IgnorePolicy`,
`TextDiffSettingsHolder` in `platform/diff-impl/.../tools/util/base/`. The menu labels are a rendering of
two enums, and both turn out to map onto machinery this port already has.

#### The header, in two rows

Ours is one line reading `Difference 1 of 1` with two arrows. IntelliJ's is:

```
↑ ↓ ✎ | [Side-by-side viewer ▾] [Do not ignore ▾] [Highlight lines ▾] | ⧉ ⇅ ⚙ ?      10 differences, 0 included
🔒 c8442e9d  core/src/main/java/.../TextEditor.java                          ☐ Current version
```

**Taken as wanted:** prev, next, jump-to-source, the three dropdowns, collapse, sync-scroll, and the
difference count. **Skipped:** the back/forward pair and the hamburger — navigation history for a diff
opened from a list, which this has no equivalent of yet.

> The count reads **"10 differences, 0 included"** — the second number is a commit dialog's inclusion
> state, not a diff concept. A plain diff shows only the first half.

#### 1-2. Previous / Next Difference · `Shift+F7` / `F7`

We have the buttons; what is missing is that they are **commands with accelerators** rather than two
`onPressed` handlers. They belong in the `CommandRegistry` so the keys work without the buttons being
focused, and so the tooltips can read their accelerator from the `Keymap` — which is already this
codebase's rule ("a tooltip must read its accelerator from the KEYMAP, never spell it").

> Note the collision: **F7 is the harness's merge-demo key.** The scene binds it bare, so a diff view that
> claims F7 will fight it. The demo keys should move before the commands land.

#### 3. Jump to Source · `F5`

Opens the *real* file at the line under the caret and closes the diff. Needs: the caret's model row in the
focused pane, mapped through `mapLine` to the modified side (a row on the left has no meaning in the
working file), then the existing `openFile` path plus a `revealAt`. **The mapping is the whole difficulty**
— jumping from the left pane without it lands on whatever line happens to share that number.

#### 6. Side-by-side ↔ Unified viewer

Two panes versus one, where the single pane interleaves both sides: removed lines, then added lines, in
one column with one gutter that shows **both** line numbers. IntelliJ implements it as a separate viewer
class (`UnifiedDiffViewer`), not as a mode of the side-by-side one.

**What it needs here:** a third document assembled from the diff — base lines for unchanged spans, then
each block's left lines followed by its right lines — plus a per-row map back to `(side, line)` so the
gutter can print two columns and the bands know which colour to paint. `DiffDecorations` already carries
enough to colour it; the assembly and the two-column gutter are new.

> **Not a `TextEditor` mode.** The unified document is not either file, so a caret in it does not
> correspond to a document position in either — which breaks every editor affordance that assumes it does.
> Upstream making it a separate viewer is the same conclusion.

#### 7. Whitespace · `IgnorePolicy`, five values

| Menu label | `IgnorePolicy` | What it maps to here |
|---|---|---|
| Do not ignore | `DEFAULT` | `ComparisonPolicy.DEFAULT` — **have it** |
| Trim whitespaces | `TRIM_WHITESPACES` | `ComparisonPolicy.TRIM_WHITESPACES` — **have it** |
| Ignore whitespaces | `IGNORE_WHITESPACES` | `ComparisonPolicy.IGNORE_WHITESPACES` — **have it** |
| Ignore whitespaces and empty lines | `IGNORE_WHITESPACES_CHUNKS` | the same policy **plus `isShouldTrimChunks()`** |
| Ignore imports and formatting | `FORMATTING` | `ComparisonPolicy.DEFAULT` plus language-supplied ignored ranges |

So three of five are a dropdown over an enum this port already threads end to end. The other two are real
work:

**`isShouldTrimChunks()`** — after diffing, trim blank lines off the *edges* of every changed block. A
block that begins or ends with an added empty line reports as touching one more row than it changed, and
under "ignore whitespace" a person has already said they do not care about that row.

**`FORMATTING`** — upstream asks a language for the ranges to ignore (`DiffIgnoredRangeProvider`), which
for Java means import statements and reformatted-but-equivalent code. We have `LanguageServices` per
document, so the seam exists; the provider does not. **Lowest priority of everything here** — it is the
one item that cannot be done language-neutrally, and getting it wrong hides real changes.

#### 8. Highlight · `HighlightPolicy`, five values

| Menu label | Value | `getFragmentsPolicy()` | `isShouldSquash()` |
|---|---|---|---|
| Highlight lines | `BY_LINE` | `NONE` | true |
| Highlight words | `BY_WORD` | `WORDS` | true |
| Highlight split changes | `BY_WORD_SPLIT` | `WORDS` | **false** |
| Highlight characters | `BY_CHAR` | `CHARS` | true |
| Do not highlight | `DO_NOT_HIGHLIGHT` | `NONE` | — `isShouldCompare()` is false |

Three separate things fall out of that table, and only the first is obvious:

1. **Fragment granularity** — `NONE` / `WORDS` / `CHARS`. Ours is always CHARS-plus-word-extension.
   `WORDS` is that with the extension forced; `NONE` is skipping `LinesDiff.refine` entirely.
2. **Squash** — whether adjacent changed blocks separated by a trivial gap are merged.
   `SequenceOptimizations.joinShortMatchesBetween` is exactly that step, so `BY_WORD_SPLIT` is *skipping*
   it. **We already do the squashing unconditionally**, so this is a flag on an existing call.
3. **`DO_NOT_HIGHLIGHT` does not compute a diff at all** — not "compute it and draw nothing". On a large
   file that is the difference between a toggle and a stall, and it is why `isShouldCompare` exists
   separately from the fragments policy.

#### 9. Collapse Unchanged Fragments · `⧉`

Folds every run of unchanged lines beyond a context margin, leaving a marker with the enclosing scope's
name. This is Stage 5's `hideUnchangedRegions`, already on the list. Two details the toolbar adds: it is a
**toggle** rather than a one-shot, and the context line count is a setting.

> Both panes must fold **the same regions**, or the alignment this view just gained is destroyed — the
> folds have to be computed from the diff in base coordinates and applied through `mapLine`, not computed
> per pane.

#### 10. Synchronize Scrolling · `⇅`

A toggle over the alignment already built. Off, each pane scrolls alone. Cheap — one boolean read in
`tickFrame` — and worth having because a reader occasionally wants to hold one side still.

#### What has to exist before any of it

**A settings object.** All ten items are state that outlives one view: reopening a diff must remember the
policies. IntelliJ keeps `TextDiffSettingsHolder`, application-level with per-place overrides. Ours is a
`DiffSettings` on the editor's config storage — `CrystalEditor.useConfig` already provides one.

**A re-diff path.** Changing the ignore or highlight policy re-runs the comparison and replaces every
decoration; changing collapse or sync-scroll does not. Worth separating, because the first is expensive
and the second happens on a click.

**A dropdown-button widget.** `Dropdown` exists (`ui/elements/Dropdown`) and is a `Popover` over a `Menu`,
which is the right shape — a labelled button whose label reflects the current value.

#### Order

1. `DiffSettings` + the two-row header shell, with the buttons as commands
2. Highlight policy (three of its five cases are flags on code that already runs)
3. Whitespace policy (three of five are the enum already threaded; then chunk trimming)
4. Sync-scroll toggle, then Collapse (Stage 5's folding lands here)
5. Jump to Source
6. Unified viewer
7. `FORMATTING`, last and maybe never

### Not porting

- **`accessibleDiffViewer`** — depends on a screen-reader story this engine does not have yet.
- **`legacyLinesDiffComputer`** — superseded in its own repo.
- **`externalLinesDiffComputer`** — an extension seam we have no use for.
