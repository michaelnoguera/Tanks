# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""List literal shader setup sites, excluding Java comments; not a reachability proof."""
import csv
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[3]
writer = csv.writer(sys.stdout, lineterminator="\n")
writer.writerow(["source", "line", "setup_expression", "resources"])
for source in sorted((root / "src/main/java").rglob("*.java")):
    text = source.read_text()
    # Preserve line numbers while removing comments; retain quoted Java strings.
    pattern = r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/'
    text = re.sub(pattern, lambda m: m[0] if m[0].startswith('"') else
                  re.sub(r"[^\n]", " ", m[0]), text)
    for match in re.finditer(r"[\w.]+\.setUp\(([^;]+)\);", text):
        resources = re.findall(r'"(/shaders/[^"\n]+)"', match[0])
        if resources:
            for resource in resources:
                if not (root / "src/main/resources" / resource.lstrip("/")).is_file():
                    raise FileNotFoundError(resource)
            writer.writerow([source.relative_to(root), text.count("\n", 0, match.start()) + 1,
                             " ".join(match[0].split()), " | ".join(resources)])
