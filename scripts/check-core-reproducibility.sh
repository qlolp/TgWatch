#!/usr/bin/env bash
# Hash the actual first-party JAR produced with both normal and private file masks.
set -Eeuo pipefail
core_version=$(sed -n 's/^version=//p' version.properties)
[[ "$core_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
for mask in 022 077; do
  (
    umask "$mask"
    # A reused Kotlin daemon retains its original mask and can hide this bug.
    bash ./gradlew :probe-core:clean :probe-core:jar -Pkotlin.compiler.execution.strategy=in-process --no-daemon
  ) > "$scratch/$mask.log" 2>&1 || {
    tail -40 "$scratch/$mask.log"
    exit 1
  }
  cp "probe-core/build/libs/probe-core-$core_version.jar" "$scratch/$mask.jar"
done
if ! cmp -s "$scratch/022.jar" "$scratch/077.jar"; then
  echo '::error title=Core JAR reproducibility::Normal and restrictive umasks produced different first-party JAR bytes.' >&2
  sha256sum "$scratch/022.jar" "$scratch/077.jar"
  python3 - "$scratch/022.jar" "$scratch/077.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as a, zipfile.ZipFile(sys.argv[2]) as b:
    modes = [(name, oct(a.getinfo(name).external_attr >> 16), oct(b.getinfo(name).external_attr >> 16))
             for name in a.namelist() if name in b.namelist() and
             a.getinfo(name).external_attr != b.getinfo(name).external_attr]
    print('ZIP mode differences:', modes[:6])
PY
  exit 1
fi
echo 'First-party JAR is byte-identical with umasks 022 and 077.'
