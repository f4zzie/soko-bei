#!/usr/bin/env bash
set -euo pipefail
umask 077
root="${SOKO_BACKUP_DIR:-/home/ubuntu/soko-backups}"
mkdir -p "$root/cassandra" "$root/mongo"
exec 9>"$root/backup.lock"
flock -n 9 || exit 0
tag="soko-backup-$(date -u +%Y%m%dT%H%M%SZ)"
docker exec soko-cassandra nodetool snapshot -t "$tag" soko_bei
docker exec soko-cassandra sh -c 'cd /var/lib/cassandra/data && find soko_bei -type d -path "*/snapshots/$1" -print0 | tar --null -T - -czf -' sh "$tag" > "$root/cassandra/$tag.tar.gz.partial"
gzip -t "$root/cassandra/$tag.tar.gz.partial"
mv "$root/cassandra/$tag.tar.gz.partial" "$root/cassandra/$tag.tar.gz"
docker exec soko-mongo mongodump --db soko_bei --archive --gzip > "$root/mongo/$tag.archive.gz.partial"
gzip -t "$root/mongo/$tag.archive.gz.partial"
mv "$root/mongo/$tag.archive.gz.partial" "$root/mongo/$tag.archive.gz"
sha256sum "$root/cassandra/$tag.tar.gz" "$root/mongo/$tag.archive.gz" > "$root/$tag.sha256"
docker exec soko-cassandra nodetool clearsnapshot -t "$tag" soko_bei
python3 - "$root" <<'PY'
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

root = Path(sys.argv[1])
cutoff = datetime.now(timezone.utc) - timedelta(days=7)
pattern = re.compile(r"soko-backup-(\d{8}T\d{6}Z)\.(?:tar\.gz|archive\.gz|sha256)")
for directory in (root, root / "cassandra", root / "mongo"):
    for path in directory.iterdir():
        match = pattern.fullmatch(path.name)
        if match and path.is_file() and not path.is_symlink():
            created = datetime.strptime(match[1], "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
            if created < cutoff:
                path.unlink()
                print(f"Expired backup: {path.name}")
PY
du -sh "$root/cassandra" "$root/mongo"
df -h "$root"
