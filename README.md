# NMvn: Multi-Project Maven Version & Workset Manager

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](LICENSE)
[![Java: 1.8+](https://img.shields.io/badge/Java-1.8+-orange.svg)](https://openjdk.org/)
[![Nuts Package Manager](https://img.shields.io/badge/Nuts-0.8.4+-green.svg)](https://github.com/thevpc/nuts)

**NMvn** is an advanced developer tool and Java library for orchestrating multi-project Maven versioning, dependency graph analysis, discrepancy auditing, and cross-repository **Worksets**.

It provides two submodules:
1. **`nmvn-lib`**: An embeddable Java library providing surgical non-destructive POM editing, property-indirection resolution, cycle detection, and automated discrepancy checks. 
2. **`nmvn`**: A command-line tool built on the Nuts Application Framework (NAF), exposing intuitive `workset` and `version` subcommands.

---

## Table of Contents

- [The Problems NMvn Solves](#the-problems-nmvn-solves)
- [Key Concepts](#key-concepts)
  - [Worksets](#1-worksets)
  - [Non-Destructive XML Surgery](#2-non-destructive-xml-surgery)
  - [Property-Indirection & BOM Resolution](#3-property-indirection--bom-resolution)
  - [Workspace Discrepancy Auditing](#4-workspace-discrepancy-auditing)
- [CLI Command Reference](#cli-command-reference)
  - [Workset Management (`nmvn workset`)](#workset-management-nmvn-workset)
  - [Version Management (`nmvn version`)](#version-management-nmvn-version)
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

### 2. Non-Destructive XML Surgery

`nmvn-lib`'s `PomModifier` treats XML surgically:
- Only the specific `<version>` tag or `<properties><foo.version>` tag is targeted.
- Retains all original whitespace, indentation, XML declarations, and formatting.
- **Preserves all comments**, including trailing comments inside tag bodies (e.g. `<dep.version>1.0.0-SNAPSHOT<!-- internal --> </dep.version>`).
- Provides unified diffs in dry-run mode (`--dry`) before any byte is written to disk.

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

Use `bump` to **increment** versions using SemVer arithmetic (`--patch`, `--minor`, `--major`). `nmvn` automatically calculates the next version for you.

- **Idempotent by default**: If an artifact is already a `-SNAPSHOT` (e.g. `1.2.1-SNAPSHOT`), `bump --patch` leaves it unchanged unless `--force` (`-f`) is specified.
- **Release Immutability**: If a consuming module in the workspace is a release (not a snapshot) and its POM is modified because its dependency was bumped, it is **automatically bumped to `next-SNAPSHOT`**. Consuming modules that are already snapshots remain at their current version.

```bash
# Auto-increment patch (1.2.0 -> 1.2.1-SNAPSHOT)
nmvn version bump -a com.example:core --patch

# Auto-increment minor (1.2.0 -> 1.3.0-SNAPSHOT)
nmvn version bump -a com.example:core --minor

# Force advancing an already-snapshot artifact (1.2.1-SNAPSHOT -> 1.2.2-SNAPSHOT)
nmvn version bump -a com.example:core --patch --force

# Bump all workspace projects by minor in one command
nmvn version bump --minor

# Bump module and cascade version increments to all dependents
nmvn version bump -a com.example:core --patch --cascade-versions
```

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
    cascadePolicy : "cascade-references-only" // cascade-references-only | cascade-versions
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
