# Vendored build-time dependencies

This directory is a local, flat-file Maven repository (referenced from `pom.xml` via a
`file://${project.basedir}/libs` `<repository>`) for dependencies that can no longer be resolved
from a public Maven repo at build time.

## `invsee-plus-plus_common-0.31.22`

`repo.repsy.io/mvn/jannyboy11/minecraft` (where this project previously got
`invsee-plus-plus_common`) started requiring HTTP Basic Auth for every request, including plain
metadata reads, some time around October 2026 — breaking CI and any fresh clone. InvSee++'s other
publish target, GitHub Packages, also requires authentication for Maven (true for every package
there, public or not), so switching repos doesn't avoid needing credentials either.

`invsee-plus-plus_common-0.31.22.jar` here is re-packaged from the publicly downloadable GitHub
Release asset `InvSee++.jar` (v0.31.22 — the full plugin build) at
https://github.com/Jannyboy11/InvSee-plus-plus/releases/tag/v0.31.22 — it contains just the
`com.janboerman.invsee.spigot.api.*` and `com.janboerman.invsee.spigot.internal.*` packages (the
same set the real `invsee-plus-plus_common` module published), stripped of everything else in the
release jar. It is used at `provided` scope only (compile-time API, never shaded into our output
jar) — see the README's license section.

If InvSee++'s API changes, re-extract from a newer release jar:
```bash
curl -sL -o InvSee++.jar "https://github.com/Jannyboy11/InvSee-plus-plus/releases/download/vX.Y.Z/InvSee%2B%2B.jar"
mkdir extracted && cd extracted && unzip -q ../InvSee++.jar
jar cf invsee-plus-plus_common-X.Y.Z.jar com/janboerman/invsee/spigot/api com/janboerman/invsee/spigot/internal
```
then drop the jar (and a matching `.pom`, copy the one next to this file and bump the version) into
`libs/com/janboerman/invsee/invsee-plus-plus_common/X.Y.Z/`, and update the version in `pom.xml`.
