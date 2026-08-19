; html/indents.scm
;
; Vendored from nvim-treesitter, Apache License 2.0.
;   https://github.com/nvim-treesitter/nvim-treesitter/blob/master/queries/html_tags/indents.scm
;
; MODIFICATIONS: the upstream files are split across an `; inherits:` chain that this engine does not
; implement -- there is no query-inheritance mechanism here, and adding one to read six files would be
; machinery for a feature nobody asked for. The chain is therefore RESOLVED AT VENDORING TIME by
; concatenating the inherited files ahead of the language's own, in inheritance order, which is exactly
; what nvim-treesitter's loader does at runtime. Nothing else is changed: no pattern is edited, removed
; or reordered. Where this engine needs a deviation it is applied at LOAD time, in Queries.java, so the
; deviation stays one reviewable table rather than a fork of somebody else's query.
;
; See THIRD-PARTY.md.

; --- from queries/html_tags/indents.scm ---
((element
  (start_tag
    (tag_name) @_not_void_element))
  (#not-any-of? @_not_void_element
    "area" "base" "basefont" "bgsound" "br" "col" "command" "embed" "frame" "hr" "image" "img"
    "input" "isindex" "keygen" "link" "menuitem" "meta" "nextid" "param" "source" "track" "wbr")) @indent.begin

(element
  (self_closing_tag)) @indent.begin

((start_tag
  (tag_name) @_void_element)
  (#any-of? @_void_element
    "area" "base" "basefont" "bgsound" "br" "col" "command" "embed" "frame" "hr" "image" "img"
    "input" "isindex" "keygen" "link" "menuitem" "meta" "nextid" "param" "source" "track" "wbr")) @indent.begin

; These are the nodes that will be captured when we do `normal o`
; But last element has already been ended, so capturing this
; to mark end of last element
(element
  (end_tag
    ">" @indent.end))

(element
  (self_closing_tag
    "/>" @indent.end))

; Script/style elements aren't indented, so only branch the end tag of other elements
(element
  (end_tag) @indent.branch)

[
  ">"
  "/>"
] @indent.branch

(comment) @indent.ignore
