; xml/locals.scm
;
; Vendored from nvim-treesitter, Apache License 2.0.
;   https://github.com/nvim-treesitter/nvim-treesitter/blob/master/queries/xml/locals.scm
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

; --- from queries/xml/locals.scm ---
; tags
(elementdecl
  (Name) @local.definition.type)

(elementdecl
  (contentspec
    (children
      (Name) @local.reference)))

(AttlistDecl
  .
  (Name) @local.reference)

(STag
  (Name) @local.reference)

(ETag
  (Name) @local.reference)

(EmptyElemTag
  (Name) @local.reference)

; attributes
(AttDef
  (Name) @local.definition.field)

(Attribute
  (Name) @local.reference)

; entities
(GEDecl
  (Name) @local.definition.macro)

(EntityRef
  (Name) @local.reference)
