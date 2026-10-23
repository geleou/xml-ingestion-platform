#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f target/classes/Ingestor.class || ! -d target/dependency ]]; then
    echo 'Build first: mvn -Dmaven.repo.local=.cache/m2 package' >&2
    exit 2
fi
exec java -Xmx"${JAVA_HEAP:-512m}" -cp 'target/classes:target/dependency/*' Ingestor "$@"
