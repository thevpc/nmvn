# NMvn: Multi-Project Maven Version & Workset Manager

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](LICENSE)
[![Java: 1.8+](https://img.shields.io/badge/Java-1.8+-orange.svg)](https://openjdk.org/)
[![Nuts Package Manager](https://img.shields.io/badge/Nuts-1.1+-green.svg)](https://github.com/thevpc/nuts)

**NMvn** is an advanced developer tool and Java library for orchestrating multi-project Maven versioning, dependency graph analysis, discrepancy auditing, and cross-repository **Worksets**.

It provides two submodules:
1. **`nmvn-lib`**: An embeddable Java library providing surgical non-destructive POM editing, property-indirection resolution, cycle detection, and automated discrepancy checks. 
2. **`nmvn`**: A command-line tool built on the Nuts Application Framework (NAF), exposing intuitive `workset` and `version` subcommands.

> **In a hurry?** See the [command cheat sheet](CHEATSHEET.md) for the most
> common commands and when to use each one.

## Installing and running

NMvn is a [Nuts](https://github.com/thevpc/nuts) application
(`net.thevpc.nmvn:nmvn`). Run it through Nuts:

```bash
nuts nmvn version scan          # installed nuts
nuts-dev -y nmvn version scan   # nuts development workspace (uses ~/.m2)
```

`nuts-dev` may ask to re-install the Nuts runtime on each call; `-y` answers
yes so the command also works non-interactively. The examples below write
`nmvn ...` for short.

Nuts session options work anywhere on the command line: `--dry` (or `-D`)
previews without writing, `-y` auto-confirms, `--json` switches output to JSON,
and `--force` is the bump force flag.

## Quick start

From a project directory, scan before changing anything:

```bash
nmvn version scan --root .
nmvn version check --root .
```

Preview a bump of one artifact; apply it only after reviewing the diff:

```bash
nmvn version bump com.example:core -p --root . --dry
nmvn version bump com.example:core -p --root .
```

For regular use, save a workset once (`nmvn workset set`) and select it with
`-w <workset>` instead of repeating `--root`. Bumps require an artifact selector;
use `--all` only when you really intend to bump every artifact in the workset.

## How NMvn finds your projects

Every `version`, `clean` and `workset scan` command first works out **which
roots to scan**, then walks them for `pom.xml` files.

1. **Workset file.** With `-w`/`--workset <name|file>`, that workset is used: a
   bare name is looked up as `<name>` or `<name>.tson` in the current
   directory, then in the Nuts config folder (`nmvn workset path <name>` prints
   where). Without `-w`, the first existing file wins:
   `./workset.tson`, `./nmvn.tson`, `./.nmvn/config.tson`, then the global
   `workset.tson` / `nmvn.tson` in the Nuts config folder.
2. **Roots.** `--root`/`-r` on the command line replaces the workset's roots.
   With no workset and no `--root`, the root is the **current directory**, so
   running from the top of a multi-module checkout just works.
3. **Filtering.** `target`, `build`, `dist`, `node_modules`, VCS and IDE
   folders and anything matched by `.gitignore` are skipped (see
   [POM editing and boundaries](#2-pom-editing-and-boundaries)).

If a command touches the wrong projects, or none, run `nmvn version scan`
from the same directory with the same options: it lists exactly what was found.
A global `workset.tson` in the Nuts config folder applies everywhere, which
can make NMvn ignore your current directory; check with `nmvn workset path`.

## Workflow recipes

The examples below use `backend` as a named workset and illustrative Maven
coordinates. Replace the roots and coordinates with your own.

### Manage several repositories together

Create a reusable workset from multiple checkout directories, excluding build
outputs and generated trees:

```bash
nmvn workset set backend \
  --root ../service-api \
  --root ../service-core \
  --root ../service-jobs \
  --exclude "**/generated/**" \
  --default-increment patch \
  --cascade-policy cascade-references-only

nmvn workset scan backend
nmvn version scan --workset backend
nmvn version check --workset backend
```

Add another checkout later without recreating the workset:

```bash
nmvn workset add-root ../service-ui --workset backend --scan
nmvn workset root list --workset backend
```

### Align an external dependency without bumping project versions

Use `check` to find version drift, then preview and apply one exact alignment.
This changes matching dependency references; the default cascade does not
change dependent projects' own versions.

```bash
nmvn version check --workset backend
nmvn version update com.fasterxml.jackson.core:jackson-databind#2.17.2 \
  --workset backend --dry
nmvn version update com.fasterxml.jackson.core:jackson-databind#2.17.2 \
  --workset backend
```

### Change a module version and choose how far to cascade

For a normal library patch bump, preview first. NMvn updates references to the
library but leaves consumers' project versions unchanged. Use
`--cascade-versions` only when those consumer versions should advance too.

```bash
nmvn version bump com.acme:shared-lib -p --workset backend --dry
nmvn version bump com.acme:shared-lib -p --workset backend

# Broader coordinated change: also advance dependent project versions
nmvn version bump com.acme:shared-lib -p --cascade-versions \
  --workset backend --dry
```

To leave all downstream POMs untouched and edit only the selected artifact,
use `--cascade=none`. To bump several independent artifacts in one operation,
repeat the selector:

```bash
# Change only the selected artifact POM; do not update downstream references
nmvn version bump com.acme:shared-lib -p --cascade=none \
  --workset backend --dry

# Bump multiple selected artifacts
nmvn version bump \
  -a com.acme:shared-lib \
  -a com.acme:service-api \
  --minor --workset backend --dry
```

An artifact already on a snapshot version is not advanced again by default.
Use `--force` when an additional snapshot bump is intentional:

```bash
nmvn version bump com.acme:shared-lib -p --force --workset backend --dry
```

### Update a shared parent or BOM

An exact update is useful when upgrading a parent POM or imported BOM. Preview
the update to inspect all affected references before applying:

```bash
nmvn version update com.acme:company-parent#5.2.0 \
  --workset backend --dry
nmvn version update com.acme:company-parent#5.2.0 --workset backend
```

### Release the workset

`release` turns every `-SNAPSHOT` artifact in the scanned roots into its
release version (`6.1.0-SNAPSHOT` → `6.1.0`) and updates every internal
reference to those artifacts. Preview first:

```bash
nmvn version release --workset backend --dry
nmvn version release --workset backend

# Choose the release version of some artifacts instead of just dropping -SNAPSHOT
nmvn version release -a com.acme:shared-lib#2.4.0 --workset backend --dry

# Refuse the release if unmanaged external snapshot dependencies remain
nmvn version release --strict --workset backend --dry
```

`-a` does **not** restrict the release: all snapshots in the scanned roots are
still released, and `-a` only overrides the target version of the listed
artifacts. To release a subset, narrow the scan instead (`--root`,
`--exclude`, or a dedicated workset).

Modules that inherit their version from `<parent>` are released through the
parent reference, and `${project.version}` dependencies are left as they are.
Internal references pinned to an older version (for example `6.0.0`) are also
realigned to the released version.

After a release, start the next development cycle with a bump, for example
`nmvn version bump com.acme:parent -m --cascade-versions --dry`.

### Run a CI audit

Use JSON for machine-readable diagnostics and make warnings fail the job when
that policy is desired:

```bash
nmvn version check --workset backend --json
nmvn version check --workset backend --fail-on-warning --json
```

When launched through Nuts, the process exit code is currently `0` even when
`check` reports errors (see [Known limitations](#known-limitations)). Until
that is fixed, have CI read `hasErrors` / `hasWarnings` from the JSON output.

### Run Maven itself

Anything that is not an NMvn command is passed to an embedded Maven (3.6.x),
so NMvn can stand in for `mvn`:

```bash
nmvn install -DskipTests
nmvn build clean install      # `clean` alone is NMvn's own clean command
nmvn get com.google.guava:guava:33.0.0-jre   # download an artifact to ~/.m2
```

---

## Table of Contents

- [The Problems NMvn Solves](#the-problems-nmvn-solves)
- [Installing and running](#installing-and-running)
- [Quick start](#quick-start)
- [How NMvn finds your projects](#how-nmvn-finds-your-projects)
- [Workflow recipes](#workflow-recipes)
- [Key Concepts](#key-concepts)
  - [Worksets](#1-worksets)
  - [POM editing and boundaries](#2-pom-editing-and-boundaries)
  - [Property-Indirection & BOM Resolution](#3-property-indirection--bom-resolution)
  - [Workspace Discrepancy Auditing](#4-workspace-discrepancy-auditing)
- [CLI Command Reference](#cli-command-reference)
  - [Workset Management (`nmvn workset`)](#workset-management-nmvn-workset)
  - [Version Management (`nmvn version`)](#version-management-nmvn-version)
  - [Project Cleaning (`nmvn clean`)](#project-cleaning-nmvn-clean)
  - [Maven Pass-Through and Utilities](#maven-pass-through-and-utilities)
- [Workset Configuration Format (`workset.tson`)](#workset-configuration-format-worksettson)
- [Version History File](#version-history-file)
- [Using `nmvn-lib` as a Java Library](#using-nmvn-lib-as-a-java-library)
- [Building & Testing](#building--testing)
- [Known limitations](#known-limitations)
- [License](#license)

---

## The Problems NMvn Solves

When developing large software suites split across dozens of Maven repositories or multi-module trees:
- **Property-Indirected Versions**: Dependencies often declare `<version>${common.version}</version>`, with the property defined in a parent POM or ancestor repository. Standard bumping tools either fail or break property mappings.
- **Cascading Dependencies & BOMs**: Bumping a library or a BOM (`<type>pom</type><scope>import</scope>`) requires updating all downstream consumers, direct dependents, parents, and plugins across separate directories.
- **Corrupted Formatting & Lost Comments**: Standard XML DOM parsers strip comments, destroy indentation, and rewrite the entire file when saving.
- **Accidental Discrepancies**: Teams inadvertently mix `-SNAPSHOT` and release versions, depend on different versions of the same shared artifact across siblings, or create circular dependencies between independent repositories.
- **No Monorepo Mandate**: NMvn allows independent repositories to remain independent while being managed together as a cohesive **Workset**.

---

## Key Concepts

### 1. Worksets

A **Workset** is a collection of directory roots (local repositories, submodules, or parent directories) grouped together for synchronized analysis, version bumping, and release management.

- **Local Worksets**: Defined by a `workset.tson` (or `nmvn.tson`) in the project directory.
- **Named Global Worksets**: Stored as `<name>.tson` in NMvn's Nuts configuration folder (on Linux typically `~/.config/nuts/ws/<workspace>/id/net/thevpc/nmvn/nmvn/`; `nmvn workset list` prints the exact folder). Select one from any directory with `--workset <name>` (or `-w <name>`).
- **Dynamic Roots**: Add and remove root folders dynamically with immediate live scanning (`--scan`).

### 2. POM editing and boundaries

The scanner reads POMs into an artifact/dependency model, resolves properties and
local parent/BOM metadata, then builds a workspace graph. Changes are computed
in memory and shown as unified diffs before writing. The modifier edits matching
XML text rather than serializing a DOM, which avoids routine formatting churn.

This is not a full Maven model builder: it does not resolve remote parents or
BOMs, and POM editing is based on standard, unprefixed Maven element names.
Before editing, NMvn validates XML structure and the Maven POM namespace. It
refuses prefixed or foreign-namespace Maven elements, malformed XML, and direct
replacement of property-indirected versions or property aliases. Concrete
declared properties can be updated; for alias chains, update the terminal
property explicitly rather than flattening the alias. Malformed or non-project
`pom.xml` files are reported as scan warnings.
By default, the scanner also honors `.gitignore` files and skips common generated
or tool-managed directories such as `target`, `build`, `dist`, `.git`, `.hg`,
`.svn`, `.idea`, `.gradle`, and `node_modules`. It reads root and nested
`.gitignore` files (not Git's global excludes file); common glob, directory, and
negated patterns are supported. Set `useDefaultIgnores: false` in a workset or
pass `--no-ignore` to scan, check, bump, update, release, clean, or workset scan
to disable both these defaults and `.gitignore` rules; explicit `--exclude`
patterns remain active. Read-only scans and checks continue, but mutating
commands refuse an incomplete scan so skipped projects cannot conceal
references that should be updated.
Review the warning, fix or exclude the file, then rerun the command.
`version scan --json` includes both `artifacts` and `warnings`. Apply stages
replacement and backup files beside each POM, checks for concurrent edits, then
replaces files and attempts rollback if a replacement fails. This cannot protect
against process or machine failure mid-apply; keep the worktree under version
control and review diffs.

### 3. Property-Indirection & BOM Resolution

- Automatically resolves `${property}` references across local inheritance hierarchies and ancestor POMs.
- Respects child overrides where a submodule redefines an inherited property.
- Updates multi-property aliases concurrently when multiple dependencies share a single property.
- Detects and cascades changes across BOM imports (`dependencyManagement` imports).

### 4. Workspace Discrepancy Auditing

The `nmvn version check` command audits the workset against built-in rules:
- **`MULTI_VERSION_DEPENDENCY`**: Multiple differing versions of the same dependency across workspace projects.
- **`MIXED_SNAPSHOT_AND_RELEASE`**: An artifact referenced as a `-SNAPSHOT` in one project and as a release in another.
- **`INTERNAL_VERSION_MISMATCH`**: A project referencing an internal workspace artifact with a version differing from its declared version.
- **`PARENT_VERSION_MISMATCH`**: A child project referencing a parent version that doesn't match the parent POM.
- **`SNAPSHOT_DEPENDENCY_IN_RELEASE`**: A release artifact depending on a `-SNAPSHOT` artifact.
- **`MIXED_WORKSPACE_SNAPSHOT_RELEASE`**: Modules under the same `groupId` having mixed snapshot and release versions.
- **`UNRESOLVED_PROPERTY_VERSION`**: Unresolvable `${...}` placeholders.
- **`CIRCULAR_DEPENDENCY`**: Direct or indirect cycles between workspace modules.

---

## CLI Command Reference

### Workset Management (`nmvn workset`)

Aliases: `nmvn ws`, `nmvn config`. With no sub-command, `nmvn workset` lists
the named worksets. Sub-command aliases: `list`/`ls`, `get`/`cat`/`view`,
`add-root`/`add`/`add-folder`, `remove-root`/`rm-root`/`remove`/`rm`.

`list`, `path`, `get`, `set`, `scan` and `edit` accept the workset as their
first positional argument; `add-root`, `remove-root` and `root ...` treat
positional arguments as folders, so name the workset with `--workset <name>`
(or `--name <name>`). Without one, they use the local
`workset.tson` (or `nmvn.tson`) in the current directory, creating
`workset.tson` when saving.

#### 1. Add Folders / Roots to a Workset
```bash
# Add one or more folders to the current workset and run a live scan
nmvn workset add-root ../service-core ../service-plugins --scan

# Add folders to a specific named workset
nmvn workset add-root /path/to/repo --workset backend --scan

# Alternative syntax
nmvn workset add ../service-core --scan
nmvn workset root add ../service-core --scan
```

#### 2. Remove Folders / Roots from a Workset
```bash
# Remove a folder from the current workset and rescan
nmvn workset remove-root ../service-plugins --scan

# Alternative syntax
nmvn workset rm ../service-plugins --scan
nmvn workset root remove ../service-plugins --scan
```

#### 3. List Workset Roots
```bash
# Display all configured roots and their status
nmvn workset root list
nmvn workset root list --workset backend
```

#### 4. List Worksets
```bash
# List all named worksets in the Nuts configuration folder
nmvn workset list

# List recently accessed worksets with existence check
nmvn workset list --recent

# JSON output
nmvn workset list --recent --json
```

#### 5. Inspect and Edit Worksets
```bash
# Print absolute path to the resolved workset file
nmvn workset path [workset-name]

# Display workset configuration (TSON or JSON)
nmvn workset get [workset-name]
nmvn workset get [workset-name] --json

# Create or update a workset (roots replace the stored list; excludes are appended)
nmvn workset set [workset-name] --root ../a --root ../b --exclude "**/generated/**"
nmvn workset set [workset-name] --default-increment patch --snapshot-suffix -SNAPSHOT
nmvn workset set [workset-name] --cascade-policy cascade-references-only --history-file .nmvn/history.tson

# Open workset in $EDITOR or $VISUAL
nmvn workset edit [workset-name]

# Scan all artifacts in the workset
nmvn workset scan [workset-name]
```

---

### Version Management (`nmvn version`)

All `version` sub-commands accept `-w`/`--workset`/`--config <name|file>`,
`-r`/`--root <dir>` (repeatable, replaces the workset roots), `--exclude <glob>`
(repeatable) and `--no-ignore`. Sub-command aliases: `update`/`set`,
`release`/`fix-snapshots`, `check`/`validate`.

#### 1. Scan Dependency Graph
```bash
# Scan workspace roots and display all artifacts, internal references, and dependents
nmvn version scan

# Output structured JSON
nmvn version scan --json

# Include directories normally hidden by default ignores and .gitignore
nmvn version scan --no-ignore
```

#### 2. Audit Workspace for Discrepancies (`check`)
```bash
# Run all diagnostic checks (reports failure if errors are found)
nmvn version check

# Fail on warnings as well as errors
nmvn version check --fail-on-warning

# Machine-readable output for CI/CD pipelines
nmvn version check --json
```

#### 3. Bump Versions (`nmvn version bump`)

Use `bump` to increment a selected artifact using `--patch` (`-p`), `--minor`
(`-m`), or `--major` (`-M`). Artifact coordinates may be positional or passed
with `-a`; the target version can be written as `group:artifact#version`,
`group:artifact=version`, or just `group:artifact` for an increment.

Without `-p`/`-m`/`-M`, the workset's `defaultIncrement` is used (`minor` unless
configured). An incremented release artifact becomes a `-SNAPSHOT`
(`2.0.0` → `2.0.1-SNAPSHOT` with `-p`); an existing snapshot is left unchanged
unless `--force` is specified. By default, NMvn updates references
to the selected artifact but does **not** bump dependent projects' own versions.
`--cascade-versions` opts into that broader behavior; when doing an exact
`update`, it also advances affected release dependents to snapshots.
An existing workset that explicitly stores `cascade-versions` keeps that
behavior until its configuration is changed to `cascade-references-only`.

```bash
# Preview one artifact's patch bump; use --dry for no disk changes
nmvn version bump com.example:core -p --root . --dry

# Apply after reviewing the preview
nmvn version bump com.example:core -p --root .

# Explicitly bump every artifact (preview first)
nmvn version bump --all -p --root . --dry

# Also bump dependent project versions
nmvn version bump com.example:core -p --cascade-versions --root . --dry
```

Cascade modes are `versions`, `references` (default) and `none`; pass them as
`--cascade=<mode>` or `-c=<mode>` (with `=`), or use the flags
`--cascade-versions`, `--cascade-references-only` and `--no-cascade`.

For a shorter repeatable invocation, create a workset and use
`nmvn version bump com.example:core -p -w backend --dry`. `-r` is shorthand for
`--root`; `-w` and `--config` are aliases for `--workset`. A bump with neither
an artifact selector nor `--all` is rejected rather than silently targeting the
whole workspace.

---

#### 4. Update Versions Strictly (`nmvn version update` or `set`)

Use `update` to set an **exact, strict target version** on an artifact and align all workspace references.

Coordinates support standard Nuts `#` notation or `=`:
`-a <GA#VERSION>` or `-a=<GA#VERSION>`

```bash
# Update internal module to an exact version and align all consumers:
nmvn version update -a com.example:core#2.0.0-SNAPSHOT

# Align a third-party dependency or BOM across the entire workspace (e.g. resolving MULTI_VERSION_DEPENDENCY):
nmvn version update -a net.thevpc.hl:hadra-lang#0.1.1

# Upgrade a shared parent POM across all child modules:
nmvn version update -a com.example:company-parent#5.0.0
```

---

#### 5. Release Artifacts (`nmvn version release`)

Convert every `-SNAPSHOT` artifact in the scanned roots to its release version
and update all workspace references (alias: `fix-snapshots`). Artifacts that are
already releases are left alone, so a workspace with no snapshots reports
"No changes required".
```bash
# Preview release diffs
nmvn version release --dry

# Strip -SNAPSHOT from all workspace projects and convert references
nmvn version release

# Strict mode: fails if any unmanaged external snapshot dependency is detected
nmvn version release --strict

# Override the release version of some artifacts (all snapshots are still released)
nmvn version release -a com.example:core-lib#1.0.0 -a com.example:client-lib#1.0.0
```

---

### Project Cleaning (`nmvn clean`)

Cleans all Maven projects discovered by the workset scanner (same roots and
excludes as `nmvn version scan`), so `target/` copies of poms are skipped.

Options: `-s`/`--simple`, `--workset <name>` (no `-w` short form here),
`-r`/`--root <dir>` or positional directories, `--exclude <glob>`, `--no-ignore`.
Positional arguments are **roots**, not Maven goals: `nmvn clean install`
fails with "Root does not exist: install". Use `nmvn build clean install` for
Maven's clean + install.

```bash
# Run `mvn clean` in every scanned project dir
nmvn clean

# Fast mode: delete `target/` directories directly without invoking Maven
nmvn clean --simple

# Clean a specific workset, root or exclude (also accepts positional roots)
nmvn clean --workset backend --simple
nmvn clean --root ../service-core --exclude "**/skipme/**"

# Include ignored/generated projects in the scan (use with care)
nmvn clean --no-ignore
```

---

### Maven Pass-Through and Utilities

```bash
# Any non-NMvn first argument is handed to the embedded Maven
nmvn install -DskipTests
nmvn package -Pprod

# `build` forces Maven, needed when the goal collides with an NMvn command
nmvn build clean install

# Download an artifact (and its dependencies) into ~/.m2; optional repository
nmvn get com.example:lib:1.2.3
nmvn get com.example:lib:1.2.3 vpc-public-maven

# Compare two jars, ignoring META-INF; each side is a path, URL or g:a:v
nmvn diff-jar target/old.jar target/new.jar
nmvn diff-jar com.example:lib:1.2.3 com.example:lib:1.2.4 -r https://repo.example.com/maven
```

---

## Workset Configuration Format (`workset.tson`)

Workset configurations are stored in **TSON** (Tabular / Typed Structured Object Notation), native to the Nuts ecosystem:

```tson
{
  // Root folders to search for pom.xml files (relative or absolute)
  roots : [
    "." ,
    "../core-library" ,
    "../web-frontend"
  ] ,

  // Global glob exclusion patterns
  excludes : [
    "**/generated/**" ,
    "**/local-only/**"
  ] ,

  // Honor .gitignore and common generated/tool directories unless explicitly disabled
  useDefaultIgnores : true ,

  // Default versioning rules
  bumpPolicy : {
    defaultIncrement : "patch" ,             // patch | minor | major
    snapshotSuffix : "-SNAPSHOT" ,
    cascadePolicy : "cascade-references-only" // safe default; opt into cascade-versions explicitly
  } ,

  // Durable version history store tracking commit hashes and timestamps
  historyFile : ".nmvn/version-history.tson"
}
```

## Version History File

Every applied `bump`, `update` or `release` (not `--dry`) appends the new
versions, current Git commit and timestamp to `historyFile`, by default
`.nmvn/version-history.tson` **relative to the current directory**. Commit it
if you want a record of version changes, or add `.nmvn/` to `.gitignore`.
Change the location with `nmvn workset set --history-file <path>`.

---

## Using `nmvn-lib` as a Java Library

Add `nmvn-lib` to your `pom.xml`:

```xml
<dependency>
    <groupId>net.thevpc.nmvn</groupId>
    <artifactId>nmvn-lib</artifactId>
    <version>1.1.0.0</version>
</dependency>
```

### Example: Programmatic Scan & Discrepancy Check

```java
import net.thevpc.nmvn.lib.config.NMvnConfig;
import net.thevpc.nmvn.lib.config.NMvnConfigLoader;
import net.thevpc.nmvn.lib.diagnostic.DiagnosticReport;
import net.thevpc.nmvn.lib.model.PomArtifact;
import net.thevpc.nmvn.lib.service.ScanResult;
import net.thevpc.nmvn.lib.service.VersionService;
import net.thevpc.nuts.artifact.NId;
import net.thevpc.nuts.io.NPath;

import java.util.Collections;

public class Example {
    public static void main(String[] args) throws Exception {
        VersionService service = new VersionService();
        NPath workingDir = NPath.ofUserDirectory();

        // 1. Load workset configuration
        NPath configFile = NMvnConfigLoader.resolveConfigFile(null, workingDir);
        NMvnConfig config = NMvnConfigLoader.load(configFile);

        // 2. Scan workspace
        ScanResult scan = service.scan(config, workingDir);
        System.out.printf("Discovered %d artifacts.%n", scan.getArtifacts().size());

        for (PomArtifact artifact : scan.getArtifacts().values()) {
            System.out.println("Artifact: " + artifact.toGa() + " -> " + artifact.getResolvedVersion());
        }

        // 3. Audit for discrepancies
        DiagnosticReport report = service.check(config, workingDir);
        if (report.hasErrors()) {
            report.getErrors().forEach(e -> System.err.println("[ERROR] " + e.getMessage()));
        } else {
            System.out.println("Workspace is clean!");
        }
    }
}
```

---

## Building & Testing

NMvn requires **Java 8+** and **Maven 3.6+**:

```bash
# Run all unit and integration tests
mvn clean test

# Build package
mvn clean package
```

The unit and CLI integration tests run against temporary fixtures using native
`NPath` operations.

To try a local build through Nuts, install it to `~/.m2` and make the Nuts
development workspace pick up the new jar (it otherwise keeps running the
previously installed copy of `nmvn`):

```bash
mvn install -DskipTests
nuts-dev -y install --reinstall net.thevpc.nmvn:nmvn
```

---

## Known limitations

- **Exit codes under Nuts:** when launched with `nuts`/`nuts-dev`, failed
  commands (including `version check` with errors) still exit with code `0`;
  the failure is only reported in the output. Use `--json` and inspect
  `hasErrors` in scripts.
- **Release selection:** `release -a` overrides versions but does not limit
  which snapshots are released (see [Release the workset](#release-the-workset)).
- **Remote parents and BOMs** are not resolved; only POMs inside the scanned
  roots are understood and edited.
- **Children that only inherit a version** cannot be versioned independently
  of their parent; change the parent version instead.
- `version compare` is a placeholder and does nothing yet.

---

## License

This project is licensed under the **GNU Lesser General Public License version 3 (LGPL-3.0)** — see the [LICENSE](LICENSE) file for details.

Copyright (c) 2026 thevpc.
