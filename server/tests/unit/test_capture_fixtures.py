from pathlib import Path

from scripts.capture_fixtures import CAPTURE_PLAN, LANDMARKS, fixture_path, redact


def test_redact_replaces_every_secret():
    assert (
        redact('{"k": "AB12"} /api/subway/AB12/json', ["AB12", ""])
        == '{"k": "***"} /api/subway/***/json'
    )


def test_fixture_names_are_unique_and_ascii():
    names = [fixture_path(i, Path("f")).name for i in CAPTURE_PLAN]
    assert len(names) == len(set(names)) == 13
    assert all(n.isascii() for n in names)


def test_capture_plan_uses_only_public_landmarks():
    allowed = set(LANDMARKS.values())
    assert all(p in allowed for item in CAPTURE_PLAN for p in item.points())
