#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 2 ]]; then
    echo 'Usage: run.sh TANKS_JAR NEW_OUTPUT_DIRECTORY' >&2
    exit 2
fi
jar=$(realpath "$1")
output=$(realpath -m "$2")
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ -f "$jar" ]] || { echo "Missing JAR: $jar" >&2; exit 2; }
mkdir "$output"
mkdir "$output/classes"
javac -cp "$jar" -d "$output/classes" "$script_dir/OpenGLBaseline.java"
sha256sum "$jar" "$script_dir/OpenGLBaseline.java" > "$output/inputs.sha256"
git -C "$script_dir" rev-parse HEAD > "$output/revision.txt"
for shadows in false true; do
    for lights in false true; do
        for truetype in false true; do
            scenario="$output/shadows-$shadows-lights-$lights-truetype-$truetype"
            timeout 120s java -Djava.awt.headless=true -cp "$output/classes:$jar" OpenGLBaseline \
                "$scenario" "$shadows" "$lights" "$truetype" > "$scenario.log" 2>&1
            test -f "$scenario/verified-back.txt"
        done
    done
done
