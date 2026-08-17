; java/indents.scm
;
; Vendored from nvim-treesitter, Apache License 2.0.
;   https://github.com/nvim-treesitter/nvim-treesitter/blob/master/queries/java/indents.scm
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

; --- from queries/java/indents.scm ---
; format-ignore
[
  ; ... refers to the portion that this indent query will have effects on
  (class_body)                        ; { ... } of `class X`
  (enum_body)                         ; { ... } of `enum X`
  (interface_body)                    ; { ... } of `interface X`
  (constructor_body)                  ; { `modifier` X() {...} } inside `class X`
  (annotation_type_body)              ; { ... } of `@interface X`
  (block)                             ; { ... } that's not mentioned in this scope
  (switch_block)                      ; { ... } in `switch X`
  (array_initializer)                 ; [1, 2]
  (argument_list)                     ; foo(...)
  (formal_parameters)                 ; method foo(...)
  (annotation_argument_list)          ; @Annotation(...)
  (element_value_array_initializer)   ; { a, b } inside @Annotation()
] @indent.begin

(expression_statement
  (method_invocation) @indent.begin)

[
  "("
  ")"
  "{"
  "}"
  "["
  "]"
] @indent.branch

(annotation_argument_list
  ")" @indent.end) ; This should be a special cased as `()` here doesn't have ending `;`

"}" @indent.end

(line_comment) @indent.ignore

[
  (ERROR)
  (block_comment)
] @indent.auto
