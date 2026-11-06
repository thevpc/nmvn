# NMvn: Multi-Project Maven Version & Workset Manager

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](LICENSE)
[![Java: 1.8+](https://img.shields.io/badge/Java-1.8+-orange.svg)](https://openjdk.org/)
[![Nuts Package Manager](https://img.shields.io/badge/Nuts-0.8.4+-green.svg)](https://github.com/thevpc/nuts)

**NMvn** is an advanced developer tool and Java library for orchestrating multi-project Maven versioning, dependency graph analysis, discrepancy auditing, and cross-repository **Worksets**.

It provides two submodules:
1. **`nmvn-lib`**: An embeddable Java library providing surgical non-destructive POM editing, property-indirection resolution, cycle detection, and automated discrepancy checks. 
2. **`nmvn`**: A command-line tool built on the Nuts Application Framework (NAF), exposing intuitive `workset` and `version` subcommands.

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

---

## Table of Contents

- [The Problems NMvn Solves](#the-problems-nmvn-solves)
- [Quick start](#quick-start)
- [Key Concepts](#key-concepts)
  - [Worksets](#1-worksets)
  - [POM editing and boundaries](#2-pom-editing-and-boundaries)
  - [Property-Indirection & BOM Resolution](#3-property-indirection--bom-resolution)
  - [Workspace Discrepancy Auditing](#4-workspace-discrepancy-auditing)
- [CLI Command Reference](#cli-command-reference)
  - [Workset Management (`nmvn workset`)](#workset-management-nmvn-workset)
  - [Version Management (`nmvn version`)](#version-management-nmvn-version)
  - [Project Cleaning (`nmvn clean`)](#project-cleaning-nmvn-clean)
- [Workset Configuration Format (`workset.tson`)](#workset-configuration-format-worksettson)
- [Using `nmvn-lib` as a Java Library](#using-nmvn-lib-as-a-java-library)
- [Building & Testing](#building--testing)
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
- **Named Global Worksets**: Managed inside the standard Nuts configuration layout (`~/.nuts/ws/<workspace>/conf/id/net/thevpc/nmvn/nmvn/<name>.tson`). You can switch between worksets anywhere using `--workset <name>`.
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
Read-only scans and checks continue, but mutating commands refuse an incomplete
scan so skipped projects cannot conceal references that should be updated.
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

Aliases: `nmvn ws`, `nmvn config`

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

# Set workset defaults
nmvn workset set [workset-name] --default-increment patch --snapshot-suffix -SNAPSHOT

# Open workset in $EDITOR or $VISUAL
nmvn workset edit [workset-name]

# Scan all artifacts in the workset
nmvn workset scan [workset-name]
```

---

### Version Management (`nmvn version`)

#### 1. Scan Dependency Graph
```bash
# Scan workspace roots and display all artifacts, internal references, and dependents
nmvn version scan

# Output structured JSON
nmvn version scan --json
```

#### 2. Audit Workspace for Discrepancies (`check`)
```bash
# Run all diagnostic checks (returns exit code 1 if errors are found)
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

An incremented release artifact becomes a `-SNAPSHOT`; an existing snapshot is
left unchanged unless `--force` is specified. By default, NMvn updates references
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

Convert `-SNAPSHOT` artifacts to clean release versions and update all workspace references:
```bash
# Preview release diffs
nmvn version release --dry

# Automatically strip -SNAPSHOT from all workspace projects and convert references
nmvn version release

# Strict mode: fails if any unmanaged external snapshot dependency is detected
nmvn version release --strict

# Release specific artifacts with explicit target versions
nmvn version release -a com.example:core-lib#1.0.0 -a com.example:client-lib#1.0.0
```

---

### Project Cleaning (`nmvn clean`)

Cleans all Maven projects discovered by the workset scanner (same roots and
excludes as `nmvn version scan`), so `target/` copies of poms are skipped.

```bash
# Run `mvn clean` in every scanned project dir
nmvn clean

# Fast mode: delete `target/` directories directly without invoking Maven
nmvn clean --simple

# Clean a specific workset, root or exclude (also accepts positional roots)
nmvn clean --workset backend --simple
nmvn clean --root ../service-core --exclude "**/skipme/**"
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
    "**/target/**" ,
    "**/build/**" ,
    "**/.git/**" ,
    "**/.idea/**"
  ] ,

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

---

## Using `nmvn-lib` as a Java Library

Add `nmvn-lib` to your `pom.xml`:

```xml
<dependency>
    <groupId>net.thevpc.nmvn</groupId>
    <artifactId>nmvn-lib</artifactId>
    <version>1.0.0.0</version>
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

All 24 unit and CLI integration tests run automatically against temporary fixtures using native `NPath` operations.

---

## License

This project is licensed under the **GNU Lesser General Public License version 3 (LGPL-3.0)** — see the [LICENSE](LICENSE) file for details.

Copyright (c) 2026 thevpc.
