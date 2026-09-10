rootProject.name = "cgui-mc1201-build-logic"

// The single-jar build lives in CrystalGraphics and is SHARED rather than copied — see
// CrystalGraphics/singlejar-logic/README.md. Included from here rather than from the root settings
// because it is this build that compiles against its classes.
includeBuild("../../CrystalGraphics/singlejar-logic")
