# Runtime downloads

[`locations.json`](locations.json) is where CrystalGUI downloads everything from — today Minecraft's name
mappings, the engine jars a jar does not carry and the JDK sources. **No download address is written anywhere
else**, and every released jar re-reads the copy on `master` — so a dead link is fixed for players by
editing this file, with no new release.

## A link has died

Add a working address and push to `master`. A jar re-reads this file before its first download of the
day, and again as soon as every address it knows has failed.

- **One download:** add a URL to its `urls`.
- **A whole host** — Maven Central, Forge's or Fabric's Maven: add an address to its repository, and every
  download from it gets the new one.

  ```json
  "maven-central": ["https://repo1.maven.org/maven2/{path}", "https://repo.maven.apache.org/maven2/{path}"]
  ```

The copy on `master` can move a download and never change one. A jar checks every file against the digest
it was built with and ignores the digests here, so a wrong address costs a failed download, never a wrong
file. A changed digest, or a new download, reaches only jars built after the edit.

## What is in it

| Key | Holds |
|---|---|
| `self` | Where released jars read this file from. Changing it moves them all |
| `repositories` | A name and its addresses. `{path}` is where Maven keeps a file (`org/benf/cfr/0.152/cfr-0.152.jar`), `{file}` only its name |
| `files` | A download by id: its `urls`, or its `maven` coordinates found `from` named repositories — and a `digest` (`sha1:`, `md5:` or `gitblob:`) wherever one can be known |
| `engines` | Each engine band's jars as Maven coordinates and their digest, all `from` the same repositories. A jar's id is `engine/<band>/<file>` |
| `format` | The layout. A jar ignores a copy in a format it was not built to read |

An id holding `{minecraft}` or `{java}` covers every version, and is never pinned.

`mirror` is this repository's `download-mirror` release. It holds only what a licence lets us
redistribute — `mirrorLicences` in the root `build.gradle.kts` — and never MCP's, MCPConfig's or Mojang's
mappings.

## Checks

```
./gradlew checkDownloadLocations    # well-formed, and the engines are the resolved bands; part of check
./gradlew verifyDownloadLocations   # every address answers with the right bytes; online
./gradlew stageDownloadMirror       # the files for the download-mirror release, and the commands to upload them
```

When an engine band is re-pinned, `checkDownloadLocations` fails and prints the `bands` block to paste.
