// Machine-local settings — paths and switches true of ONE checkout and of nobody else's.
//
// `local.properties` is gitignored. Anything in it is a fact about the machine the build is running
// on: where a game client is installed, which launcher, which directory to drop a jar into. Those
// must never reach the remote, and equally must not be retyped on every invocation as `-P` flags.
//
//   cp local.properties.example local.properties     # then edit
//   ./gradlew deploySingleJars
//
// EVERY key in the file is loaded, whatever it is called. Nothing here enumerates them, so adding a
// setting is editing one file and reading it by name wherever it is wanted.
//
// The cost of that is real and worth stating rather than discovering: a key nobody set is ABSENT from
// `extra`, not null, so reading one that may be unset must ask first —
//
//     val dir: String? = if (extra.has(key)) extra[key] as? String else null
//
// — and a misspelled key is silence rather than an error. `local.properties.example` is where a key
// gets explained; it is documentation and not a schema, so the build neither reads it nor stops you
// using a key missing from it.
//
// Read here rather than per-subproject so the file is parsed once and every project sees the same
// answer, and so an absent file is normal — a fresh clone has no local settings and must still build.

val localSettingsFile = rootProject.file("local.properties")
val localSettings = java.util.Properties().apply {
    if (localSettingsFile.isFile) localSettingsFile.inputStream().use { load(it) }
}

allprojects {
    for (name in localSettings.stringPropertyNames()) {
        extra[name] = localSettings.getProperty(name)
    }
}

if (localSettingsFile.isFile) {
    logger.info("[cgui] local settings from {}: {}", localSettingsFile,
            localSettings.stringPropertyNames().sorted())
}
