from app.core.errors import InvalidRequest


def parse_latlng(value: str) -> tuple[float, float]:
    """ "위도,경도" 문자열. 형식이나 범위가 틀리면 400 INVALID_REQUEST(PARAMS)."""
    parts = value.split(",")
    if len(parts) != 2:
        raise InvalidRequest("PARAMS")
    try:
        lat, lng = float(parts[0]), float(parts[1])
    except ValueError as e:
        raise InvalidRequest("PARAMS") from e
    if not (-90 <= lat <= 90 and -180 <= lng <= 180):
        raise InvalidRequest("PARAMS")
    return lat, lng
