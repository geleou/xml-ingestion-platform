#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import os
import secrets
from pathlib import Path

path = Path("secrets/db_password")
path.parent.mkdir(mode=0o700, exist_ok=True)
try:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    pass
else:
    with os.fdopen(descriptor, "w") as output:
        output.write(secrets.token_urlsafe(32) + "\n")
PY
docker compose up -d --wait db
