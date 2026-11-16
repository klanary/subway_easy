from ml.scopes import BAND_SEC, band_of, merge_bands, parse_scope_key, scope_key


def test_band_is_the_service_day_hour():
    assert BAND_SEC == 3600
    assert (band_of(18000), band_of(21599), band_of(21600)) == (5, 5, 6)
    assert band_of(88200) == 24  # 00:30은 전날 운행의 24시대


def test_scope_key_round_trip():
    scope = (1, "9호선", 1, 6)
    assert scope_key(scope) == "1|9호선|1|6"
    assert parse_scope_key(scope_key(scope)) == scope


def test_merge_bands_joins_neighbours():
    assert merge_bands([5, 6, 8]) == [(5, 6), (8, 8)]
    assert merge_bands([7, 5, 6, 6]) == [(5, 7)]
    assert merge_bands([]) == []
