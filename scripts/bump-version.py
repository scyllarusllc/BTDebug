#!/usr/bin/env python3
"""Update Android versions to YYMMDDNN / YYYY.M.D before bundling."""

from datetime import date
from pathlib import Path
import re
import sys


def main() -> None:
    build_file = Path(__file__).resolve().parent.parent / "app/build.gradle.kts"
    source = build_file.read_text(encoding="utf-8")
    code_pattern = r"(?m)^(\s*versionCode\s*=\s*)(\d+)(\s*)$"
    name_pattern = r'(?m)^(\s*versionName\s*=\s*)"[^"\n]+"(\s*)$'
    codes = list(re.finditer(code_pattern, source))
    names = list(re.finditer(name_pattern, source))
    if len(codes) != 1 or len(names) != 1:
        raise ValueError("Expected exactly one versionCode and versionName in app/build.gradle.kts.")

    today = date.today()
    prefix = today.strftime("%y%m%d")
    current = codes[0].group(2)
    sequence = int(current[-2:]) + 1 if len(current) == 8 and current[:6] == prefix else 1
    if sequence > 99:
        raise ValueError("Today's version sequence has reached 99; cannot generate another YYMMDDNN version.")
    code = int(f"{prefix}{sequence:02d}")
    if code <= int(current):
        raise ValueError("Today's versionCode would not increase. Check the system date and existing version.")
    name = f"{today.year}.{today.month}.{today.day}"
    updated = re.sub(code_pattern, lambda match: f"{match.group(1)}{code}{match.group(3)}", source)
    updated = re.sub(name_pattern, lambda match: f'{match.group(1)}"{name}"{match.group(2)}', updated)
    build_file.write_text(updated, encoding="utf-8")
    print(f"Android version updated: {code} ({name})", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        print(f"Version update failed: {error}", file=sys.stderr)
        sys.exit(1)
