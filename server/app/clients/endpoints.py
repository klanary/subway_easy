"""외부 API 주소. 샘플 수집 스크립트와 P2의 클라이언트가 함께 쓴다."""

# 요청 형식: {BASE}/{키}/json/realtimeStationArrival/0/50/{역 이름}
# 키가 URL 경로에 들어가므로 이 주소를 로그에 남길 때는 키를 가린다(스펙 6.8).
SEOUL_REALTIME_BASE = "http://swopenapi.seoul.go.kr/api/subway"

KAKAO_LOCAL_BASE = "https://dapi.kakao.com/v2/local"

# 대중교통 경로 조회(/v2/routing/publictraffic)의 전체 URL.
# 스펙 부록 A의 카카오 문서에서 확인한 뒤 채운다(P1 Task 4 Step 6).
# 비어 있으면 샘플 수집이 경로 항목을 건너뛴다.
KAKAO_ROUTE_URL = ""
