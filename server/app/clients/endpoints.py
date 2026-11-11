"""외부 API 주소. 샘플 수집 스크립트와 P2의 클라이언트가 함께 쓴다."""

# 요청 형식: {BASE}/{키}/json/realtimeStationArrival/0/50/{역 이름}
# 키가 URL 경로에 들어가므로 이 주소를 로그에 남길 때는 키를 가린다(스펙 6.8).
SEOUL_REALTIME_BASE = "http://swopenapi.seoul.go.kr/api/subway"

KAKAO_LOCAL_BASE = "https://dapi.kakao.com/v2/local"

# 대중교통 경로 조회. 카카오맵 REST API 문서에서 확인(2026-11-11, api-verification.md §3).
# GET, 헤더 Authorization: KakaoAK {REST 키}, 파라미터 start_x/start_y/end_x/end_y.
KAKAO_ROUTE_URL = "https://dapi.kakao.com/v2/routing/publictraffic"
