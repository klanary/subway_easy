import gzip
import sqlite3
from datetime import date, datetime, timedelta

from app.core.clock import KST
from ml.backup import backup_db, main, prune_backups, prune_raw
from ml.store import Store
from tests.unit.test_ml_store import _event

DAY = date(2026, 11, 15)
NOON = datetime(2026, 11, 15, 12, 0, 0, tzinfo=KST)


def _db(tmp_path):
    path = tmp_path / "c.sqlite"
    store = Store(path)
    store.upsert_events([_event(), _event(status=2)], {}, NOON)
    store.add_snapshot("5호선", NOON - timedelta(days=15), "INFO-000", "old")
    store.add_snapshot("5호선", NOON - timedelta(days=13), "INFO-000", "recent")
    store.add_snapshot("5호선", NOON, "INFO-000", "today")
    store.close()
    return path


def _count(path, table: str) -> int:
    con = sqlite3.connect(path)
    try:
        return con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
    finally:
        con.close()


def test_backup_is_a_gzipped_copy_named_by_day(tmp_path):
    db = _db(tmp_path)
    out = backup_db(db, tmp_path / "backups", DAY)
    assert out.name == "collector-20261115.sqlite.gz"
    restored = tmp_path / "restored.sqlite"
    restored.write_bytes(gzip.decompress(out.read_bytes()))
    assert (_count(restored, "events"), _count(restored, "raw_snapshots")) == (2, 3)
    assert [p.name for p in (tmp_path / "backups").iterdir()] == [out.name]  # 임시 파일 없음


def test_backup_works_while_the_collector_holds_the_db_open(tmp_path):
    db = _db(tmp_path)
    writer = Store(db)
    writer.upsert_events([_event(train_no="9999")], {}, NOON)  # 아직 커밋 전
    out = backup_db(db, tmp_path / "backups", DAY)
    writer.close()
    assert out.stat().st_size > 0


def test_prune_backups_keeps_the_newest(tmp_path):
    out = tmp_path / "backups"
    out.mkdir()
    for day in ("20261111", "20261113", "20261112"):
        (out / f"collector-{day}.sqlite.gz").write_bytes(b"x")
    (out / "메모.txt").write_text("남는다", encoding="utf-8")
    removed = prune_backups(out, keep=2)
    assert [p.name for p in removed] == ["collector-20261111.sqlite.gz"]
    assert sorted(p.name for p in out.iterdir()) == [
        "collector-20261112.sqlite.gz",
        "collector-20261113.sqlite.gz",
        "메모.txt",
    ]


def test_prune_raw_removes_only_old_snapshots(tmp_path):
    db = _db(tmp_path)
    assert prune_raw(db, keep_days=14, today=DAY) == 1
    assert (_count(db, "raw_snapshots"), _count(db, "events")) == (2, 2)
    assert prune_raw(db, keep_days=14, today=DAY) == 0


def test_main_backs_up_yesterday_then_prunes(tmp_path):
    db = _db(tmp_path)
    out = tmp_path / "backups"
    now = datetime(2026, 11, 16, 2, 10, tzinfo=KST)
    args = ["--db", str(db), "--out", str(out), "--keep", "7", "--raw-days", "14"]
    assert main(args, now=now) == 0
    assert [p.name for p in out.iterdir()] == ["collector-20261115.sqlite.gz"]
    restored = tmp_path / "r.sqlite"
    restored.write_bytes(gzip.decompress(next(out.iterdir()).read_bytes()))
    assert _count(restored, "raw_snapshots") == 3  # 백업에는 지우기 전 것이 다 있다
    assert _count(db, "raw_snapshots") == 2


def test_main_without_db_fails_clearly(tmp_path):
    assert main(["--db", str(tmp_path / "없음.sqlite"), "--out", str(tmp_path / "b")]) == 2
