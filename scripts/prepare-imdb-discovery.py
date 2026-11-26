"""Refresh private independent discovery data and atomically activate its verified manifest."""
from pathlib import Path
import argparse
import json
import sys
from imdb_discovery import refresh


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reuse-downloads", action="store_true", help="Verify/rebuild from cached inputs without claiming a network refresh")
    args = parser.parse_args()
    try:
        result = refresh(Path(__file__).resolve().parents[1], reuse=args.reuse_downloads,
                         progress=lambda message: print(message, flush=True))
    except (Exception, KeyboardInterrupt) as error:
        print(f"Refresh did not finish; no incomplete snapshot was activated: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
