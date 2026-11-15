"""수집 DB 매일 백업(설계 §2.3).

사용법 (server/에서, 수집기가 쉬는 새벽에):
    python -m ml.backup            # 어제 날짜 이름으로 백업, 최근 7개 보관, raw는 14일 보관

수집기가 DB를 열고 있어도 된다(SQLite 온라인 백업 + WAL).
"""

import argparse
import gzip
import shutil
import sqlite3
import sys
from datetime import date, datetime, time, timedelta
from pathlib import Path

from app.core.clock import KST

BACKUP_KEEP = 7
RAW_KEEP_DAYS = 14
_PATTERN = "collector-*.sqlite.gz"


def backup_db(db_path: str | Path, out_dir: str | Path, day: date) -> Path:
    """DB 전체를 `collector-YYYYMMDD.sqlite.gz`로 복사한다."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / f"collector-{day:%Y%m%d}.sqlite.gz"
    plain = out_dir / f".collector-{day:%Y%m%d}.sqlite.tmp"
    packed = out_dir / f".collector-{day:%Y%m%d}.gz.tmp"
    source = sqlite3.connect(Path(db_path), timeout=30)
    copy = sqlite3.connect(plain)
    try:
        source.backup(copy)
    finally:
        copy.close()
        source.close()
    try:
        with open(plain, "rb") as src, gzip.open(packed, "wb", compresslevel=6) as dst:
            shutil.copyfileobj(src, dst)
        packed.replace(target)  # 다 쓴 뒤에 이름을 바꿔, 쓰다 만 파일이 백업으로 보이지 않게 한다
    finally:
        plain.unlink(missing_ok=True)
        packed.unlink(missing_ok=True)
    return target


def prune_backups(out_dir: str | Path, keep: int) -> list[Path]:
    """날짜가 가장 늦은 `keep`개만 남기고 지운다. 지운 파일 목록."""
    backups = sorted(Path(out_dir).glob(_PATTERN))
    removed = backups[: max(0, len(backups) - keep)]
    for path in removed:
        path.unlink()
    return removed


def prune_raw(db_path: str | Path, keep_days: int, today: date) -> int:
    """`keep_days`일보다 오래된 raw 응답만 지운다(events는 그대로). 지운 행 수."""
    cutoff = datetime.combine(today - timedelta(days=keep_days), time(0, 0), tzinfo=KST)
    con = sqlite3.connect(Path(db_path), timeout=30)
    try:
        cur = con.execute(
            "DELETE FROM raw_snapshots WHERE received_at < ?",
            (cutoff.isoformat(timespec="seconds"),),
        )
        con.commit()
        return cur.rowcount
    finally:
        con.close()


def main(argv: list[str] | None = None, *, now: datetime | None = None) -> int:
    parser = argparse.ArgumentParser(description="수집 DB 백업")
    parser.add_argument("--db", default="ml_data/collector.sqlite")
    parser.add_argument("--out", default="ml_data/backups")
    parser.add_argument("--keep", type=int, default=BACKUP_KEEP, help="남길 백업 수")
    parser.add_argument("--raw-days", type=int, default=RAW_KEEP_DAYS, help="raw 보관 일수")
    args = parser.parse_args(argv)
    if not Path(args.db).is_file():
        print(f"수집 DB가 없습니다: {args.db}", file=sys.stderr)
        return 2
    today = (now or datetime.now(KST)).astimezone(KST).date()
    target = backup_db(args.db, args.out, today - timedelta(days=1))
    removed = prune_backups(args.out, args.keep)
    deleted = prune_raw(args.db, args.raw_days, today)
    size_mb = target.stat().st_size / 1_000_000
    print(f"백업 {target} ({size_mb:.1f}MB), 지운 백업 {len(removed)}개, 지운 raw {deleted}행")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
