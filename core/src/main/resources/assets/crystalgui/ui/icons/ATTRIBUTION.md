# Icon attribution

Two sets ship here, under two licences. Both are **verbatim** — no file has been edited, recoloured or
re-exported. That is worth stating plainly because Apache 2.0 requires modifications to be declared, and
"we changed nothing" is the cheapest possible way to satisfy it.

## `filetypes/` and `toolwindows/` — IntelliJ Platform icons

**Copyright © 2000–2023 JetBrains s.r.o. and contributors — Apache License 2.0.**

The **2023 "New UI" set**, taken from the official browsable index at
<https://intellij-icons.jetbrains.design/>. Every file carries the licence header in its own source.

Full licence text: <https://www.apache.org/licenses/LICENSE-2.0>

Each file type ships a light and a dark drawing, as `name.svg` and `name_dark.svg`. The set is **not
enumerated here on purpose** — it grows as icons are pulled from the index, and a list in a notice file is
a second copy of a fact that `ls filetypes/` already states, which is the copy that goes stale. The licence
below covers everything in that directory.

**Unmodified.** Only the directory they sit in has changed.

`toolwindows/` holds the same set's tool-window marks — `notifications.svg` and `problems.svg`, each with a
`_dark` companion, drawn by the rail buttons for the Notifications and Problems panels. Same copyright, same
licence, same "unmodified" statement, and reached through the same `FileIconTheme.withVariant` suffix
convention as the file types; it is a separate directory only because a tool-window mark is not a file type
and `default.json` should not have to say so.

> An earlier revision of this file described a 2021 set of 47 icons pulled from `platform/icons/src/` in
> the `intellij-community` repository. That set was **replaced wholesale**, not extended: the 2023 icons
> are a different drawing language — outlined and warm where the old ones were flat polygons in blue and
> grey — so the two cannot appear in one file tree without looking like a mistake. The old set also carried
> a long tail nothing here will ever open (`jsp`, `jspx`, `jupyter`, `microsoftWindows`, `uiForm`,
> `diagram`, `aspectj`, `idl`, `hprof`, `jfr`, `wsdl`), which is why the replacement is smaller as well as
> newer.

### Light and dark

Every icon here ships as both `name.svg` and `name_dark.svg`, and **both are wired up**: `default.json`
names only the stem, and `FileIconTheme.withVariant` appends the suffix for the active
`FileIconTheme.Variant`, falling back to the stem when an icon has no dark drawing. An icon that reads on
either background is therefore free to ship once, and a theme never has to say so.

Which variant is active is currently one static on `FileIconTheme` — provisional, and documented there as
such: there is no editor-theme concept for it to be a property of yet.

### What is deliberately missing

Several languages have **no icon in the Platform set** — theirs live in per-language plugin modules, and a
few are specific to a paid product. GLSL has no JetBrains icon at all; its IntelliJ support is third-party.
Those extensions resolve to the plain text document until an icon is pulled in for them.

Which ones are still outstanding is stated where it is actionable — in `default.json`, as the entries whose
value is `filetypes/text`. It is not restated here, for the same reason the shipped set is not listed above.

They are still listed individually in `default.json` rather than left to fall through, because listing them
is what gives each one its own `.filetype-*` class — so a stylesheet can tell a Kotlin file from a Rust one
today, on the label, even while they share a glyph. Filling one in later is a one-line change to the value.

Closing the gap means either hunting the plugin paths on the index above or filling in from
[Material Icon Theme](https://github.com/PKief/vscode-material-icon-theme) (MIT), which is licence-compatible
but a visibly different drawing style: 32px and saturated against IntelliJ's 16px and muted. Mixing them in
one file tree is the cost.

## The rest of this directory — Feather

**Copyright © 2013–2023 Cole Bemis — MIT License.**

<https://github.com/feathericons/feather>

`folder.svg`, `file-text.svg`, `image.svg`, `code.svg`, `package.svg`, `x.svg`, and the three severity
marks the notification cards draw — `info.svg`, `alert-triangle.svg`, `alert-circle.svg`. Stroked, 24×24,
authored as `stroke="currentColor"` — which is what makes them theme from the cascade for free, and why
they are kept around as chrome marks even though the file tree now uses the IntelliJ set.

The severity three are drawn to Feather's own geometry and stroke conventions rather than copied byte for
byte, which changes nothing about the obligation: the notice travels with the distribution either way, and
guessing at where the line falls is a worse bet than naming the source.

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction, including
> without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the
> following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial
> portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
> LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
> EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
> IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
> THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Trademarks

Neither licence grants trademark rights — Apache 2.0 § 6 says so outright. The file-type icons above are
JetBrains' own drawings of documents, not marks. The **IntelliJ IDEA logo is** a mark, which is why it lives
in `core/src/test/resources/` and not here: it is the SVG renderer's torture test, not a shipped asset.
