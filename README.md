# NMvn — one version control plane for many Maven repositories

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](LICENSE)
[![Java: 1.8+](https://img.shields.io/badge/Java-1.8+-orange.svg)](https://openjdk.org/)
[![Nuts Package Manager](https://img.shields.io/badge/Nuts-1.1+-green.svg)](https://github.com/thevpc/nuts)

**NMvn treats a set of independent Maven checkouts as one dependency graph, so you can
inspect, align, bump and release their versions in a single reviewed operation —
without merging the repositories into a monorepo.**

Maven is excellent inside one reactor. Real products often span several reactors:
`platform-parent`, `service-api`, `service-core`, `service-jobs`, a shared BOM, a
few plugins — each in its own Git repository, each with its own release cadence.
NMvn is the missing layer between them.

```text
  repo A            repo B              repo C
  parent POM   ←──  service-api   ←──  service-jobs
      │                 │
      └── BOM ──────────┘
           ╲
            ╲  NMvn sees this whole graph,
             ╲ audits it, and edits every affected pom.xml
              ╲ in one dry-runnable pass.
```

It has two parts:

1. **`nmvn`** — the CLI (`workset`, `version`, `clean`, Maven pass-through, jar diff).
2. **`nmvn-lib`** — the embeddable Java library behind it: POM scanning, property
   resolution, dependency graph, discrepancy checks and non-destructive POM editing.

> **In a hurry?** [CHEATSHEET.md](CHEATSHEET.md) lists the common commands in one page.

---

## Why NMvn exists

### The work it removes

| What is painful by hand | What NMvn does instead |
|---|---|
| Find every `pom.xml` across 5–50 checkouts and remember which are generated, ignored or vendored. | Walks workset roots, honours `.gitignore` and common build folders, and prints exactly what it found. |
| Follow `<version>${common.version}</version>` through parent POMs, child overrides and local `~/.m2` parents/BOMs. | Resolves property chains, inheritance, child overrides and BOM imports before deciding anything. |
| Bump a library and then hunt every consumer's dependency, parent, plugin and BOM-import reference. | Builds a workspace graph and updates only the references that actually point at the changed artifact. |
| Decide whether consumers' *own versions* should move too. | Makes that an explicit policy: `none`, `cascade-references-only` (default) or `cascade-versions`. |
| Release a suite: strip every `-SNAPSHOT`, realign internal references, keep inherited modules consistent. | `version release` does the whole set, optionally refusing external snapshots. |
| Notice mixed release/snapshot modules, version drift, parent mismatches and cycles. | `version check` applies ten diagnostic rules with file/line context and JSON output. |
| Edit dozens of POMs without reformatting them or losing comments. | Edits matching XML text, preserves layout and comments, and shows a unified diff first. |
| Trust a mass edit after it is applied. | `--dry` previews; apply stages files, checks for concurrent edits, keeps backups and attempts rollback. |
| Repeat the same multi-repo setup in every shell and every CI job. | A named **workset** stores roots and policy once; `-w <name>` reuses it anywhere. |

### What “added value” means concretely

- **One graph, many repositories.** No monorepo migration, no aggregator POM, no
  changes to how the projects build or publish.
- **Safe by construction.** Every mutation has a dry run, a diff, a completeness
  gate, ambiguity detection and an apply-time recheck.
- **Policy, not guesswork.** The cascade mode is explicit, so a routine library bump
  does not silently advance every consumer's version.
- **Auditable.** Every applied change is recorded in a version history with the Git
  commit hash.
- **Scriptable.** Plain text for humans, JSON for CI, and an embeddable Java API.

### When NMvn is a good fit

- Your product is **several Maven repositories** that must stay version-compatible.
- You maintain a **company parent POM or BOM** consumed by many projects.
- You publish a **library plus applications** that depend on it.
- You need a **pre-release or pre-merge audit** across repos.
- You want a **CI guard** against version drift, not just a build.
- You keep a **conceptual monorepo** without physically merging repositories.

### When it is not the right tool

- You have one ordinary multi-module reactor and only ever run `mvn versions:set`.
  The Maven Versions Plugin is simpler.
- You want Git tagging, deployment and rollback of a single project. Use the Maven
  Release Plugin or your CI release pipeline.
- You want automatic third-party upgrades from Maven Central as pull requests.
  Use Renovate or Dependabot.
- You need remote parent/BOM resolution, full Maven model building or effective-POM
  emulation. NMvn reads POMs and local `~/.m2` metadata; it is not Maven itself.

---

## Install and run

NMvn is a [Nuts](https://github.com/thevpc/nuts) application
(`net.thevpc.nmvn:nmvn`):

```bash
# Run NMvn through an installed Nuts runtime.
nuts nmvn version scan

# Run it from a Nuts development workspace (uses the local ~/.m2 build);
# -y accepts the runtime re-installation prompt non-interactively.
nuts-dev -y nmvn version scan
```

The rest of this document writes `nmvn ...` for short.

Nuts session options are accepted anywhere on the command line:

| Option | Effect |
|---|---|
| `--dry` (or `-D`) | Plan and print the diff; write nothing. |
| `-y` | Auto-confirm prompts. |
| `--json` | Machine-readable output. |
| `--force` | Allow bumping an artifact that is already a `-SNAPSHOT`. |

---

## Quick start

```bash
# 1. See which Maven projects NMvn finds from the current directory,
#    their effective versions, their references and their dependents.
nmvn version scan

# 2. Audit those projects for version drift, mixed snapshots and cycles.
#    This is read-only.
nmvn version check

# 3. Preview a patch bump of one artifact: 2.0.0 → 2.0.1-SNAPSHOT.
#    --dry means "show the diff, do not write".
nmvn version bump com.example:core -p --root . --dry

# 4. Apply exactly the change reviewed in step 3.
nmvn version bump com.example:core -p --root .
```

For repeated use, save the roots as a workset once and select it with `-w`:

```bash
# Create a named workset with absolute roots (safe to use from any directory).
nmvn workset set backend \
  --root /work/service-api \
  --root /work/service-core \
  --root /work/service-jobs

# Scan the saved workset instead of repeating --root.
nmvn version scan --workset backend
```

A bump requires an artifact selector. `--all` is deliberate and bumps every artifact
in the workset. Every mutating example below is shown with `--dry` first; remove it
only after reading the diff.

---

## Core concepts

### Worksets

A **workset** is a reusable list of roots plus policy:

- **Local** — `workset.tson` or `nmvn.tson` in the project directory. Relative roots
  are fine because the file travels with the repository.
- **Named/global** — `<name>.tson` in the Nuts configuration folder, shared across
  directories. Prefer **absolute roots** here: relative roots are resolved against
  the directory you run from, not against the file.
- **Selection** — `-w <name>` / `--workset <name|file>`. Without `-w`, NMvn looks for
  `./workset.tson`, `./nmvn.tson`, `./.nmvn/config.tson`, then the global
  `workset.tson` / `nmvn.tson`.
- **One-off** — `--root <dir>` (repeatable) replaces the workset roots for that call.

A global workset can make NMvn ignore your current directory. If a command touches
the wrong projects (or none), run `nmvn workset path` and `nmvn version scan` with
the same options; together they show which file was used and what was discovered.

### The workspace graph

The scanner parses each POM into an artifact model and builds edges for:

- direct dependencies,
- `dependencyManagement` entries and BOM imports,
- `<parent>` references,
- plugin declarations.

It then resolves `${property}` versions through local parent chains, child
overrides, properties and locally available (`~/.m2`) parent/BOM POMs. The result is
one graph spanning all roots. An artifact with the same `groupId:artifactId` found
twice is rejected as **ambiguous** rather than silently picking one.

### Cascade policies

When one artifact changes, “what else changes?” is a policy decision:

| Policy | Own version | References to it | Dependents' own versions |
|---|---|---|---|
| `none` | changed | untouched | untouched |
| `cascade-references-only` (default) | changed | updated | untouched |
| `cascade-versions` | changed | updated | advanced transitively |

Use the default for a normal library patch. Use `cascade-versions` when the suite is
released in lockstep. Use `none` when you deliberately want one POM changed and will
coordinate consumers yourself.

Pass the policy as `--cascade=<mode>` or `-c=<mode>` (with `=`), or use
`--cascade-versions`, `--cascade-references-only`, `--no-cascade`.

### Safety model

Before writing, NMvn:

1. refuses to mutate from an **incomplete scan** (an unreadable POM is an error for
   mutations, even if read-only scans merely warn),
2. rejects **ambiguous** artifacts and **cycles** (a scan also fails on cycles),
3. validates XML and the Maven POM namespace, and refuses prefixed/foreign Maven
   elements,
4. checks that each POM still has the bytes it was planned against, so a concurrent
   edit is not overwritten,
5. stages replacements beside each POM, keeps backups, and attempts rollback if a
   replacement fails.

This is not a substitute for version control. Keep the worktree clean and review the
diff. A process or machine failure mid-apply can still leave a partial change.

---

## Workflow recipes

The examples use `backend` as a workset name and illustrative coordinates. Replace
them with your own.

### 1. Manage several repositories as one

```bash
# Create/update a named workset. Absolute roots are resolved from any directory.
nmvn workset set backend \
  --root /work/service-api \
  --root /work/service-core \
  --root /work/service-jobs \
  --exclude "**/generated/**" \
  --default-increment patch \
  --cascade-policy cascade-references-only

# List the roots stored in the workset and whether they still exist.
nmvn workset root list --workset backend

# Scan the saved workset: artifacts, versions, references, dependents.
nmvn version scan --workset backend

# Audit the saved workset without modifying anything.
nmvn version check --workset backend
```

`workset set` replaces the stored root list; `--exclude` patterns are appended.

Add a checkout later without recreating the workset:

```bash
# Add one root, persist it, then immediately rescan the workset.
nmvn workset add-root /work/service-ui --workset backend --scan

# Remove a root and rescan what remains.
nmvn workset remove-root /work/legacy --workset backend --scan
```

Inspect or hand-edit the configuration:

```bash
# Print the TSON configuration of the workset.
nmvn workset get backend

# Print the absolute path of the file NMvn would use (and whether it exists).
nmvn workset path backend

# Open that file in $EDITOR/$VISUAL, creating it if needed.
nmvn workset edit backend

# List all named worksets in the Nuts configuration folder.
nmvn workset list

# List recently used workset files, flagging missing ones.
nmvn workset list --recent

# Same list as JSON for scripts.
nmvn workset list --recent --json
```

### 2. Align an external dependency without changing project versions

`check` finds the drift; `update` applies one exact target version everywhere the
dependency, parent or BOM is referenced. The default cascade leaves consumers' own
versions alone.

```bash
# Show every discrepancy in the workset before touching it.
nmvn version check --workset backend

# Preview aligning jackson-databind to exactly 2.17.2 across all roots.
nmvn version update com.fasterxml.jackson.core:jackson-databind#2.17.2 \
  --workset backend --dry

# Apply the alignment reviewed above.
nmvn version update com.fasterxml.jackson.core:jackson-databind#2.17.2 \
  --workset backend
```

### 3. Bump one module and choose the blast radius

```bash
# Preview a patch bump of one library; consumers' references follow,
# but consumers' own project versions do not move.
nmvn version bump com.acme:shared-lib -p --workset backend --dry

# Apply the reviewed bump.
nmvn version bump com.acme:shared-lib -p --workset backend

# Broader coordinated cycle: also advance dependent projects to snapshots.
nmvn version bump com.acme:shared-lib -p --cascade-versions \
  --workset backend --dry

# Edit only the selected artifact's POM; do not update downstream references.
nmvn version bump com.acme:shared-lib -p --cascade=none \
  --workset backend --dry

# Bump several independent artifacts in one reviewed operation.
nmvn version bump -a com.acme:shared-lib -a com.acme:service-api \
  --minor --workset backend --dry

# Bump every artifact in the workset (lockstep). Deliberately explicit.
nmvn version bump --all -p --workset backend --dry
```

An artifact already on a snapshot version is not advanced again by default. This is
intentional idempotence; use `--force` when another snapshot bump is really wanted:

```bash
# Advance an artifact that is already -SNAPSHOT (otherwise a no-op).
nmvn version bump com.acme:shared-lib -p --force --workset backend --dry
```

Without `-p`/`-m`/`-M`, the workset's `defaultIncrement` is used (`minor` unless
configured). A bump with neither an artifact selector nor `--all` is rejected.

### 4. Upgrade a shared parent or BOM

```bash
# Preview moving every child to company-parent 5.2.0 and realigning parent refs.
nmvn version update com.acme:company-parent#5.2.0 --workset backend --dry

# Apply the parent upgrade reviewed above.
nmvn version update com.acme:company-parent#5.2.0 --workset backend
```

### 5. Release the workset

`release` converts every `-SNAPSHOT` artifact in the scanned roots to its release
version (`6.1.0-SNAPSHOT` → `6.1.0`) and updates workspace references to those
artifacts. Modules that inherit their version from `<parent>` are released through
the parent reference; `${project.version}` references stay as they are.

```bash
# Preview the release of every snapshot in the workset.
nmvn version release --workset backend --dry

# Apply the release after reviewing the diff.
nmvn version release --workset backend

# Refuse the release if any unmanaged external snapshot dependency remains.
nmvn version release --strict --workset backend --dry

# Release everything, but give one artifact a specific release version.
nmvn version release -a com.acme:shared-lib#2.4.0 --workset backend --dry
```

`-a` does **not** restrict which snapshots are released: all snapshots in the scanned
roots are still released, and `-a` only overrides the target version of the listed
artifacts. To release a subset, narrow the scan instead (`--root`, `--exclude`, or a
dedicated workset).

After a release, open the next development cycle:

```bash
# After the release: bump the parent to the next minor snapshot and move
# dependent projects to snapshots as well.
nmvn version bump com.acme:parent -m --cascade-versions --workset backend --dry
```

### 6. Guard CI

```bash
# Produce a JSON audit report: hasErrors, hasWarnings, issueCount, issues, scanWarnings.
nmvn version check --workset backend --json

# Also fail the command when warnings are present.
nmvn version check --workset backend --fail-on-warning --json
```

When launched through Nuts, the process exit code is currently `0` even when `check`
reports errors (see [Known limitations](#known-limitations)). Until that is fixed,
have CI read `hasErrors` / `hasWarnings` from the JSON output.

### 7. Clean many checkouts

```bash
# Run Maven's own `clean` in every scanned project directory.
nmvn clean --workset backend

# Fast path: delete each scanned project's target/ directly, without starting Maven.
nmvn clean --simple --workset backend

# Clean only specific roots and exclude a subtree.
nmvn clean --root /work/service-core --exclude "**/skipme/**"

# Include projects normally hidden by .gitignore/default ignores (use with care).
nmvn clean --no-ignore
```

Positional arguments to `clean` are **roots**, not Maven goals. `nmvn clean install`
fails with `Root does not exist: install`; use `nmvn build clean install`.

### 8. Use Maven itself through the same command

Anything that is not an NMvn command is handed to the embedded Maven (3.6.3), so NMvn
can stand in for `mvn`:

```bash
# Run Maven install in the current project, skipping tests.
nmvn install -DskipTests

# Run Maven package with the prod profile.
nmvn package -Pprod

# Force Maven's clean + install even though `clean` is also an NMvn command.
nmvn build clean install

# Run a parallel Maven build (4 threads) for the current reactor.
nmvn build -T 4 clean install

# Download an artifact and its dependencies into ~/.m2.
nmvn get com.google.guava:guava:33.0.0-jre

# Download from an explicit repository URL/name instead of Maven Central.
nmvn get com.example:lib:1.2.3 vpc-public-maven
```

### 9. Compare two jars

```bash
# Compare two local jars entry by entry (META-INF ignored); exit code 0 means no
# differences, 1 means the contents differ.
nmvn diff-jar target/old.jar target/new.jar

# Compare two artifacts resolved from a repository, without downloading them by hand.
nmvn diff-jar com.example:lib:1.2.3 com.example:lib:1.2.4 \
  -r https://repo.example.com/maven

# Same comparison as JSON for a script.
nmvn diff-jar target/old.jar target/new.jar --json
```

This compares jar entry names and bytes. It is a content comparison, not a
Java-API compatibility analysis, and it lists at most the first 20 differing entries.

---

## How NMvn finds your projects

Every `version`, `clean` and `workset scan` command first works out which roots to
scan, then walks them for `pom.xml` files.

1. **Workset selection.** `-w` / `--workset` / `--config <name|file>` uses that
   workset. A bare name is looked up as `<name>` or `<name>.tson` in the current
   directory, then in the Nuts config folder. Without `-w`, the first existing file
   wins: `./workset.tson`, `./nmvn.tson`, `./.nmvn/config.tson`, then global
   `workset.tson` / `nmvn.tson`.
2. **Roots.** `--root` / `-r` (repeatable) replaces the workset roots. With no
   workset and no `--root`, the current directory is the root.
3. **Filtering.** `target`, `build`, `dist`, `node_modules`, `.git`, `.hg`, `.svn`,
   `.idea` and `.gradle` are skipped. Root and nested `.gitignore` files are honoured
   (common glob, directory and negated patterns; not Git's global excludes file).
4. **Excludes.** `--exclude <glob>` patterns are always applied, even with
   `--no-ignore`. `--no-ignore` disables only the defaults and `.gitignore` rules.

If a command touches the wrong projects, or none, run `nmvn version scan` from the
same directory with the same options. It lists exactly what was found. Also check
`nmvn workset path`: a global workset applies everywhere and can override the
current directory.

---

## Command reference

### `nmvn workset` (aliases: `ws`, `config`)

With no sub-command, `nmvn workset` lists named worksets. Sub-command aliases:
`list`/`ls`, `get`/`cat`/`view`, `add-root`/`add`/`add-folder`,
`remove-root`/`rm-root`/`remove`/`rm`.

`list`, `path`, `get`, `set`, `scan` and `edit` accept the workset as their first
positional argument. `add-root`, `remove-root` and `root ...` treat positional
arguments as folders, so name the workset with `--workset <name>` (or `--name`).
Without a name they use the local `workset.tson` / `nmvn.tson`, creating
`workset.tson` when saving.

```bash
# Add one or more folders to the current workset and scan immediately.
nmvn workset add-root ../service-core ../service-plugins --scan

# Add a folder to a named workset.
nmvn workset add-root /work/service-ui --workset backend --scan

# Remove a folder from the current workset and rescan.
nmvn workset remove-root ../service-plugins --scan

# Show the workset's roots and whether each one exists.
nmvn workset root list --workset backend

# Create/update a workset (roots replace; excludes append).
nmvn workset set backend --root /work/a --root /work/b --exclude "**/generated/**"

# Store default versioning policy in the workset.
nmvn workset set backend --default-increment patch --snapshot-suffix -SNAPSHOT

# Store cascade policy and version-history location.
nmvn workset set backend --cascade-policy cascade-references-only \
  --history-file .nmvn/history.tson

# Print, inspect, locate or edit the workset file.
nmvn workset get backend
nmvn workset get backend --json
nmvn workset path backend
nmvn workset edit backend

# Scan every artifact in the workset.
nmvn workset scan backend
```

### `nmvn version`

All `version` sub-commands accept `-w` / `--workset` / `--config <name|file>`,
`-r` / `--root <dir>` (repeatable, replaces workset roots), `--exclude <glob>`
(repeatable) and `--no-ignore`. Sub-command aliases: `update`/`set`,
`release`/`fix-snapshots`, `check`/`validate`.

#### `scan`

```bash
# Print discovered artifacts, paths, references and dependents.
nmvn version scan

# Same scan as JSON (includes scan warnings and per-artifact references).
nmvn version scan --json

# Include directories hidden by default ignores and .gitignore.
nmvn version scan --no-ignore
```

#### `check`

```bash
# Run every diagnostic rule; exit non-zero on errors (see the Nuts caveat above).
nmvn version check

# Treat warnings as failure too.
nmvn version check --fail-on-warning

# Emit the report as JSON for CI.
nmvn version check --json
```

Rules and default severities:

| Rule | Severity | Meaning |
|---|---|---|
| `MULTI_VERSION_DEPENDENCY` | warning | One dependency is referenced at multiple versions in the workspace. |
| `MIXED_SNAPSHOT_AND_RELEASE` | error | One artifact is referenced as both snapshot and release. |
| `INTERNAL_VERSION_MISMATCH` | error | A project references an internal artifact at a version different from its declared version. |
| `PARENT_VERSION_MISMATCH` | error | A child declares a parent version different from the parent POM's version. |
| `SNAPSHOT_DEPENDENCY_IN_RELEASE` | error | A release artifact depends on an external or internal snapshot. |
| `MIXED_WORKSPACE_SNAPSHOT_RELEASE` | warning | Modules in one group mix snapshot and release versions. |
| `UNRESOLVED_PROPERTY_VERSION` | error | A `${...}` version placeholder cannot be resolved. |
| `MISSING_VERSION` | warning | A direct dependency has no version and is not managed locally. |
| `CIRCULAR_DEPENDENCY` | error | Direct or indirect cycle between workspace modules. |
| `INCOMPLETE_SCAN` | error | A POM or ignore file could not be read; the graph may be incomplete. |

#### `bump`

```bash
# Preview one artifact's patch bump (release → next -SNAPSHOT).
nmvn version bump com.example:core -p --root . --dry

# Apply the patch bump after reviewing the preview.
nmvn version bump com.example:core -p --root .

# Preview bumping every artifact in the workset.
nmvn version bump --all -p --root . --dry

# Bump the selected artifacts and also advance dependent project versions.
nmvn version bump com.example:core -p --cascade-versions --root . --dry
```

Coordinates may be positional or passed with `-a`; the target version can be written
as `group:artifact#version`, `group:artifact=version`, or just `group:artifact` for
an increment. An incremented release becomes a `-SNAPSHOT`; an existing snapshot is
left alone unless `--force` is used.

#### `update` (alias `set`)

```bash
# Set an internal module to an exact version and align references to it.
nmvn version update -a com.example:core#2.0.0-SNAPSHOT

# Align a third-party dependency across the whole workspace.
nmvn version update -a net.thevpc.hl:hadra-lang#0.1.1

# Upgrade a shared parent across all child modules.
nmvn version update -a com.example:company-parent#5.0.0
```

With the default cascade this changes references only; with `--cascade-versions`,
affected release dependents are also advanced to snapshots.

#### `release` (alias `fix-snapshots`)

```bash
# Preview stripping -SNAPSHOT from every artifact and realigning references.
nmvn version release --dry

# Apply the release.
nmvn version release

# Fail if any unmanaged external snapshot dependency remains.
nmvn version release --strict

# Override the release version of specific artifacts (all snapshots still released).
nmvn version release -a com.example:core-lib#1.0.0 -a com.example:client-lib#1.0.0
```

#### `compare`

`nmvn version compare` is a placeholder and currently reports that it is not
implemented. Use `nmvn diff-jar` instead.

### `nmvn clean`

```bash
# Run Maven's `clean` in every scanned project.
nmvn clean

# Fast: delete target/ directly without invoking Maven.
nmvn clean --simple

# Clean a workset, a specific root, or exclude a subtree.
nmvn clean --workset backend --simple
nmvn clean --root ../service-core --exclude "**/skipme/**"

# Include ignored/generated projects in the scan.
nmvn clean --no-ignore
```

Options: `-s` / `--simple`, `--workset <name>` (no `-w` short form),
`-r` / `--root <dir>` or positional roots, `--exclude <glob>`, `--no-ignore`.

### Maven pass-through and utilities

```bash
# Hand an unknown command to the embedded Maven: install, skipping tests.
nmvn install -DskipTests

# Hand Maven a profile-specific package build.
nmvn package -Pprod

# Force Maven when the goal collides with an NMvn command.
nmvn build clean install

# Download an artifact and dependencies into ~/.m2.
nmvn get com.example:lib:1.2.3

# Same, from an explicit repository.
nmvn get com.example:lib:1.2.3 https://repo.example.com/maven

# Compare two jars (paths, URLs or g:a:v), ignoring META-INF.
nmvn diff-jar target/old.jar target/new.jar

# Compare two repository artifacts, resolving from the given repository.
nmvn diff-jar com.example:lib:1.2.3 com.example:lib:1.2.4 \
  -r https://repo.example.com/maven
```

---

## Workset configuration format (`workset.tson`)

Worksets are stored in **TSON** (Typed Structured Object Notation), native to Nuts:

```tson
{
  // Root folders to search for pom.xml files.
  // Use absolute paths in named/global worksets; relative paths resolve
  // against the directory NMvn is run from.
  roots : [
    "/work/service-api",
    "/work/service-core",
    "/work/service-jobs"
  ],

  // Glob patterns excluded from every root.
  excludes : [
    "**/generated/**",
    "**/local-only/**"
  ],

  // Per-root excludes, keyed by the exact root string above.
  rootExcludes : {
    "/work/service-jobs" : ["**/samples/**"]
  },

  // Honour .gitignore and the built-in generated/tool directories.
  useDefaultIgnores : true,

  // Default versioning rules.
  bumpPolicy : {
    defaultIncrement : "patch",              // patch | minor | major
    snapshotSuffix : "-SNAPSHOT",
    cascadePolicy : "cascade-references-only" // safe default
  },

  // Optional declarative bump instructions (same shape as CLI selectors).
  instructions : [
    { groupId : "com.acme", artifactId : "shared-lib", toVersion : "patch" }
  ],

  // Durable version history with commit hashes and timestamps.
  historyFile : ".nmvn/version-history.tson"
}
```

`nmvn workset set` writes this file; `nmvn workset get` prints it; `nmvn workset
edit` opens it in your editor.

---

## Version history

Every applied `bump`, `update` or `release` (not `--dry`) appends the new versions,
the current Git commit and a timestamp to `historyFile`, by default
`.nmvn/version-history.tson` relative to the current directory. Commit it if you want
a durable record, or add `.nmvn/` to `.gitignore`. Change the location with
`nmvn workset set --history-file <path>`.

---

## Using `nmvn-lib` as a Java library

```xml
<dependency>
    <groupId>net.thevpc.nmvn</groupId>
    <artifactId>nmvn-lib</artifactId>
    <version>1.1.0.0</version>
</dependency>
```

```java
import net.thevpc.nmvn.lib.config.NMvnConfig;
import net.thevpc.nmvn.lib.config.NMvnConfigLoader;
import net.thevpc.nmvn.lib.diagnostic.DiagnosticReport;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nmvn.lib.service.ScanResult;
import net.thevpc.nmvn.lib.service.VersionService;
import net.thevpc.nuts.io.NPath;

public class Example {
    public static void main(String[] args) throws Exception {
        VersionService service = new VersionService();
        NPath workingDir = NPath.ofUserDirectory();

        // 1. Load the workset configuration (or defaults if no file exists).
        NPath configFile = NMvnConfigLoader.resolveConfigFile(null, workingDir);
        NMvnConfig config = NMvnConfigLoader.load(configFile);

        // 2. Scan the workspace into one artifact graph.
        ScanResult scan = service.scan(config, workingDir);
        System.out.printf("Discovered %d artifacts.%n", scan.getArtifacts().size());
        for (PomArtifact artifact : scan.getArtifacts().values()) {
            System.out.println(artifact.toGa() + " -> " + artifact.getResolvedVersion());
        }

        // 3. Audit the workspace without modifying it.
        DiagnosticReport report = service.check(config, workingDir);
        if (report.hasErrors()) {
            report.getErrors().forEach(e -> System.err.println("[ERROR] " + e.getMessage()));
        } else {
            System.out.println("Workspace is clean!");
        }
    }
}
```

The library exposes scanning, checking, bumping, updating and releasing. Mutating
calls take an `apply` flag, so a dry run is just `apply = false`.

---

## How it compares

There is no mainstream tool that is a drop-in equivalent of “workset across several
independent Maven repositories, with property-aware POM editing and a drift audit”.
The closest tools solve adjacent problems:

| Tool | Main job | How it differs from NMvn |
|---|---|---|
| [Maven Versions Plugin](https://www.mojohaus.org/versions/versions-maven-plugin/) | Update versions inside one reactor (`versions:set`, `update-parent`, `update-properties`, …). | Excellent inside one reactor. It does not build a cross-repository workset graph, audit drift across repos, or choose a cross-repo cascade policy. `versions:set` is typically all-modules-to-one-version. |
| [Maven Release Plugin](https://maven.apache.org/plugins/maven-release-plugin/) | Release one project: prepare, tag, deploy, next snapshot. | Per-project and SCM-centric. It does not coordinate a suite of independent repositories or realign a cross-repo graph. |
| [flatten-maven-plugin](https://www.mojohaus.org/flatten-maven-plugin/) | Publish a flattened POM with resolved versions. | A publishing concern, not a version-orchestration or audit tool. |
| [jgitver](https://github.com/jgitver/jgitver-maven-plugin), [Paprika](https://atos-digital-id.github.io/paprika/) | Derive versions from Git tags/history. | They compute versions at build time; they do not edit POMs across repositories and generally compute one version per reactor. |
| [Renovate](https://docs.renovatebot.com/) / Dependabot | Open PRs that upgrade third-party dependencies. | Great for external dependency updates. They do not coordinate local snapshot/release cycles across your own repositories. |
| [IKE Workspace Maven Plugin](https://ike.network/ike-platform/ike-workspace-maven-plugin) | Cross-repository workspace goals (`ws:align`, release in topological order, workspace audits). | The closest conceptual match found. It assumes its own `workspace.yaml` + Maven-plugin model and a fan-out workflow; NMvn is a standalone CLI with `.gitignore`-aware scanning, property/BOM resolution and dry-run POM editing. |
| [aggregator-creator](https://github.com/motlin/aggregator-creator) | Create an aggregator POM from several repositories. | Solves “build them together”, not “align and release their versions together”. |
| [multi-repo-ci](https://github.com/overbaard/multi-repo-ci) | CI orchestration across interdependent repositories using version properties. | CI workflow generation, not a local editing/audit CLI. |
| [one-version](https://one-version.vercel.app/) | Enforce one dependency version in a JS/TS monorepo. | Same idea, different ecosystem (npm/pnpm/yarn), and monorepo-scoped. |

### Is NMvn concurrent or parallel?

No — and that is deliberate. NMvn runs one process:

- scans roots sequentially,
- builds one in-memory plan,
- applies POM edits sequentially,
- rechecks each file before replacing it, and refuses to overwrite a POM that changed
  after planning.

This keeps diffs deterministic and makes rollback tractable. For faster builds, use
Maven's own parallelism (`mvn -T` / `nmvn build -T 4 clean install`) or a CI matrix.
If several Maven processes share `~/.m2`, that is a separate problem solved by Maven
Resolver's shared local repository (1.7+) and named locks; the older
[Takari Concurrent Local Repository](https://github.com/takari/takari-local-repository)
addressed the same problem and is now archived.

NMvn also detects *concurrent edits* to the POMs it is changing: it compares the
bytes it planned against with the bytes on disk immediately before writing, and
aborts rather than clobbering another process's change.

---

## Building and testing

NMvn requires **Java 8+** and **Maven 3.6+**:

```bash
# Compile and run all unit/integration tests.
mvn clean test

# Build the jars.
mvn clean package
```

The test suite covers property inheritance and child overrides, property aliases,
BOM-import cascading, ambiguity and cycle failures, strict snapshot release, release
with inherited versions, CLI parsing, workset management, `.gitignore` handling and
cleaning.

To try a local build through Nuts:

```bash
# Install the current source build into ~/.m2.
mvn install -DskipTests

# Make the Nuts development workspace pick up the newly installed NMvn jar.
nuts-dev -y install --reinstall net.thevpc.nmvn:nmvn
```

---

## Known limitations

- **Exit codes under Nuts:** when launched with `nuts` / `nuts-dev`, failed commands
  (including `version check` with errors) still exit with code `0`; the failure is
  reported in the output. Use `--json` and inspect `hasErrors`.
- **Release selection:** `release -a` overrides versions but does not limit which
  snapshots are released.
- **Remote parents and BOMs:** NMvn reads local `~/.m2` parent/BOM POMs but does not
  fetch remote parents or BOMs. Only POMs inside the scanned roots are edited.
- **Children that only inherit a version** cannot be versioned independently of their
  parent; change the parent version instead.
- **POM editing scope:** the modifier edits standard, unprefixed Maven elements. It
  refuses prefixed/foreign-namespace elements, malformed XML, symlinked POMs, and
  direct replacement of property-indirected versions. Update the terminal property
  explicitly instead of flattening an alias chain.
- **Non-numeric versions:** a version such as `1.2.3.Final` is not numerically
  incremented; a bump appends the snapshot suffix instead. Prefer an explicit
  `group:artifact#version` update for such schemes.
- **`version compare`** is a placeholder and does nothing yet; use `diff-jar`.

---

## License

This project is licensed under the **GNU Lesser General Public License version 3
(LGPL-3.0)** — see the [LICENSE](LICENSE) file for details.

Copyright (c) 2026 thevpc.
