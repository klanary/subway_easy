import pytest


def test_search_ranks_exact_prefix_contains(sample_index):
    ids = [s.id for s in sample_index.search("서울", 20)]
    assert ids == ["서울", "서울대입구", "서울숲", "강변"]


def test_search_tie_breaks_by_line_count_then_name(sample_index):
    ids = [s.id for s in sample_index.search("입구", 20)]
    assert ids == ["홍대입구", "서울대입구", "총신대입구"]


def test_chosung_search_and_limit(sample_index):
    assert [s.id for s in sample_index.search("ㅎㄷㅇㄱ", 20)] == ["홍대입구"]
    assert len(sample_index.search("ㅅ", 2)) == 2


def test_lines_are_ordered_numbered_first(sample_index):
    assert sample_index.get("홍대입구").lines == ("2호선", "경의중앙선", "공항철도")


@pytest.mark.parametrize(
    "raw, line, expected",
    [
        ("홍대입구역", None, "홍대입구"),
        ("이수", None, "이수"),
        ("총신대입구(이수)", None, "총신대입구"),
        ("동서울터미널", None, "강변"),
        ("서울역", "1호선", "서울"),
        ("양평", "경의중앙선", "양평(경의중앙선)"),
        ("양평", "5호선", "양평"),
        ("양평", None, "양평"),
    ],
)
def test_resolve(sample_index, raw, line, expected):
    assert sample_index.resolve(raw, line).id == expected


def test_resolve_unknown_is_none(sample_index):
    assert sample_index.resolve("없는역") is None


def test_same_station(sample_index):
    assert sample_index.same_station("서울역", "서울")
    assert not sample_index.same_station("이수", "총신대입구")


def test_nearest_within_2km(sample_index):
    station, dist = sample_index.nearest(37.5575, 126.9260)
    assert station.id == "홍대입구" and dist < 200
    assert sample_index.nearest(37.0, 125.0) is None


def test_shares_api_name(sample_index):
    assert sample_index.shares_api_name(sample_index.get("양평"))
    assert not sample_index.shares_api_name(sample_index.get("홍대입구"))


def test_real_data_resolves_names_from_other_sources(real_index):
    """실제 역 데이터(655개): 카카오 경로의 괄호 이름과 실시간 API의 이름이 같은 역으로 풀린다."""
    assert real_index.resolve("경복궁(정부서울청사)").id == "경복궁"
    assert real_index.resolve("서울").id == real_index.resolve("서울역").id == "서울"
    assert "1호선" in real_index.get("수원").lines and "경의중앙선" in real_index.get("옥수").lines
    assert real_index.get("천호").api_name == "천호(풍납토성)"
    assert real_index.resolve("운정").id == "운정" and real_index.get("운정중앙").lines == (
        "GTX-A",
    )


def test_real_api_names_verified_against_the_live_api(real_index):
    """2026-11-12 낮에 실시간 API로 전 역을 조회해 확인한 조회 이름(api-verification.md §2)."""
    assert real_index.get("군자").api_name == "군자(능동)"
    assert real_index.get("남한산성입구").api_name == "남한산성입구(성남법원,검찰청)"
    assert real_index.get("상도").api_name == "상도(중앙대앞)"
    # 스펙의 "대모산"은 조회되지 않았다
    assert real_index.get("대모산입구").api_name == "대모산입구"
    # 7호선 이수와 4호선 총신대입구는 한 이름으로 조회되고 두 호선이 함께 온다.
    shared = "총신대입구(이수)"
    assert real_index.get("이수").api_name == real_index.get("총신대입구").api_name == shared
    assert real_index.shares_api_name(real_index.get("이수"))
