# Command inventory

Every command this codebase declares, swept on 2026-09-21 so icons can be assigned to them.

**188 commands, across 28 files. 30 of them carry an icon.**

> **New ▸'s rows are not in this list**, and that is not an omission: each is a
> `NewDocumentKind` registered with `WorkbenchContext.newDocuments()` rather than a `Command.of`
> call site, so this sweep cannot see them. A kind's id IS a command id and a menu row is built for
> it either way -- see `docs/CGUI_WORKBENCH_SERVICES.md` § Making things.

`Command.icon(String iconId)` was already read by `MenuBuilder` and already styled by
`menu.__has-icons__` in `ua/overlays.css` — the whole path existed and had simply never been
called. Wiring it up was a data pass, not a feature.

| Command | Mark |
|---|---|
| `blackboard.deleteProperty` — Delete Property | `crystalgui:general/action/delete` |
| `console.removeScript` — Remove | `crystalgui:general/action/delete` |
| `edit.copy` — Copy | `crystalgui:general/action/copy` |
| `edit.cut` — Cut | `crystalgui:general/action/cut` |
| `edit.paste` — Paste | `crystalgui:general/action/paste` |
| `edit.redo` — Redo | `crystalgui:general/action/redo` |
| `edit.undo` — Undo | `crystalgui:general/action/undo` |
| `editor.deleteLines` — Delete Line | `crystalgui:general/action/delete` |
| `editor.zoomReset` — Reset Zoom | `crystalgui:general/action/reset` |
| `explorer.newFile` — New File… | `crystalgui:general/action/addFile` |
| `explorer.newFolder` — New Folder… | `crystalgui:general/action/addDirectory` |
| `explorer.refresh` — Reload from Disk | `crystalgui:general/action/refresh` |
| `graph.delete` — Delete | `crystalgui:general/action/delete` |
| `problems.jumpToSource` — Jump to Source | `crystalgui:general/action/edit` |
| `problems.showQuickFixes` — Show Quick-Fixes | `crystalgui:general/action/intentionBulb` |
| `surface.delete` — Delete | `crystalgui:general/action/delete` |
| `tree.copy` — Copy | `crystalgui:general/action/copy` |
| `tree.cut` — Cut | `crystalgui:general/action/cut` |
| `tree.delete` — Delete | `crystalgui:general/action/delete` |
| `tree.paste` — Paste | `crystalgui:general/action/paste` |
| `uibuilder.copyAttributes` — Copy Attributes | `crystalgui:general/action/copy` |
| `uibuilder.freeTransform` — Free Transform | `crystalgui:general/action/freeTransform` |
| `uibuilder.insert` — Insert… | `crystalgui:general/action/add` |
| `uibuilder.layers.remove` — Delete | `crystalgui:general/action/delete` |
| `uibuilder.layers.visible` — Show | `crystalgui:general/show` |
| `uibuilder.library.deleteGroup` — Delete Group | `crystalgui:general/action/delete` |
| `uibuilder.library.removeFromGroup` — Remove from Group | `crystalgui:general/action/delete` |
| `uibuilder.pasteAttributes` — Paste Attributes | `crystalgui:general/action/paste` |
| `workbench.preferences` — Preferences… | `crystalgui:general/action/settings` |
| `workbench.saveFile` — Save File | `crystalgui:general/action/save` |

```java
Command.of(PREFIX + "copy", "Copy").icon("crystalgui:general/action/copy");
```

> A menu holding any row with an icon reserves the column on **every** row, so labels stay
> aligned. That is the enabling behaviour, not a cost: it is what lets a menu icon five rows
> out of thirty and still read as one column.

## What is already drawable

228 SVGs ship under `assets/crystalgui/ui/icons/`, all `currentColor`:

| Family | Count | For |
|---|---:|---|
| `general/action/` | 51 | verbs — copy, cut, paste, delete, edit, add, expandAll, refactor |
| `nodes/ui/` | 47 | UI-builder widget kinds |
| `nodes/java/` | 46 | Java symbol kinds |
| `filetypes/` | 40 | per-extension file marks |
| (root) | 18 | `menu`, `folder`, `file-text`, `code`, `image`, `window-*`, `trash`, `stop`, `x` |
| `general/search/` | 10 | find, replace, match-case, regex |
| `general/` | 9 | misc chrome |
| `toolwindows/` | 7 | one per built-in panel |

`general/action/` is the best-stocked and the one that matters most here. 66 of the 228 files
are `*_dark` companions, and they are **not** duplicates to be collapsed: a JetBrains icon
carries its own palette, so the dark drawing is a genuinely different file rather than the
light one recoloured. Nothing references them by name — a command names the base
(`general/action/copy`) and `FileIconTheme.Variant` picks the drawing — so the pairing costs a
caller nothing and the set is used exactly as it ships.

## How many rows should get one

**Few.** VS Code puts no icons in menus at all. IntelliJ, counted off two of its own menus:

| Menu | Rows | Iconned |
|---|---:|---|
| Edit | ~29 | **5** — Undo, Redo, Cut, Copy, Edit as Table |
| File context | ~27 | **6** — Cut, Copy, Paste, Reformat Code, Reload from Disk, Compare With, Create Gist |

About a fifth, and the choice is not by importance: Delete, Rename, Select All and Find all go
bare while Create Gist gets a mark. What earns one is a mark the reader **already knows** —
scissors, the clipboard pair, the undo arrow — not a drawing invented for the row. A verb with
no conventional glyph is better bare than given a guess.

So the question is not coverage. For the record, matching each command's leading verb against
the verb families gives:

| | Verbs | Commands |
|---|---:|---:|
| Have a mark | 13 | 44 |
| **Need a new one** | **60** | **144** |

Covered: `copy`&nbsp;11, `delete`&nbsp;6, `paste`&nbsp;6, `show`&nbsp;5, `cut`&nbsp;5, `add`&nbsp;3, `save`&nbsp;2, `refresh`&nbsp;1, `run`&nbsp;1, `stop`&nbsp;1, `hide`&nbsp;1, `edit`&nbsp;1, `pin`&nbsp;1.

— but that 144 is the count of rows with no mark, not a list of drawings to commission. Held
to IntelliJ's restraint the real list is tiny; see the next section. The one thing the gap
does say is that what ships is *toolbar* vocabulary (copy, cut, paste, delete, run, stop,
refresh, pin) and the menu staples `undo` and `redo` are missing.

| Missing verb | Commands | Missing verb | Commands |
|---|---:|---|---:|
| `select` | 12 | `toggle` | 11 |
| `move` | 10 | `duplicate` | 9 |
| `close` | 7 | `frame` | 6 |
| `find` | 5 | `rename` | 4 |
| `split` | 4 | `new` | 4 |
| `remove` | 3 | `fold` | 3 |
| `go` | 3 | `insert` | 3 |
| `replace` | 3 | `zoom` | 3 |
| `restore` | 3 | `clear` | 2 |
| `switch` | 2 | `focus` | 2 |
| `next` | 2 | `open` | 2 |
| `previous` | 2 | `unfold` | 2 |

The leading verb is a sizing signal, never a mapping: `toggle`(11) wants a *comment* mark for
`toggleLineComment`, not a "toggle" one.

## The set in use

Held to the restraint above, and taking only marks a reader already knows. Every row is listed
in the table at the top of this file; this is the same set by menu, with what it leaves out.

| Menu | Rows with a mark | Of |
|---|---|---:|
| Edit | Undo, Redo, Cut, Copy, Paste | 15 |
| File | Save File | 3 |
| View | Reset Zoom | 11 |
| Explorer context | Reload from Disk | 4 |
| — (palette only) | Preferences… | |

Edit at 5 marks over 15 rows, against IntelliJ's 5 over ~29: denser, because our Edit menu is
half the length and carries the same five head rows. Every other row in every other menu stays
bare — Window is all splits and focus moves, Help is two rows, and View is nearly all toggles.
None has a conventional glyph, which is IntelliJ's own reason for leaving its equivalents alone.

**A toggle cannot show one either way**, and that is enforced rather than chosen: `MenuBuilder`
sets an icon only `if (resolved.getIcon() != null && !checkable)`, because the mark and the
checkmark are the same slot. So the 10 toggle commands are bare by construction, and View could
not be iconned much further even if it should be.

### The window system menu is a deliberate abstention

`window-minimize`, `window-maximize`, `window-restore` and `x` all ship and would fit
Restore/Minimize/Maximize/Close exactly. Win32 system menus have never carried icons, and the
window's own chrome buttons are drawing those same four marks a couple of centimetres away.

## Commands by area

**90 of the 188 appear in a menu; 98 are palette-only.** That is the first thing the icon pass
has to decide — the palette draws a row per command too, so "palette-only" is not "invisible",
and a command with no menu still shows up when someone searches for it.

`Menus` is where the command already appears; `—` means palette-only, which is a decision to
revisit separately from icons. `Keys` merges both places a binding can be declared — the
command's own `.binding(...)` and a `keymap().bind(spec, id)` call elsewhere. 119 of the 188
are bound; the 89 external sites are the older idiom the `binding` javadoc warns about.

### Text editor

<sub>`widget/texteditor` · 48</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `editor.addCaretAbove` | Add Caret Above | — | `Mod+Alt+Up` |
| `editor.addCaretAtNextOccurrence` | Add Caret At Next Occurrence | — | `Alt+J` |
| `editor.addCaretBelow` | Add Caret Below | — | `Mod+Alt+Down` |
| `editor.copy` | Copy | — | `Mod+C` |
| `editor.cut` | Cut | — | `Mod+X` |
| `editor.deleteLines` | Delete Line | — | `Mod+Shift+K` |
| `editor.duplicateLineDown` | Duplicate Line Down | — | `Mod+D`, `Shift+Alt+Down` |
| `editor.duplicateLineUp` | Duplicate Line Up | — | `Shift+Alt+Up` |
| `editor.excludeMatch` | Exclude Match | — | `Mod+Alt+E` |
| `editor.find` | Find… | MAIN_EDIT | `Mod+F` |
| `editor.find.close` | Close Find Bar | — | `Escape` |
| `editor.findNext` | Find Next | MAIN_EDIT | `F3` |
| `editor.findPrevious` | Find Previous | MAIN_EDIT | `Shift+F3` |
| `editor.findWordUnderCaret` | Find Word Under Caret | — | `Mod+F3` |
| `editor.fold` | Fold | — | `Mod+Shift+LBracket` |
| `editor.foldAll` | Fold All | MAIN_VIEW |  |
| `editor.foldRecursively` | Fold Recursively | — | `Mod+Shift+Multiply` |
| `editor.goToDefinition` | Go To Declaration | MAIN_VIEW | `Mod+B` |
| `editor.goToLine` | Go To Line… | MAIN_VIEW | `Mod+G` |
| `editor.insertLineAbove` | Insert Line Above | — | `Mod+Shift+Return` |
| `editor.insertLineBelow` | Insert Line Below | — | `Mod+Return` |
| `editor.joinLines` | Join Lines | — | `Mod+J` |
| `editor.moveLineDown` | Move Line Down | — | `Alt+Down` |
| `editor.moveLineUp` | Move Line Up | — | `Alt+Up` |
| `editor.nextProblem` | Next Problem | — | `F2`, `F8` |
| `editor.paste` | Paste | — | `Mod+V` |
| `editor.previousProblem` | Previous Problem | — | `Shift+F2`, `Shift+F8` |
| `editor.quickDocumentation` | Quick Documentation | MAIN_VIEW | `Mod+Q` |
| `editor.replace` | Replace… | MAIN_EDIT | `Mod+R` |
| `editor.replaceAll` | Replace All | MAIN_EDIT |  |
| `editor.replaceCurrent` | Replace | MAIN_EDIT |  |
| `editor.selectAll` | Select All | MAIN_EDIT | `Mod+A` |
| `editor.selectAllOccurrences` | Select All Occurrences | — | `Mod+Shift+L` |
| `editor.selectLine` | Select Line | — | `Mod+L` |
| `editor.showCodeActions` | Show Context Actions | MAIN_VIEW | `Alt+Enter` |
| `editor.toggleBlockComment` | Toggle Block Comment | MAIN_EDIT | `Shift+Alt+A` |
| `editor.toggleLineComment` | Toggle Line Comment | MAIN_EDIT | `Mod+Slash` |
| `editor.toggleMatchCase` | Match Case | — | `Alt+C` |
| `editor.togglePreserveCase` | Preserve Case | — | `Alt+E` |
| `editor.toggleRegex` | Regex | — | `Alt+X` |
| `editor.toggleSoftWrap` | Toggle Soft Wrap ⃰ | MAIN_VIEW | `Alt+Z` |
| `editor.toggleWholeWords` | Words | — | `Alt+W` |
| `editor.triggerSuggest` | Trigger Suggest | — | `Mod+Space` |
| `editor.unfold` | Unfold | — | `Mod+Shift+RBracket` |
| `editor.unfoldAll` | Unfold All | MAIN_VIEW |  |
| `editor.zoomIn` | Zoom In | MAIN_VIEW |  |
| `editor.zoomOut` | Zoom Out | MAIN_VIEW |  |
| `editor.zoomReset` | Reset Zoom | MAIN_VIEW |  |

### UI builder

<sub>`app/uibuilder` · 18</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `uibuilder.convertToSize` | Convert to Size | — |  |
| `uibuilder.copyAttributes` | Copy Attributes | — | `Alt+C` |
| `uibuilder.duplicateDown` | Duplicate Down | — | `Shift+Alt+Down` |
| `uibuilder.duplicateUp` | Duplicate Up | — | `Shift+Alt+Up` |
| `uibuilder.editText` | Edit Text | — | `F2` |
| `uibuilder.freeTransform` | Free Transform | — | `Mod+T` |
| `uibuilder.insert` | Insert… | — | `Shift+Space` |
| `uibuilder.inspectElement` | Inspect Element | — | `Ctrl+Shift+C` |
| `uibuilder.moveDown` | Move Down | — | `Alt+Down` |
| `uibuilder.moveUp` | Move Up | — | `Alt+Up` |
| `uibuilder.pasteAttributes` | Paste Attributes | — | `Alt+V` |
| `uibuilder.selectAll` | Select All | — | `Mod+A` |
| `uibuilder.selectChild` | Select First Child | — | `Down` |
| `uibuilder.selectNextSibling` | Select Next Sibling | — | `Right` |
| `uibuilder.selectParent` | Select Parent | — | `Up` |
| `uibuilder.selectPreviousSibling` | Select Previous Sibling | — | `Left` |
| `uibuilder.togglePreview` | Toggle Preview | — | `Ctrl+Alt+P` |
| `uibuilder.transformAgain` | Transform Again | — | `Mod+Shift+T` |

### Dock — panes and splits

<sub>`workbench/dock` · 18</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `dock.changeSplitterOrientation` | Change Splitter Orientation | EDITOR_TAB_CONTEXT |  |
| `dock.closeAllInGroup` | Close All Tabs in Group | EDITOR_TAB_CONTEXT |  |
| `dock.closeOthers` | Close Other Tabs | EDITOR_TAB_CONTEXT |  |
| `dock.closePanel` | Close | MAIN_FILE, EDITOR_TAB_CONTEXT | `Mod+W` |
| `dock.closeToTheRight` | Close Tabs to the Right | EDITOR_TAB_CONTEXT |  |
| `dock.focusNextGroup` | Focus Next Group | MAIN_WINDOW | `Mod+K` |
| `dock.focusPreviousGroup` | Focus Previous Group | MAIN_WINDOW | `Mod+Shift+K` |
| `dock.moveToOppositeGroup` | Move to Opposite Group | EDITOR_TAB_CONTEXT |  |
| `dock.nextTab` | Next Tab | MAIN_WINDOW | `Mod+PageDown` |
| `dock.openInNewWindow` | Open Tab in New Window | EDITOR_TAB_CONTEXT | `Shift+F4` |
| `dock.openInOppositeGroup` | Open in Opposite Group | EDITOR_TAB_CONTEXT |  |
| `dock.previousTab` | Previous Tab | MAIN_WINDOW | `Mod+PageUp` |
| `dock.splitAndMoveDown` | Split and Move Down | EDITOR_TAB_CONTEXT |  |
| `dock.splitAndMoveRight` | Split and Move Right | EDITOR_TAB_CONTEXT |  |
| `dock.splitDown` | Split Down | MAIN_WINDOW, EDITOR_TAB_CONTEXT | `Mod+Shift+Backslash` |
| `dock.splitRight` | Split Right | MAIN_WINDOW, EDITOR_TAB_CONTEXT | `Mod+Backslash` |
| `dock.toggleMaximize` | Toggle Maximize Group | MAIN_WINDOW | `Mod+M` |
| `dock.unsplit` | Unsplit | EDITOR_TAB_CONTEXT |  |

### Project explorer

<sub>`workbench/explorer` · 11</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `explorer.copyPath` | Copy Path | EXPLORER_CONTEXT |  |
| `explorer.copyRelativePath` | Copy Relative Path | EXPLORER_CONTEXT |  |
| `explorer.find` | Find in Project View | — |  |
| `explorer.goToFile` | Go to File… | MAIN_FILE | `Mod+P` |
| `explorer.newFile` | New File… | — | `Mod+N` |
| `explorer.newFolder` | New Folder… | — |  |
| `explorer.refresh` | Reload from Disk | EXPLORER_CONTEXT | `F5` |
| `explorer.restoreDeleted` | Restore Deleted File… | EXPLORER_CONTEXT |  |
| `explorer.selectOpenedFile` | Select Opened File | — |  |
| `workbench.configureEditorTabs` | Configure Editor Tabs… | EDITOR_GROUP_OPTIONS, EDITOR_TAB_CONTEXT |  |
| `workbench.preferences` | Preferences… | — | `Alt+Shift+S` |

### Node graph

<sub>`widget/graph` · 10</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `graph.clearSelection` | Deselect | MAIN_GRAPH | `Escape` |
| `graph.copy` | Copy | MAIN_GRAPH | `Mod+C` |
| `graph.createNode` | Create Node | MAIN_GRAPH | `Space` |
| `graph.cut` | Cut | MAIN_GRAPH | `Mod+X` |
| `graph.delete` | Delete | MAIN_GRAPH | `Delete`, `Backspace` |
| `graph.duplicate` | Duplicate | MAIN_GRAPH | `Mod+D` |
| `graph.frameAll` | Frame All | MAIN_GRAPH | `A` |
| `graph.frameSelection` | Frame Selection | MAIN_GRAPH | `F` |
| `graph.paste` | Paste | MAIN_GRAPH | `Mod+V` |
| `graph.selectAll` | Select All | MAIN_GRAPH | `Mod+A` |

### Window frames

<sub>`desktop/window` · 9</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `window.close` | Close | WINDOW_SYSTEM |  |
| `window.fullscreen` | Full Screen ⃰ | WINDOW_SYSTEM | `F11` |
| `window.maximize` | Maximize | WINDOW_SYSTEM |  |
| `window.minimize` | Minimize | WINDOW_SYSTEM |  |
| `window.move` | Move | WINDOW_SYSTEM |  |
| `window.pin` | Pin ⃰ | WINDOW_SYSTEM |  |
| `window.restore` | Restore | WINDOW_SYSTEM |  |
| `window.size` | Size | WINDOW_SYSTEM |  |
| `window.systemMenu` | Window Menu | — | `Alt+Minus` |

### Tree views

<sub>`widget/collection/tree` · 9</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `tree.collapseAll` | Collapse All | — | `Mod+Minus`, `Mod+Subtract` |
| `tree.copy` | Copy | — | `Mod+C` |
| `tree.cut` | Cut | — | `Mod+X` |
| `tree.delete` | Delete | — | `Delete` |
| `tree.duplicate` | Duplicate | — | `Mod+D` |
| `tree.expandAll` | Expand All | — | `Mod+Shift+Equals`, `Mod+Shift+Add` |
| `tree.expandSelected` | Expand Selected | — | `Mod+Equals`, `Mod+Add` |
| `tree.paste` | Paste | — | `Mod+V` |
| `tree.rename` | Rename… | — | `F2` |

### Surface (canvas pan/zoom)

<sub>`widget/surface` · 9</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `surface.copy` | Copy | — | `Mod+C` |
| `surface.cut` | Cut | — | `Mod+X` |
| `surface.delete` | Delete | — | `Delete`, `Backspace` |
| `surface.deselect` | Deselect | — | `Escape` |
| `surface.duplicate` | Duplicate | — | `Mod+D` |
| `surface.frameAll` | Frame All | — | `A` |
| `surface.frameSelection` | Frame Selection | — | `F` |
| `surface.paste` | Paste | — | `Mod+V` |
| `surface.selectAll` | Select All | — | `Mod+A` |

### UI builder — Layers

<sub>`app/uibuilder/style` · 7</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `uibuilder.layers.duplicate` | Duplicate | — |  |
| `uibuilder.layers.moveDown` | Move Down | — |  |
| `uibuilder.layers.moveToBottom` | Move to Bottom | — |  |
| `uibuilder.layers.moveToTop` | Move to Top | — |  |
| `uibuilder.layers.moveUp` | Move Up | — |  |
| `uibuilder.layers.remove` | Delete | — |  |
| `uibuilder.layers.visible` | Show ⃰ | — |  |

### UI builder — Library

<sub>`app/uibuilder/library` · 6</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `uibuilder.library.deleteGroup` | Delete Group | — |  |
| `uibuilder.library.newGroup` | New Group… | — |  |
| `uibuilder.library.newSubgroup` | New Group Inside… | — |  |
| `uibuilder.library.removeFromGroup` | Remove from Group | — | `Delete`, `Backspace` |
| `uibuilder.library.renameGroup` | Rename Group… | — |  |
| `uibuilder.library.toggleRows` | Show as Rows ⃰ | — |  |

### Desktop (CrystalOS)

<sub>`desktop` · 6</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `desktop.frameStats` | Toggle Frame Stats ⃰ | — | `F7` |
| `desktop.frameStatsDetail` | Frame Stats: Phase Breakdown ⃰ | — | `F8` |
| `desktop.showDesktop` | Show Desktop ⃰ | TASKBAR_CONTEXT, DESKTOP_CONTEXT |  |
| `desktop.switchWindow` | Switch Window | DESKTOP_CONTEXT | `Mod+Tab` |
| `desktop.switchWindowBack` | Switch Window (Back) | — | `Mod+Shift+Tab` |
| `desktop.taskbarDesigner` | Design Taskbar… | TASKBAR_CONTEXT | `Mod+Alt+T` |

### Scripts — console

<sub>`language/run/console` · 4</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `console.clear` | Clear All | CONTEXT |  |
| `console.removeScript` | Remove | RAIL_CONTEXT |  |
| `console.scrollToEnd` | Scroll to End | CONTEXT |  |
| `console.toggleSoftWrap` | Soft-Wrap ⃰ | CONTEXT |  |

### Workbench — editor tabs

<sub>`workbench` · 4</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `editor.tab.closeUnmodified` | Close Unmodified Tabs in Group | EDITOR_TAB_CONTEXT |  |
| `editor.tab.copyPath` | Copy Path | EDITOR_TAB_CONTEXT |  |
| `editor.tab.copyRelativePath` | Copy Relative Path | EDITOR_TAB_CONTEXT |  |
| `editor.tab.renameFile` | Rename File… | EDITOR_TAB_CONTEXT |  |

### Menu bar and chrome

<sub>`workbench/chrome/menu` · 4</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `help.about` | About | MAIN_HELP |  |
| `help.documentation` | Documentation | MAIN_HELP |  |
| `view.mainMenu` | Main Menu | — | `F10` |
| `workbench.showCommands` | Show All Commands | MAIN_VIEW | `Mod+Shift+P` |

### Shader graph — Blackboard

<sub>`app/shadergraph/blackboard` · 3</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `blackboard.deleteProperty` | Delete Property | BLACKBOARD_CONTEXT | `Delete` |
| `blackboard.duplicateProperty` | Duplicate Property | BLACKBOARD_CONTEXT | `Mod+D` |
| `blackboard.renameProperty` | Rename Property | BLACKBOARD_CONTEXT | `F2` |

### Clipboard and the registry's own

<sub>`core/command` · 3</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `edit.copy` | Copy | MAIN_EDIT |  |
| `edit.cut` | Cut | MAIN_EDIT |  |
| `edit.paste` | Paste | MAIN_EDIT |  |

### Workbench application

<sub>`workbench/app` · 3</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `workbench.restoreLayout` | Restore Window Layout | MAIN_WINDOW | `Mod+O` |
| `workbench.saveFile` | Save File | MAIN_FILE | `Mod+S` |
| `workbench.saveLayout` | Save Window Layout | MAIN_WINDOW | `Mod+Shift+S` |

### Tool-window view modes

<sub>`workbench/toolwindow` · 3</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `toolwindow.viewMode.docked` | Dock Pinned | — |  |
| `toolwindow.viewMode.floating` | Float | — |  |
| `toolwindow.viewMode.windowed` | Window | — |  |

### UI builder — Hierarchy

<sub>`app/uibuilder/panel` · 2</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `uibuilder.copySelector` | Copy Selector | CONTEXT_MENU |  |
| `uibuilder.selectInHierarchy` | Select Canvas Selection | — |  |

### Undo

<sub>`core/undo` · 2</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `edit.redo` | Redo | MAIN_EDIT |  |
| `edit.undo` | Undo | MAIN_EDIT |  |

### Scripts — run and stop

<sub>`language/run` · 2</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `script.run` | Run Script | — | `Shift+F10` |
| `script.stop` | Stop Script | — | `Mod+F2` |

### Problems

<sub>`workbench/chrome/problems` · 2</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `problems.jumpToSource` | Jump to Source | — | `F5` |
| `problems.showQuickFixes` | Show Quick-Fixes | — | `Alt+Enter` |

### Tool-window rails

<sub>`workbench/stripe` · 2</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `toolwindow.stripe.hide` | Hide | — |  |
| `toolwindow.stripe.showNames` | Show Tool Window Names ⃰ | — |  |

### Shader graph

<sub>`app/shadergraph` · 1</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `shadergraph.viewGenerated` | View Generated Shader | — |  |

### Java — JDK sources

<sub>`language/java` · 1</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `java.downloadJdkSources` | Download JDK Sources | — |  |

### Scripts — mappings view

<sub>`language/run/view` · 1</sub>

| Id | Label | Menus | Keys |
|---|---|---|---|
| `script.remapToReadable` | Remap to Readable Names | MAIN_EDIT |  |

<sub>⃰ a toggle — reports its own checked state, so its row draws a checkmark. Whether a
checkmark and an icon can share one row is an open question for the icon pass.</sub>

## Commands built at runtime

These are not declared anywhere — one is minted per panel, per file, per contribution. They
cannot be given an icon at a call site; each has to take one from the thing it names.

| Site | Shape | Where its icon would come from |
|---|---|---|
| `WorkbenchMenus` | `workbench.toolWindow.<typeId>` | the tool window's own descriptor icon |
| `WorkbenchMenus` | `workbench.recent.<path>` | the file-icon theme, by extension |
| `WorkbenchMenus` | `workbench.editor.<identity>` | the open document's `DocumentKind` icon |
| `StripeView` | one per rail button | the panel descriptor, same as the rail draws |
| `Workbench` | `Show <panel>` per tool window | same descriptor |
| `RemoteCommands` | whatever a server contributes | the wire — needs an icon field in the protocol |

The last row is the only one that needs a protocol change; the rest are a lookup the builder
already has in hand.

## How this was produced

Parsed rather than grepped, because ids are rarely literals — `Command.of(PREFIX + "save", …)`
and `Command.of(SAVE_ALL, …)` are both common, and four wrapper factories
(`editorCommand`, `mode`, `command`, and `Command.of` itself) declare commands too. The sweep
resolves `String` constants, including concatenated ones, and strips comments first — three of
the apparent icon calls were javadoc examples in `Command.java`.

Per-file counts were reconciled against a raw `grep -c 'Command.of('` so nothing was dropped
silently; every file balances once the wrapper calls and the runtime sites above are accounted
for.
