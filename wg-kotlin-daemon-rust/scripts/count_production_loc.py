"""Count daemon production code across all platforms, excluding tests."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--cloc", nargs="+", default=["cloc"], help="cloc command, e.g. --cloc perl /path/to/cloc")
args = parser.parse_args()
source_root = Path(__file__).resolve().parents[1] / "daemon/src"
physical = 0

with tempfile.TemporaryDirectory(prefix="wg-daemon-loc-") as temporary:
    files = []
    for source_file in sorted(source_root.rglob("*.rs")):
        if source_file.name.endswith("_tests.rs"):
            continue
        # Production precedes the test-only declarations at the end of each module.
        source = re.split(r"(?m)^#\[cfg\((?:all\()?test\b", source_file.read_text(encoding="utf-8"))[0]
        physical += len(source.splitlines())
        destination = Path(temporary) / source_file.relative_to(source_root)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(source, encoding="utf-8")
        files.append(str(destination))

    result = subprocess.run([*args.cloc, "--json", "--quiet", *files], check=True, stdout=subprocess.PIPE, text=True)
    report = json.loads(result.stdout)

print(json.dumps({"cloc_version": report["header"]["cloc_version"], "physical": physical, **report["SUM"]}, indent=2))
