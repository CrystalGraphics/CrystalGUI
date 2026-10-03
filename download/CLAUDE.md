# Runtime downloads: one file, repairable after release

> Loads itself: Claude Code reads this file the first time an agent reads any file in this folder or below. Moved verbatim from [`AGENTS.md`](../AGENTS.md), which keeps the rules every session needs.

## Runtime downloads: one file, repairable after release

**Every address CrystalGUI downloads from is in [`download/locations.json`](locations.json),
and nowhere else** — [`download/README.md`](README.md) is the guide to editing it. A URL compiled
into a shipped class cannot change after release, and every host eventually moves; so code names an *id* —
`fabric/intermediary/1.20.1`, `engine/17/<jar>` — and the file says where it lives, as URLs or as Maven
coordinates in a named repository. What an artifact *is* (its id and pinned digest) is kept apart from
where it *lives*, as Bazel's `urls = [...]` beside a hash and Maven's repositories do, so moving a whole
host is one line.

| Copy | Where | What it may do |
|---|---|---|
| **The jar's** | `assets/crystalgui/download/locations.json` in `crystalgui-<version>.jar`, from `:core:processResources` | Names the ids a jar knows, and holds **the only digests it trusts** |
| **Master's** | read from the file's own `self` addresses; kept at `<cache>/download/locations.json`, under the cache root a host hands `DownloadLocations.useCacheRoot` | **Adds addresses** — URLs for ids the jar lists, and more addresses for a repository. Fetched before a session's first download when a day old, and once more when every URL has failed. Its digests, any id the jar lacks, and a copy in another `format` are ignored |
| **An override** | `-Dcrystalgui.download.locations=<file>` | The same rule, tried first: a pack's own mirror, an offline machine |

**Master's copy can move an artifact and never change one**, which is why nothing is signed: a bad address
there costs a failed download, never a wrong file. `-Dcrystalgui.download.remote=false` stops it being
read; the tests run that way.

| When | Do |
|---|---|
| **A link has died** | Add a working URL to the download, or an address to its repository, and push to master. Every jar built since this file existed picks it up within a day, or on its next failure |
| **A pin changes** — a band re-pinned, a new Minecraft version | Edit the download. `checkDownloadLocations`, in `check`, fails when the engines are not the resolved bands and prints the `bands` block to paste — and when a loader node's own version has no pinned script names (Mojang's `client.txt`, MCPConfig, intermediary or MCP stable); `verifyScriptingCoverage`, run by the Release workflow, checks every release in each node's range. Pin with the publisher's digest, and a Fabric intermediary also `from` the mirror. Only jars built after the edit carry the new pin |
| **Before a release** | `./gradlew verifyDownloadLocations` fetches every URL and checks what it serves. Online; a dead extra URL is a warning, a download with no working URL a failure |
| **The mirror changes** | `./gradlew stageDownloadMirror` collects every download that comes from the `mirror` repository — this repository's `download-mirror` release — verified, and prints the `gh release` commands. Only what `mirrorLicences` in the root `build.gradle.kts` covers may be mirrored — never MCP's, MCPConfig's or Mojang's data. Publishing is by hand |

The machinery is `com.crystalgui.core.cache`, so any module can download; the language stack is only its
first user. Code reaches a location through `Downloads.located(id)` or
`MappingCoordinates.Source.located(id)`; `Downloads.from(url)` is for an address only known at run time,
such as one Mojang's manifest names. `DownloadUrlsLiveInOneFileTest`, in core's `headlessTest`, fails on an
address literal in code anywhere in `core/`, `language/` or a loader's sources, on an id a host asks for
that the shipped file does not list, and when the runtime's reading of the file stops expanding to the
addresses the build verifies.

---

Also loaded with this folder:

@README.md
