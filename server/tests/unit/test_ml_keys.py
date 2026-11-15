from datetime import date, timedelta

import pytest

from ml.keys import KEY_ENV, KeyRing, load_keys

DAY = date(2026, 11, 15)


def _env_file(tmp_path, text: str):
    path = tmp_path / ".env"
    path.write_text(text, encoding="utf-8")
    return path


def test_load_keys_reads_only_collector_keys(tmp_path):
    env = _env_file(
        tmp_path,
        "SEOUL_REALTIME_API_KEY=REALTIME\nSEOUL_OPENAPI_KEY=GENERAL\n"
        "SEOUL_SPARE_KEY_2=S2\nSEOUL_POSITION_API_KEY=POS\nSEOUL_SPARE_KEY_1=S1\n",
    )
    keys = load_keys(env, {})
    assert list(keys.items()) == [("position", "POS"), ("spare1", "S1"), ("spare2", "S2")]
    assert "REALTIME" not in keys.values() and "GENERAL" not in keys.values()


def test_load_keys_skips_empty_and_prefers_environment(tmp_path):
    env = _env_file(tmp_path, "SEOUL_POSITION_API_KEY=\nSEOUL_SPARE_KEY_1=FILE\n")
    keys = load_keys(env, {"SEOUL_SPARE_KEY_1": "ENV", "SEOUL_REALTIME_API_KEY": "REALTIME"})
    assert keys == {"spare1": "ENV"}


def test_load_keys_without_env_file():
    assert load_keys(None, {"SEOUL_POSITION_API_KEY": " POS "}) == {"position": "POS"}


def test_key_env_is_fixed_allow_list():
    assert list(KEY_ENV) == ["position", "spare1", "spare2"]
    assert not any("REALTIME" in name or "OPENAPI" in name for name in KEY_ENV.values())


def test_ring_moves_to_next_key_for_the_day():
    ring = KeyRing({"position": "POS", "spare1": "S1"})
    assert ring.current(DAY) == ("position", "POS")
    ring.block("position", DAY)
    assert ring.current(DAY) == ("spare1", "S1")
    assert ring.current(DAY + timedelta(days=1)) == ("position", "POS")


def test_ring_returns_none_when_all_blocked():
    ring = KeyRing({"position": "POS"}, blocked={"position": DAY})
    assert ring.current(DAY) is None


def test_ring_rejects_unknown_names():
    with pytest.raises(ValueError):
        KeyRing({"realtime": "X"})


def test_ring_keeps_fixed_order_whatever_the_input_order():
    ring = KeyRing({"spare2": "S2", "position": "POS"})
    assert ring.names == ("position", "spare2")
