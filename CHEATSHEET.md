# NMvn cheat sheet

Fast commands for day-to-day use. Run them from the top of your Maven checkout
(or pass `-w <workset>`). Prefix with `nuts` (or `nuts-dev -y` in a Nuts dev
workspace); it is omitted below. Full details are in the [README](README.md).

**Golden rule:** add `--dry` first, read the diff, then run it again without `--dry`.

## Look before you touch

| Command | Use it when |
|---|---|
| `nmvn version scan` | You want to see which projects NMvn found, their versions and who depends on whom. Run this first whenever a command "finds nothing" or the wrong projects. |
| `nmvn version check` | Before a release or merge: finds mixed SNAPSHOT/release, version drift, parent mismatches and cycles. |
| `nmvn version check --json` | In CI; read `hasErrors` / `hasWarnings` (exit code is not reliable under Nuts yet). |
| `nmvn workset path` | You are not sure which workset file is being used. |

## Change versions

| Command | Use it when |
|---|---|
| `nmvn version release --dry` | Release time: every `X-SNAPSHOT` becomes `X` and all references follow. |
| `nmvn version release --strict --dry` | Same, but stop if anything still depends on an external SNAPSHOT. |
| `nmvn version release -a g:a#2.4.0 --dry` | Releasing everything, but one artifact needs a specific version. `-a` does **not** limit what is released. |
| `nmvn version bump g:a -p --dry` | Start a patch cycle on one module (`2.0.0` → `2.0.1-SNAPSHOT`); consumers' references are updated. `-m` minor, `-M` major. |
| `nmvn version bump g:a -m --cascade-versions --dry` | After a release, start the next cycle and also move dependent modules to SNAPSHOT. |
| `nmvn version bump g:a -p --cascade=none --dry` | Change only that module's own POM; leave every other POM alone. |
| `nmvn version bump --all -m --dry` | Lockstep: bump every module in the workset together. |
| `nmvn version bump g:a -p --force --dry` | The module is already a SNAPSHOT and you really want to bump it again. |
| `nmvn version update g:a#2.17.2 --dry` | Set an exact version: align a third-party dependency, parent POM or BOM everywhere it is referenced. |

Notes:

- `g:a` means `groupId:artifactId`; a version is added with `#` or `=` (`g:a#1.2.0`).
- A module without its own `<version>` follows its parent: bump or release the
  **parent**, not the module.
- Cascade modes need `=`: `--cascade=none`, `-c=versions` (not `-c none`).
- Bump without `-p`/`-m`/`-M` uses the workset default (`minor` unless configured).
- Every applied change appends to `.nmvn/version-history.tson`; commit it or
  ignore it.

## Narrow or widen the scan

| Option | Use it when |
|---|---|
| `-w backend` | You saved a named workset spanning several repositories. |
| `-r ../lib -r ../app` | One-off scan of specific folders instead of the workset roots. |
| `--exclude "**/samples/**"` | Some POMs must be left alone (also the way to release only part of a tree). |
| `--no-ignore` | You need projects that `.gitignore` or `target/`/`build/` filtering hides. |

## Worksets (several repositories as one)

| Command | Use it when |
|---|---|
| `nmvn workset set backend --root ../api --root ../core --exclude "**/generated/**"` | Create (or update) a named workset once. |
| `nmvn workset add-root ../jobs --workset backend --scan` | Add another checkout to it. |
| `nmvn workset remove-root ../jobs --workset backend --scan` | Drop a checkout. |
| `nmvn workset root list --workset backend` | See its roots and whether they exist. |
| `nmvn workset list` / `--recent` | Find the worksets you have. |
| `nmvn workset get backend` / `edit backend` | Inspect or hand-edit the TSON file. |

## Build and clean

| Command | Use it when |
|---|---|
| `nmvn install -DskipTests` | Any Maven goal: unknown commands go to the embedded Maven. |
| `nmvn build clean install` | Maven clean + install. `nmvn clean install` does **not** work (`install` is read as a folder). |
| `nmvn clean --simple` | Fast: delete `target/` of every scanned project without starting Maven. |
| `nmvn clean` | Run `mvn clean` in every scanned project. |
| `nmvn clean --gitignore --dry` | Preview repositories whose `target/` is not ignored or is still tracked by Git. |
| `nmvn clean --gitignore` | Add `target/` to each repo `.gitignore` and untrack it; keeps files on disk. |
| `nmvn get g:a:v` | Pull an artifact into `~/.m2`. |
| `nmvn diff-jar old.jar new.jar` | Check whether two jars (paths, URLs or `g:a:v`) differ, ignoring `META-INF`. |

## Typical release flow

```bash
nmvn version check                        # 1. nothing inconsistent
nmvn version release --strict --dry       # 2. review the diff
nmvn version release --strict             # 3. apply
nmvn build clean install                  # 4. build the release; tag/deploy as usual
nmvn version bump g:parent -m --cascade-versions --dry   # 5. open the next cycle
nmvn version bump g:parent -m --cascade-versions
```

## When something goes wrong

| Symptom | Fix |
|---|---|
| "No changes required" on release | Nothing is a SNAPSHOT; use `bump` or `update` instead. |
| Wrong or no projects touched | `nmvn version scan` from the same folder; check `nmvn workset path` for a global workset overriding the current directory. |
| "Refusing to modify versions from an incomplete scan" | A POM could not be read; fix it or `--exclude` it. |
| "inherits its version from parent" | Version the parent instead of the child module. |
| "Invalid coordinate format … got: none" | Write `-c=none` / `--cascade=none`. |
| `nuts-dev` stops on "should we proceed re-installation?" | Add `-y`. |
