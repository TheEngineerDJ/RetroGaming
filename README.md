# RetroVault

Evidence-first identification for retro-gaming artifacts.

The first vertical slice is ROM normalization: take a messy local library,
work out what each file actually is, explain why, and rename only what can be
explained.

The governing rule, from `TESTING_SPEC.md`:

> A missed match is acceptable. A wrong match presented as certain is not.

Specifications live in this repository and are authoritative. `Constitution.md`
is the highest authority; `CLAUDE_CODE.md` explains how to consume the chain.

---

## Building and testing

```bash
./gradlew test          # every JVM test
./gradlew build         # compile and test everything available
```

The core modules build and test on a plain JDK 17+. **No Android SDK is
required** for that: `settings.gradle.kts` includes `:platform-android` and
`:app` only when an SDK is present (via `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or
`sdk.dir` in `local.properties`). With an SDK installed, `./gradlew build`
compiles the Android modules too.

This is a build-tooling decision only. It does not weaken layering: the Android
modules are infrastructure and presentation, and hold no identity, naming or
rename rules.

### Where the Gradle plugins come from

All Gradle plugins are loaded from one classpath, declared in `buildSrc`, and
applied by id with no version in module build files. That is not stylistic:

- The Kotlin Gradle plugin must load exactly once. Declaring it at the root
  while an Android module resolves its own copy loads it twice, and Gradle
  rejects that as a classpath conflict.
- The Kotlin Android plugin references Android Gradle plugin types. If the two
  are loaded into different classloaders, applying it fails with
  `NoClassDefFoundError: com/android/build/gradle/api/BaseVariant`.

`buildSrc` adds the Android plugins only when an SDK is present, mirroring the
module-inclusion check, so a JVM-only contributor never has to reach Google's
Maven repository. Building the Android modules does require access to it.

---

## Modules

Dependency direction is `UI → Application → Domain ← Infrastructure`
(`ENGINEERING_SPEC.md` section 1). Nothing points inward at the domain.

| Module | Layer | Contains |
|---|---|---|
| `core-domain` | Domain | Identity, evidence, resolution, naming, rename planning. Pure Kotlin: no coroutines, no I/O, no Android. |
| `core-application` | Application | Ports and use cases. Coroutines, bounded concurrency, typed failures. |
| `core-dat` | Infrastructure | Streaming XML scanner and Logiqx DAT parser. |
| `core-io` | Infrastructure | Streaming hashing and bounded ZIP inspection. Shared by JVM and Android. |
| `core-data` | Infrastructure | SQLite schema, migrations and repositories over a small driver abstraction. |
| `core-data-jdbc` | Infrastructure | JVM SQLite binding, for tests and desktop hosts. |
| `platform-jvm` | Infrastructure | Local filesystem adapters. Hosts the end-to-end tests. |
| `platform-android` | Infrastructure | Storage Access Framework adapters and the Android SQLite binding. |
| `app` | Presentation | Compose UI and a view model that maps state and calls use cases. |

---

## The pipeline

```
SAF folder → recursive walk → container inspection → observation
  → size lookup → CRC32 → cryptographic hash → bounded title fallback
  → candidates + evidence → resolution state + confidence
  → canonical filename → whole-batch validation → dry run
  → journalled execution → reconciliation
```

Some properties worth stating explicitly, because they are what make the slice
trustworthy rather than merely functional:

- **The resolver performs no I/O.** It requests evidence and the application
  fetches it. Every escalation rule is therefore unit-testable with no
  filesystem, database or device.
- **CRC32 never settles identity.** A unique CRC32 hit still escalates when the
  catalogue record carries MD5 or SHA1. A CRC32 match contradicted by a strong
  hash resolves to `CONFLICT`, not to a match.
- **Corroboration and ambiguity are different.** Two datasets describing the
  same release corroborate each other. Two records describing *different*
  releases under one hash are ambiguous, and nothing is selected.
- **Size filtering is an optimisation, not proof.** When a file's size appears
  in no record, that is reported as evidence so the user can tell it apart from
  a genuine absence.
- **Representation differences are not identity differences.** A size mismatch
  weakens a candidate; a region, revision, disc or hash mismatch eliminates it.
- **Validation is all-or-nothing.** One blocking issue refuses the whole batch.
  The user excludes the offending entry and revalidates.
- **The journal is written before the mutation.** An interrupted batch is
  reconstructable, and reconciliation reports "unknown" rather than guessing.
- **Dry run writes nothing at all** — no filesystem change and no journal.

---

## Testing

```
core-domain      identity rules, tokenizing, similarity, naming, planning, validation
core-dat         DAT parsing, malformed input, XXE and entity-expansion resistance
core-io          hashing vectors, archive bounds, decompression bombs, traversal paths
core-application scan orchestration, bounded concurrency, cancellation, filtering
core-data        migrations, foreign keys, round-trip fidelity, re-import, journal
platform-jvm     the whole slice, end to end, against real files and real SQLite
```

The corpus deliberately includes adversarial cases: sequel numbering,
misleading filenames, CRC collisions, region variants, revisions, scene and
scrubbed names, multi-artifact archives, malformed DATs, hostile archive paths
and duplicate destinations.

All fixtures are synthetic. No copyrighted DAT data and no ROM content is
committed to this repository.

---

## Known gaps

- `platform-android` and `app` are written but **have not been compiled or run**
  in this environment, which has no Android SDK. They need a device pass before
  any claim is made about provider behaviour, rename support or UI correctness.
- Naming profiles ship as No-Intro-style and minimal only.
- Multi-disc sets, `.cue`/`.bin` relationships, header detection and nested
  archives are out of scope for this slice.
