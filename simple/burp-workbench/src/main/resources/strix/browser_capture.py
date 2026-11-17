"""Receive only the owned native Strix viewer capability; never handle OAuth."""
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.parse import parse_qsl, urlsplit


def main():
    if len(sys.argv) != 2:
        return 1
    raw = sys.argv[1]
    try:
        parsed = urlsplit(raw)
        query = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True)
        if (len(raw) > 1024 or parsed.scheme != "http" or parsed.hostname != "127.0.0.1"
                or parsed.netloc != "127.0.0.1:" + str(parsed.port)
                or not 1 <= parsed.port <= 65535 or parsed.path != "/" or parsed.fragment
                or len(query) != 1 or query[0][0] != "token"
                or not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", query[0][1])):
            return 1
        directory = Path(__file__).absolute().parent
        if directory.is_symlink() or directory.stat().st_mode & 0o077:
            return 1
        # An atomic, owner-only handoff prevents a partially read capability.
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=directory,
                                         prefix=".viewer-", delete=False) as output:
            temporary = Path(output.name)
            json.dump({"url": raw}, output)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, directory / "viewer-session.json")
        return 0
    except (OSError, ValueError, TypeError):
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
