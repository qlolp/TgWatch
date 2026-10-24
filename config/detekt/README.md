# Kotlin analysis

Detekt 1.23.8 runs its defaults plus `detekt.yml` on `app/src/main/java`.
This is syntax-based analysis without type resolution; JVM/Android tests and
Gradle scripts are outside its source set. Execute `bash ./gradlew detekt`.

The initial baseline was generated from the Kotlin source in commit e3d5348,
before the 1.13 changes, using the same pinned analyzer/configuration. It
started with 412 legacy findings; fixes have since reduced it to 408. CI
reports the count and fails if the baseline grows or gains a new ID. New
automation, diagnostics and other new files were excluded from baseline
generation. An existing baseline ID can still
match a modified function: baseline analysis catches new finding identities,
not every increase in severity inside a previously flagged function.

Review new findings and baseline diffs. Prefer small fixes and narrowly scoped,
explained suppression when a guard or UI orchestration intentionally exceeds a
rule. Do not run `detektBaseline` on the current checkout merely to pass CI.
If intentionally updating the baseline, document which findings were accepted
and why. `.editorconfig` supplies editor conventions; it is not a formatting
proof and no automatic whole-source rewrite is part of this change.
