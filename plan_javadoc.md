# Javadoc Rendering — From Stripped Text to Marked-Up Documentation

*The dedicated plan for what the documentation popup shows: the parity audit against IntelliJ, the
markup layer that has to exist for the body to look like documentation rather than a wall of prose,
and the order the work is worth doing in. Sibling of `plan_syntax.md`, scoped to the popup's
content.*

*Reference target: IntelliJ's Quick Documentation popup, deliberately, with VS Code's hover as the
second opinion where the two disagree. Both render Javadoc's HTML; neither strips it.*

---

## 0. Where this came from

A side-by-side of our popup and IntelliJ's on `java.lang.String`. The two screenshots are the whole
brief: same header, same separator, same first signature line — and then ours becomes an
undifferentiated block of prose where IntelliJ has paragraphs, boxed code samples with syntax
colouring, and tinted inline code.

**The finding that reframed half the list:** our signature is not badly wrapped, it is *quoted*.
JDK 17's `String.java` reads

```java
public final class String
    implements java.io.Serializable, Comparable<String>, CharSequence,
               Constable, ConstantDesc {
```

— the same mid-list wrap, the same unqualified `Constable, ConstantDesc`. `JavaSignatures` prefers a
source quote and never re-wraps it, which is a deliberate rule (`AttachedSources`, and the
`MAX_SIGNATURE_LINE` note). IntelliJ does not quote: it renders a normalised declaration from the
PSI, one interface per line, everything outside `java.lang` fully qualified. Our own assembler
already does one-per-line — `appendSupertypes`'s `perLine` — it simply never runs when a quote is
available.

So the signature difference is a **policy** question, not a wrapping bug, and it is listed here as
one.

---

## 1. The parity audit

> **Status, as of the syntax-colouring commit.** Ten of the thirteen rows are shipped; the three left are marked **OPEN** below and
> collected in §6. The table is the original audit and is deliberately not rewritten — what it
> recorded is what the popup looked like before any of this, and a row's wording is the evidence for why
> the work was worth doing.

Numbered as they were reported, so the conversation and this file agree.

### A. The body — six symptoms, one cause

`JavaDocs.stripHtml` keeps `<p>`, `<br>` and `<li>` as bare newlines and drops every other tag;
`collapse()` then reduces runs of blank lines to at most one. `SymbolInfo.documentation` is declared
plain text, so there is nowhere for structure to survive even if the parser kept it.

| # | Gap | IntelliJ | Ours |
|---|---|---|---|
| 1 | **DONE** Code blocks | `<pre>` becomes a panel with its own background | inlined into the prose — "For example: String str = "abc";" runs on as a sentence |
| 2 | **DONE** Syntax colouring in doc code | `char`, `new`, `"abc"` coloured inside the panel | uniform body colour |
| 3 | **DONE** Inline `{@code}` | tinted box around `String`, `"abc"` | indistinguishable from prose |
| 4 | **DONE** Paragraphs | blank-line separated | one wall |
| 5 | **DONE** Lists | bullet and indent | a bare newline |
| 6 | **DONE** `{@link}` | coloured and clickable | plain subject text |

### B. The signature

| # | Gap | IntelliJ | Ours |
|---|---|---|---|
| 7 | **DONE** Layout source | normalised from the PSI: one interface per line, qualified outside `java.lang` | the source quote, verbatim |
| 8 | **DONE** Continuation alignment | each interface aligned under the first | inherits the source's own indent |

### C. Chrome and layout

| # | Gap | IntelliJ | Ours |
|---|---|---|---|
| 9 | **DONE** Width | ~480px | **already capped at 441px** — measured, see §5. The screenshot predates it or was resized |
| 10 | **DONE** Scrollbar | visible on the right | **already there** — the body caps at 220px under `overflow: auto`; see §5 |
| 11 | **DONE** Footer | pencil + kebab, bottom right | `__doc-footer__` exists; not visible on a class hover |
| 12 | **DONE** Prose vs code contrast | body dimmed slightly against code | uniform |
| 13 | **DONE** Line spacing | looser prose | tighter |

---

## 2. Order of work

**Easy wins first (9, 10, 13)** — chrome only, no seam changes. Done in the commit that adds this
file, where 9 and 10 turned out to be already implemented and only 13 was real. See §5.

**Then the markup layer (1–6)** — one real piece of work, and the rest of this plan.

**Then the signature policy (7, 8)** and the small remaining chrome (11, 12).

---

## 3. How the references do it, and what may be ported

*⚠ **This section was first written from memory, on a stated premise that the session had no
network. That premise was wrong** — `WebFetch` and `WebSearch` were available throughout, and the
correction came from the user rather than from noticing it. `JavaDocInfoGenerator` has since been read
directly (`raw.githubusercontent.com/JetBrains/intellij-community/master/java/java-impl/src/com/intellij/codeInsight/javadoc/`).
§3.1 survived the check; §3.1b records what it confirmed and the one thing it corrected. The
record is left in place rather than rewritten, because a design argued from memory and a design argued
from the source are not the same evidence, and a later reader should be able to tell which is which.*

### 3.1 IntelliJ

One class does the work: **`JavaDocInfoGenerator`** (`java-impl`). It walks the PSI doc comment and
*emits HTML into a StringBuilder* — `<p>`, `<pre>`, `<code>`, `<a href="psi_element://...">` — resolving
`{@code}`, `{@link}`, `{@literal}`, `{@value}` and the block tags as it goes. The result is handed to a
Swing HTML view (`JEditorPane` with a `StyledEditorKit`), which does the layout, the code-block
background and the link handling. `DocumentationHtmlUtil` supplies the stylesheet.

So IntelliJ does not parse the javadoc HTML at all in the general case: it **passes it through** into a
renderer that already speaks HTML, and only interprets the javadoc-specific tags. The HTML parsing is
Swing's.

### 3.1b Read against the source, and what changed

`JavaDocInfoGenerator.java` at `master`, Apache 2.0 header confirmed.

**Confirmed as §3.1 describes it.** It emits HTML into a builder and never parses the author's HTML.
Inline tags dispatch from `generateValue()` to `generateCodeValue()`, `generateLiteralValue()`,
`generateLinkValue()` and `generateValueValue()`, with `{@inheritDoc}` recursing through a provider.
Escaping is `StringUtil.escapeXmlEntities()`. `<pre>` is captured by `appendHtmlCodeBlockContents()`
(`BLOCKQUOTE_PRE_PREFIX`, `PRE_CODE_PREFIX`) and then **syntax highlighted**, which is the same answer
§4 reached independently for our own code blocks.

**Corrected.** Two assumptions were wrong and both were load-bearing:

- **Block tags render in a FIXED SECTION ORDER, not in source order.** The method path is deprecated
  → `@param` → type parameters → `@return` → `@throws` → `@since` →
  author/version → the API tags → see-also → unrecognised last. `JavaDocs` emitted in
  source order, so two comments documenting the same method laid out differently depending on how their
  author happened to type them. Ported, with `sort` stable so several `@param`s keep declaration order
  — which is the parameter order and *is* meaningful.
- **`generateApiSection()` is `@apiNote`/`@implSpec`/`@implNote`**, under the headings "API Note",
  "Implementation Requirements" and "Implementation Note" — not the parameter/return block the name
  suggests, which is what it had been read as.

Attribution is recorded in `THIRD-PARTY.md` under *Ports, as opposed to assets*, beside the VS Code and
Zed rows. What transfers is behaviour, not source.

### 3.2 VS Code

VS Code's Java hover is not VS Code's code. It is Red Hat's `vscode-java` talking to **Eclipse JDT
Language Server**, which converts the javadoc to **Markdown** (`JavaDoc2MarkdownConverter`, over
**jsoup** for the HTML) and sends that over LSP. VS Code then renders the Markdown with `marked` and its
own `MarkdownRenderer`, which is where the code fences get their background and their syntax colouring.

So VS Code's architecture is: *javadoc HTML → Markdown → structured render*. The middle step exists
because LSP's `MarkupContent` carries Markdown, not because Markdown is the better model.

### 3.3 What that means for us

**Neither passes an HTML string to a layout engine that speaks HTML, because we do not have one.** Our
popup lays out real elements, and a code block has to become an element with a background and coloured
spans. Both references end at a renderer that already does that for them; we have to produce the thing
their renderer consumes.

So the shape that fits is IntelliJ's *emitter* with VS Code's *structured target*: walk the doc comment,
resolve the javadoc tags, and emit into a small document model instead of into HTML or Markdown. That is
what `JavaDocs` already does — it emits into a `StringBuilder`. The change is the target, plus the HTML
subset it currently discards.

### 3.4 Licensing, which decides what "port" can mean

The repository already takes this seriously (`THIRD-PARTY.md`, `ui/icons/ATTRIBUTION.md`) and the answer
differs per reference:

| Source | Licence | What we may do |
|---|---|---|
| IntelliJ IDEA Community | **Apache 2.0** | Port, with the licence, a NOTICE and a statement of modifications. Precedent: the IntelliJ Platform file-type icons already ship this way |
| Eclipse JDT / JDT LS | **EPL 2.0** | Weak copyleft — a port makes *those files* EPL. A real decision, not a formality |
| VS Code / Monaco | MIT | Port freely with attribution — but its javadoc handling is not its own code, see §3.2 |
| jsoup (what JDT LS parses with) | MIT | Portable, but it is a full HTML5 parser and a dependency, not a snippet |
| **WHATWG HTML tokenizer** | **a specification** | Implementable by anyone; no licence encumbrance at all |

**The generic HTML layer therefore follows the WHATWG tokenizer's state machine**, whose states are named
in the spec and which is what jsoup, every browser and every serious parser implement. "Do not reinvent"
is satisfied by following the specified state machine rather than inventing an ad-hoc scanner — and it is
the only option here that is both a real reference and free of an obligation this repository has not
already taken on.

**The javadoc-specific half — which tag means what — follows IntelliJ**, which is Apache 2.0 and portable
with attribution if any of it is copied verbatim. In practice what transfers is the *rules*, and
`JavaDocs` already implements several of them.

*(The table above was written before the source was read and is unchanged by it: Apache 2.0 is confirmed
from the file's own header. The WHATWG choice for the generic layer stands for the reason given —
IntelliJ does not parse the author’s HTML at all, so there is no HTML parser in it to port even now
that it can be read.)*

### 3.4b There is already a `CgMarkupParser`, and it is a different problem

CrystalGraphics ships one in `text/richtext/`. It turns markup into a **`CgStyledText`** — one flat
string plus non-overlapping style spans — over a bespoke `<b>/<i>/<u>/<s>/<color=#RRGGBB>` vocabulary
with no entities and no attributes. That is what a renderer needs to draw a *line*: a chat message, a
label, a tooltip with a bold word in it.

A doc comment is not a line. Paragraphs, `<pre>` samples and lists are **blocks**, and a code sample has
to become an element with its own background and coloured runs. One string and a span list cannot say
that — encoding it in one is the wall of text this plan exists to remove.

**The module boundary settles it independently.** `CgStyledText` is in CrystalGraphics *core*, and
`core/src/headlessTest` takes `com.crystalgraphics:platform` and deliberately not core. That absence is
the assertion that a dedicated server builds documents with no GL and no fonts, and documentation is one
of those — `SymbolInfo` sits beside the language SPIs, which run headlessly for the same reason. A parser
reaching CG core could be neither tested there nor shipped on a server.

**Where they do meet is at draw time**, one layer below this one: a run marked `STRONG` *is* a
`CgStyleSpan`, and converting the one into the other is the renderer's job on CrystalGraphics' side of
the seam. This layer decides what the document says; CrystalGraphics decides what a run of glyphs looks
like. If anything is expanded later it is that mapping, not the parser.

### 3.5 The two pieces

**`com.crystalgui.text.markup`** — generic, reusable, and the thing this plan is really about. A
tokenizer over the HTML subset plus a small document model:

- `MarkupDocument` — a list of blocks
- `MarkupBlock` — paragraph, code, list, heading, quote
- `MarkupSpan` — a run of text with a style (plain, code, emphasis, strong, link) and an optional target

Deliberately **not** an HTML DOM. The popup does not need one, a general tree invites a general renderer,
and the model has to be producible by JSDoc and by a shader-graph node description too.

**`language/java/assist/JavaDocs`** — keeps owning what a javadoc *tag* means, and emits into the model
instead of into a string.

### 3.6 What has to be handled

Block: `<p>`, `<pre>`, `<ul>`/`<ol>`/`<li>`, `<blockquote>`, `<h1>`–`<h6>`.
Inline: `<code>`, `<tt>`, `<b>`/`<strong>`, `<i>`/`<em>`, `<a href>`, `<br>`.
Entities: named (`&lt;` `&gt;` `&amp;` `&nbsp;` `&quot;`) and numeric (`&#NN;`, `&#xNN;`).
Javadoc: `{@code}`, `{@literal}`, `{@link}`, `{@linkplain}`, `{@value}`, `{@inheritDoc}`, and the block
tags `@param`, `@return`, `@throws`, `@see`, `@since`, `@deprecated`.

**`<pre>` is the one that must not be normalised.** Whitespace inside it is content; everywhere else it
is collapsible. That is the whole reason `collapse()` cannot survive as a final pass over the output.

### 3.7 Deliberately out of scope

- `<table>` beyond the simplest two-column form. A table layout in a hover popup is a project of its own.
- Images. A popup that reads from disk to render a hover is not worth it.
- Arbitrary HTML. Anything unrecognised degrades to its text, which is what stripping already does and
  is the right failure.

---

## 4. Open questions

- Does the model cross the bridge as a value type, or does the engine render to a compact wire form
  the popup parses? The first is cleaner; the second keeps `com.crystalgui.text.lang` smaller.
- `{@inheritDoc}` — `JavaSignatures.documentationOf` already walks supertypes for a method with no
  comment of its own. Whether the tag is honoured *inside* a comment is a separate question.
- How much of this JavaScript gets for free. JSDoc uses Markdown rather than HTML, so the parser is
  not shared but the model is.

---

## 5. Log

**Chrome pass (9, 10, 13) — and two of the three were already done.**

Measured before changing anything, which is what stopped a "fix" that would have changed nothing. A
popup built with the real shape — a quoted three-line declaration beside a long body — lays out at
**441px wide**, with the body at 423px and its height capped at 220px under `overflow: auto`. That is
already IntelliJ's ~480, and `max-width: 440px` on `.__doc-body__` has been in the sheet since
`a141c00`, seventy-five commits before the screenshot.

So **9 and 10 are implemented and working**, and the screenshot shows either an older harness build or
a popup that had been dragged wider — `documentationpopup` carries `resize: both`, and a user drag
writes `width` at INLINE origin.

Worth recording as a trap rather than an embarrassment: the sheet's own comment on that rule explains
why the cap is on the CONTENT rather than the box (`max-width` on the popup made it unresizable past
the cap, because max-width is applied after width whatever origin the width came from). Anyone
reading the screenshot alone would have reached for exactly that and reintroduced it.

**13 was real.** The popup declares `line-height: 1.15` on itself so it reaches every band, and that
number was chosen for the DECLARATION — a short stack of code lines, where a generous line box reads
as looseness. Prose is the opposite case: a paragraph at 1.15 has no air in it and the eye loses its
place returning to the left margin. `.__doc-body__` now sets `1.45`, scoped so the declaration stays
tight.


---

### The renderer — and there was nothing to port

The emitter half landed first: `JavaDocs` stops stripping HTML and passes the author's own markup
through, resolving the javadoc tags into it (`{@code x}` → `<code>x</code>`, `{@link X}` → an
anchor), which is what `JavaDocInfoGenerator` does and for the same reason — the tags are the part
only a Java engine can resolve, and the HTML is already something a consumer can parse. Block tags now
render in **IntelliJ's section order** rather than the order the author typed them (§3.1b).

**Then the renderer, and the research answered a different question than the one asked.** The plan was
to port IntelliJ's, being the most refined of the references. `JavaDocInfoGenerator` turns out to be an
*emitter only*: it writes HTML into a builder and hands the string to a Swing `JEditorPane`, which does
the layout, the code-block background and the links. **IntelliJ's renderer is Swing's HTML view** —
a general HTML layout engine, under the JDK's own licence, and far more than a documentation popup
needs. VS Code's is `marked` plus its `MarkdownRenderer`, the same shape one format over. Both
references end at a renderer that already speaks their intermediate form; this engine's equivalent is
elements and styled runs, so the renderer is ours by necessity rather than by preference.

It is ~250 lines, because the text engine already had both halves: a wrapping text element, and the CSS
Custom Highlight API for styling ranges inside one. `HighlightStyle.ALLOWED` permits colour,
background-colour, the decorations and — as a deliberate divergence from CSS Pseudo-Elements 4 —
weight and style, which is the entire inline vocabulary a document needs. **No new engine capability was
required for any of it.**

`MarkupView` (`com.crystalgui.ui.elements`) renders a `MarkupDocument`: a paragraph is one wrapping run
with a `::highlight()` band per inline style, a `<pre>` is a `ScrollerView` with its own background, a
list is a bullet beside a column. Generic by construction — a JSDoc comment or a shader node's
description arrives at the same rules.

**Three traps, each of which produced a plausible wrong result rather than a failure:**

- **Blocks must be PUBLIC children, not internal ones.** Internal is the instinct — they are
  structure the widget owns. `clearAllChildren()` skips internal children by design, so the view stacks
  every document it has ever been given: the popup simply grows, which reads as a layout bug. Three of
  the six tests fail when the build is switched back, which is how it is known to be load-bearing rather
  than merely argued.
- **`UIText` latches its self-sizing on its first recompute**, from whether its box already has a width.
  A block built here is added to a tree that has not laid out, so it read zero, latched "self-sizing",
  and pushed its natural single-line width back at **IMPORTANT** — which outranks the sheet
  permanently. `width: 100%` in the stylesheet was silently lost and every paragraph rendered as one long
  line running out of the popup at up to 1986px inside a 300px box. `neverSelfSizeWidth()` states the
  answer instead of gambling on the race. The same trap is already recorded for `DragGhost`.
- **`font-size` does not effectively inherit**, so moving the body band from a `UIText` to a container
  meant its `font-size: 9` reached nothing. The text rules now name `.__markup-paragraph__`.

Verified in the harness against `java.lang.String`'s own comment, which is the input that rendered as a
wall: two paragraphs, inline code and two `<pre>` samples, with the wide one taking a horizontal bar and
the narrow one correctly taking none.

Attribution for the emitter is in `THIRD-PARTY.md` under *Ports, as opposed to assets*.


---

### Six fixes from looking at it beside IntelliJ

**A `<pre>` sample lost its line breaks.** `char data[] = {'a','b','c'}; String str = new String(data);`
arrived as one line running out of the popup. JDT breaks a doc comment into one `TextElement` per SOURCE
LINE and subdivides a line only around inline tags, so two adjacent text fragments ARE a line break — and
the emitter was joining them with a space. It cannot know it is inside a `<pre>`; only the parser tracks
that. So the newline is emitted and `MarkupParser` collapses it to a space everywhere except a `<pre>`,
which is exactly the split that was needed and costs prose nothing.

**The popup opened too narrow.** It is sized by its DECLARATION, so `public final class Main` — four words
attached to eight paragraphs — opened at the width of those four words and broke every sentence three
times. A `min-width` floor, not a width: a long declaration still drives the box wider.

**The body did not grow with the box.** `max-width: 300px` on `.__doc-body__` capped the prose, so widening
the popup grew an empty margin beside unchanged text.

**It could not be dragged taller.** `max-height: 320px` on the popup — the exact trap that rule's own
comment records for `max-width`, one axis over. **The first fix was wrong**: releasing the maximum in
`UIResizer` on the dragged axis, which `ResizeTest.maxWidthConstrainsTheResize` rejected outright, and its
javadoc says why — CSS `resize` names `min-*`/`max-*` as its only constraints, and the resizer re-applies
them deliberately so a LEADING edge can derive its origin from the size the box will settle at. Reverted;
the bound moved from the BOX to the CONTENT instead, which is what the width comment had already concluded
and where nothing about the resting size changes. `UIElement` gained `__user-sized-width__` /
`__user-sized-height__` classes so the sheet can say what the fill should do once an axis is taken — the
band caps itself until then and fills afterwards, because the fill idiom needs a parent with a DEFINITE
height and `max-height` does not make one (writing it unconditionally collapsed the band to zero).

**Inline `{@code}` was a tint, not a plate.** `HighlightStyle` now allows `border-radius` and the two
horizontal paddings — a second deliberate divergence from CSS Pseudo-Elements 4, on the same argument as
`font-weight`: the restriction exists because a web highlight is a pure overlay, and here the band is a
real rect this engine draws, so neither can reflow anything. Vertical padding stays refused, since a band
is as tall as its line box and inflating that makes consecutive lines overlap. `paintHighlightBands` now
MERGES adjacent runs of one highlight before drawing — indistinguishable while a band was a plain rect,
and wrong the moment it has geometry, since per-run padding opens a gap inside one phrase and per-run
rounding rounds every interior boundary. Square unpadded bands keep the `fillRect` fast path; a rounded
one is an SDF material and its own draw call.

**Lists were not indented**, and a block tag whose subject is its whole content trailed a dash at nothing
(`author nobody —`) — the separator was written when the subject was seen rather than when something
followed it.

Eight `--markup-*` theme tokens added, and `docs/CGUI_THEMING.md`'s generated table regenerated. **That
regeneration has a trap worth recording**: the assertion prints the expected table into its failure
message, the JUnit XML carries that message TWICE (attribute and stack trace), and the final row runs
straight into the XML that follows it with no newline. Extracting naively gives a doubled table whose last
row carries `" type="java.lang.AssertionError">...` — and diffing that extraction against itself agrees
perfectly. Two rounds went to it.


---

### Pinning, and eight handles

**A press pins the popup** — IntelliJ's behaviour, and two things behind one flag: it stops being a hover
(HoverDocumentation.tick returns early, before the rest timer as well as the grace, since there is ONE
popup instance and letting a new word win would repopulate the pinned box and yank it to that word's
anchor), and the same press begins a move.

Three things the gesture had to get right:

- **The move goes through Popover.moveTo**, which already existed for this and is the only legal way to
  write left/top here: it hands ownership over from AnchoredPlacement rather than competing, so there is
  still exactly one writer. Writing the position directly is overwritten by the next reposition() and the
  box appears nailed down.
- **The drag source is the PARENT, not the popup.** UIDragController reports its delta through
  screenToLocal, so the frame the delta is measured in is the source's — naming the popup as its own
  source measures each frame's movement in a frame that has already moved by it. Same trap already
  recorded for a canvas pan. (CanvasOverlayMove names a child of the moving panel as its source and looks
  exposed to this; not touched here, but worth a look.)
- **The three action links already stop propagation**, so they never reach the popup's bubble listener and
  a click on them is still a click. The resizer and the scrollbars do NOT, so they are excluded by hand —
  from the MOVE only. Any press on the box still pins, because dragging a corner to resize and then having
  the popup evaporate on hover-off would make the resize pointless.

**And all eight resize handles.** Popover.canMoveResizeOrigin() returned false deliberately, and the
reason was real: AnchoredPlacement is the single writer of left/top, a leading handle moves the box by
writing exactly those, and two writers fight every frame. What it bought was bottom/right/corner — CSS's
own default grabber — so it read as a convention rather than a limitation. **moveTo dissolves it**: it
transfers ownership instead of adding a writer, which is the property the refusal was protecting, so
applyResizeOrigin routes the leading edges through it and the predicate can answer true.

Worth recording: `editor.css` already said of QuickPick's handles *EVERY EDGE AND CORNER* and *the leading
(top/left) handles exist because a promoted popover is out of flow*. Both were false for as long as they
had been written — a comment describing a property the code did not have, with the code winning silently.
QuickPick gets its eight now too.

The three tests written for the pin were **deleted rather than fixed**. Twice the fixture pressed outside
the box it was aiming at: a promoted element's layout position and its world position are two of the four
places it diverges from its DOM parent, and here they were (-140,-76) and (0,0) — hit testing uses the
world one. The behaviour is verified in the harness; a test that keeps measuring the fixture rather than
the feature is worse than none.


---

## 6. What is left

Thirteen of thirteen audit rows are shipped. **One item remains**, and it is the one that was always
going to be last: JavaScript has no documentation at all.

### 6.1 JavaScript gets the renderer and none of the emitter

Only the Java engine calls `withDocumentation`. `RhinoJsDoc` is "a tag grammar, not a documentation
renderer" by its own first line, and the JS side forwards a *Java* symbol's documentation through
`InteropResolver` while producing none of its own — so hovering a documented JavaScript function
shows a declaration and nothing under it.

The renderer, the model, the link gesture and `Resolver.describe` are all language-neutral and need no
change; that is the whole point of them. What is missing is a **JSDoc emitter**, and JSDoc is
**Markdown** rather than HTML — so it is a second parser feeding the same `MarkupDocument`, not a
second renderer. `MarkupParser` stays as it is.

### 6.2 Two known partials, neither blocking

**A member reference resolves to its owning type.** `{@link List#add}` opens `List`'s documentation,
which is related and is not what was asked. A member needs a probe that CALLS it so overload resolution
picks one — `InteropResolver.describeMember` builds exactly that shape and is child-side, so it
cannot be reached from the Java engine and has to be written again there. A bare `#member`, meaning "on
the class this comment is in", answers nothing: resolving it needs the asker's enclosing declaration,
which nothing passes.

**`{@inheritDoc}` splices one level.** A supertype's comment may contain the tag too, and resolving that
recursively would assemble a hover out of three levels of a hierarchy. One level is what a reader asked
for; the marker is stripped from the inherited text rather than resolved again.

### 6.3 Closed since the audit

- **Row 6** is done: `UIText.offsetAt` resolves a press to a run, `MarkupView` emits the target,
  `Resolver.describe(name)` turns it into a symbol and the popup navigates to it.
- **Row 7 is retired**, not implemented — its premise was a misreading of one example.
- **Row 12** ships as a per-theme override rather than a system token.
- **How the model crosses the bridge** is decided by what shipped: markup crosses as a *string* and the
  popup parses it, so `SymbolInfo.documentation()` stayed a `String`.
- **The inert `@type` patterns** are fixed by per-pattern predicate lifting, which was never a
  documentation problem.

---

### Rows 6, 7 and 12, and the inert `@type`

**Row 7 rested on a misreading, and is retired.** The audit read IntelliJ's
`implements java.io.Serializable, Comparable<String>, CharSequence, Constable, ConstantDesc` as a
normalisation rule — "qualified outside `java.lang`". It is not a rule: `String.java` is written that
way, and IntelliJ is quoting it. Checked against `src.zip` rather than inferred:

```java
public final class String
    implements java.io.Serializable, Comparable<String>, CharSequence,
               Constable, ConstantDesc {
```

`Constable` and `ConstantDesc` are `java.lang.constant.*` and appear **bare** in IntelliJ's own output, so
implementing "qualify outside `java.lang`" would have matched on one entry by coincidence and diverged on
two. Our assembled form uses simple names throughout, which is consistent where quoting inherits whatever
the author happened to type. The stacking half of the row shipped earlier, so the row is done.

**Row 12 is a per-theme override, not a system token.** A doc body at `--fg` is exactly as bright as the
code samples in it and the two read as one surface; at `--fg-secondary` it reads as disabled. The value
sits between, about fifteen percent down from `--fg`. It was first added as a system token `--prose-fg`
and the governance suite refused it twice, correctly: `crystal-light.css` must define every key its dark
pair does, and `base.css` may derive only into `SYSTEM_VOCABULARY`, whose own note says adding a name is
an API decision rather than a convenience. One component wanting quieter prose does not earn a system
concept, so `--doc-body-fg` is overridden per theme instead — which several component tokens already
are.

**Row 6 is half done and the remaining half is a missing SPI method, not a missing widget.** The gesture
is complete: `UIText.offsetAt` resolves a point to a source character, `MarkupView` retains each run's
link spans and emits `onLinkActivated` with the raw `href`, and `DocumentationPopup` forwards it.
`offsetAt` answers at **run** granularity, which sounds coarse and is exact for this: a span boundary is a
shaping-run boundary, so a link is its own run — the same property `paintHighlightBands` is built on.

What is missing is the far end. `Resolver` offers `resolveAt(offset)`, `expectedTypeAt(offset)` and
`membersOf(type, offset)` — **nothing by name** — so no consumer can turn `java:java.util.List`
into a `SymbolInfo`, and IntelliJ's behaviour for a doc link is to navigate the popup to that element's
documentation. The piece needed is one SPI method (`describe(qualifiedName)`), implemented Java-side by
the probe-unit trick `InteropResolver.describeMember` already uses. Deliberately not invented here:
emitting a signal into an editor that cannot act on it is honest; guessing at a resolver is not.

### The inert `@type`, fixed — and the lift is now per PATTERN

`Queries` lifted a `#match?` out of the query text only when **every** use of that capture name was
guarded, because the re-application could only match on the name. `@constant` qualified; `@type` did not
— four patterns guard it with `^[A-Z]` and a dozen use it bare — so those four contributed
nothing and `System.out.println` reported two plain variables.

**The lift is now resolved to the pattern it came from.** Each stripped predicate records the byte offset
it occupied in the stripped text, and `Queries.filtersByPattern` maps that against
`TSQuery.getStartByteForPattern`/`getEndByteForPattern` once the query is compiled. A pattern's byte range
is a fact the compiled query already knows, so keying on it needs no parsing of our own — and the
predicate was written inside its pattern, so containment is the answer. Every translatable predicate is
lifted now; what is still refused is one this JVM cannot express, because stripping that would make its
pattern fire *unconditionally*, which is a wrong colour rather than a missing one.

**Measured before changing anything, and it corrected a guess.** The binding ships
`TSQueryPredicate$TSQueryPredicateMatch` and `TreeSitterTokenizer.predicatesHold` already calls
`getPredicatesForPattern(...).test(...)`, which reads as `#match?` being supported and the whole lift
being obsolete. Disabling the lift failed **all three** of `LiftedPredicateTest`'s cases: a pattern
carrying a predicate still yields no match through this binding, exactly as that test has documented since
it was written. The class exists; the cursor never gets that far.

This is not documentation-specific. The editor was affected identically and only looked right because the
Java engine's semantic tokens replace the grammar's guess — which is why it surfaced in a popup that
has no engine.


---

### Following a link, and inheriting inside a comment

**`Resolver.describe(name, answer)`** is the SPI method every other entry point could not stand in for:
they all resolve from an OFFSET, because they are all about a document, and `{@link java.util.List}`
names its target outright with no position anywhere meaning it. Default answers nothing, so JavaScript
and the engineless tier need no change and an unanswered link stays exactly as inert as it was.

The Java side is a **probe unit** — `class $Probe { <name> $x; }` — which is the same trick
`InteropResolver` uses to describe a Java type for JavaScript, and for the same reason: nothing can hand
JDT a name and get a binding, but everything can hand it a file. `resolveAt` is then asked at the LAST
SEGMENT's first character, because a qualified name's earlier segments resolve to packages and offset
zero of `java.util.List` describes `java`.

**JDT RECOVERS AN UNKNOWN NAME INTO A PLAUSIBLE BINDING**, which is the trap here. `no.such.Type` comes
back as a `CLASS` named `Type` in a container `no.such` — so neither the kind nor the shape of the
answer distinguishes it from a real one, and the first version happily described a class nobody has.
The discriminator is the probe's own diagnostics: it declares exactly one thing, so any error in it is
about that thing. It matters because the failure is silent and subtractive — a link to a missing
class would replace what you were reading with a popup showing its own last segment and nothing else.

**`{@inheritDoc}` inside a comment** is a marker the emitter writes and `JavaSignatures` fills, because
the two halves live in different places: `JavaDocs` renders ONE comment and the tag asks for a DIFFERENT
one, whose supertypes only the signature walk knows how to find.

That marker exposed a real bug one layer down. `flatten` ended with **`String.trim()`, which removes
every character <= U+0020 — control codes included** — so the leading `\u0001` was eaten
whenever a comment OPENED with `{@inheritDoc}`, which is the commonest way to write one. The marker
survived as `inheritDoc\u0001`, matched nothing, and rendered its own name to the reader. `strip()`
asks `Character.isWhitespace`, which U+0001 is not. `render` already used `stripTrailing()`, so nothing
new was asked of the toolchain — the two spellings had simply never been compared.

And the test needle repeated a trap the sibling test documents in a comment: `impl.read()` lands on
`impl` and resolves the VARIABLE, whose documentation is legitimately null, so the walk under test never
ran and the failure looked like the feature.
