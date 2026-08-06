# Icon attribution

Two sets ship here, under two licences. Both are **verbatim** — no file has been edited, recoloured or
re-exported. That is worth stating plainly because Apache 2.0 requires modifications to be declared, and
"we changed nothing" is the cheapest possible way to satisfy it.

## `filetypes/` — IntelliJ Platform icons

**Copyright © 2000–2021 JetBrains s.r.o. — Apache License 2.0.**

From [`platform/icons/src/`](https://github.com/JetBrains/intellij-community/tree/master/platform/icons/src)
in [intellij-community](https://github.com/JetBrains/intellij-community); the browsable index is at
<https://intellij-icons.jetbrains.design/>. Most files carry the licence header in their own source.

Full licence text: <https://www.apache.org/licenses/LICENSE-2.0>

- `fileTypes/` — 47 file-type icons.
- `nodes/` — `folder`, `package`, `moduleGroup`.

**Unmodified.** Only the directory they sit in has changed.

### Light and dark

The IntelliJ Platform ships one icon per file type in the general case; a `_dark` variant exists only where
the light one genuinely does not read on a dark background, which here is four files:
`Csharp_dark`, `binaryData_dark`, `json_dark`, `jsonSchema_dark`.

**Both variants are checked in and neither is wired up yet.** `default.json` names the unsuffixed file in
every case. Wiring them means a `darkSuffix` key in the theme and a way to ask which chrome is current —
a small change, and one worth doing once rather than discovering per icon. They are here so that decision
does not start with re-downloading anything.

### What is deliberately missing

Kotlin, Python, TypeScript, Rust, Go, C/C++, Ruby, PHP, shell, SQL, Markdown and GLSL have **no icon in the
Platform set** — theirs live in per-language plugin modules scattered across the repository, and several are
specific to a paid product. Those extensions resolve to the plain text document.

They are still listed individually in `default.json` rather than left to fall through, because listing them
is what gives each one its own `.filetype-*` class — so a stylesheet can tell a Kotlin file from a Rust one
today, on the label, even while they share a glyph.

Closing the gap means either hunting the plugin paths or filling in from
[Material Icon Theme](https://github.com/PKief/vscode-material-icon-theme) (MIT), which is licence-compatible
but a visibly different drawing style: 32px and saturated against IntelliJ's 16px and muted. Mixing them in
one file tree is the cost.

## The rest of this directory — Feather

**Copyright © 2013–2023 Cole Bemis — MIT License.**

<https://github.com/feathericons/feather>

`folder.svg`, `file-text.svg`, `image.svg`, `code.svg`, `package.svg`. Stroked, 24×24, authored as
`stroke="currentColor"` — which is what makes them theme from the cascade for free, and why they are kept
around as chrome marks even though the file tree now uses the IntelliJ set.

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
