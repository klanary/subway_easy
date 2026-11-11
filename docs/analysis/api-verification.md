# 1단계 확인 결과

스펙 14절의 확인 항목을 실제로 확인한 결과를 적는다. 절 번호(§1~§6)는 스펙 14절의 항목 번호와 같고, 이후 계획은 "§3 경로 필드 대응표"처럼 이 번호로 가리킨다. 확인 결과가 스펙과 다르면 스펙을 먼저 고치고 스펙 부록 B에 이력을 남긴다.

> 공개 저장소다. 키 문자열과 본인 집·학교 좌표를 적지 않는다. 샘플 좌표는 공공 장소만 쓴다.

## §1 서울시 실시간 도착 API

| 항목 | 값 |
|---|---|
| 확인일 | 2026-11-11 (공식 샘플 키 `sample`, '서울'역 5행. 본인 키로 다시 확인할 것) |
| 요청 URL 형식 | `http://swopenapi.seoul.go.kr/api/subway/{키}/json/realtimeStationArrival/{시작}/{끝}/{역 이름}` |
| 샘플 | `server/tests/fixtures/seoul_realtime_*.json` |

### 필드 확인표

| 스펙 이름 | 실제 이름 | 값 예시 | 비고 |
|---|---|---|---|
| `barvlDt` | `barvlDt` | `"0"`, `"120"` | 문자열. 5행 중 4행이 `"0"`이라 안내 문구로 추정해야 한다 |
| `recptnDt` | `recptnDt` | `"2026-11-11 21:03:10"` | 한국 시간, 초 단위. 같은 응답 안에서도 6분 넘게 차이 나는 행이 있다 |
| `arvlMsg2` | `arvlMsg2` | `"서울 도착"`, `"서울 출발"`, `"전역 출발"`, `"[8]번째 전역 (한남)"`, `"4분 후 (삼각지)"` | |
| `trainLineNm` | `trainLineNm` | `"청량리행 - 시청방면"`, `"문산행 - 신촌(경의중앙선)방면 (급행)"` | 뒤에 ` (급행)`이 붙을 수 있다 |
| `updnLine` | `updnLine` | `"상행"` | |
| `btrainSttus` | `btrainSttus` | `"일반"`, `"급행"` | |
| 막차 표시 | `lstcarAt` | `"0"` | `"1"`이면 막차 |
| `subwayId` | `subwayId` | `"1001"` | 아래 호선 코드표 |
| `arvlCd` | `arvlCd` | `"1"`, `"2"`, `"3"`, `"99"` | 0 진입, 1 도착, 2 출발, 3 전역출발, 4 전역진입, 5 전역도착, 99 운행중(데이터셋 안내) |
| (열차 번호) | `btrainNo` | `"0492"`, `"A2231"` | 위치정보와 잇는 열쇠(위치 결합 스펙) |
| (종착역) | `bstatnNm` | `"청량리"` | `trainLineNm`의 "OO행"과 같다 |
| (지금 위치) | `arvlMsg3` | `"숙대입구"` | |
| (역 코드) | `statnId` | `"1004000426"` | 호선마다 다르다 |

### 호선 코드표 (`subwayId` → 호선)

| `subwayId` | 호선 |
|---|---|
| 1001 | 1호선 |
| 1004 | 4호선 |
| 1063 | 경의중앙선 |
| 1065 | 공항철도 |

샘플에서 본 값만 적었다. 나머지는 본인 키로 받은 뒤 채운다.

### 응답 코드표

| 경우 | 코드 | 메시지 | 서버 처리 (스펙 6.3) |
|---|---|---|---|
| 정상 | `INFO-000` | 정상 처리되었습니다. | 그대로 변환. `errorMessage` 안에 들어 있다 |
| 데이터 없음 | `INFO-200` | 해당하는 데이터가 없습니다. | 빈 `groups` (200). 없는 역 이름과 운행이 끝난 새벽 시간 모두 이 코드다(2026-11-12 02시, 본인 키) |
| 일일 한도 초과 | | | 만료 캐시가 있으면 `stale`, 없으면 `QUOTA_EXCEEDED` (503) |
| 그 밖 | `ERROR-336` 등 | (요청 범위 오류 등) | `UPSTREAM_ERROR` (502) |

- **오류도 HTTP 200으로 온다.** 정상 응답은 `{"errorMessage": {...}, "realtimeArrivalList": [...]}`이고, 오류 응답은 `errorMessage` 없이 `{"status": 500, "code": "ERROR-336", "message": ...}`처럼 납작하게 온다. 클라이언트는 두 형식을 모두 읽어야 한다(`tests/fixtures/seoul_realtime_sample_error.json`).
- 샘플 키는 '서울'역, 0~5행만 된다. 다른 역이나 더 넓은 범위는 `ERROR-336`이 온다.
- "데이터 없음"과 "일일 한도 초과" 코드는 본인 키로 확인한다.

## §2 서울시 역사마스터 정보

| 항목 | 값 |
|---|---|
| 파일 이름 | `server/data/raw/station_master.json` (OpenAPI `subwayStationMaster`, `python -m scripts.fetch_station_master`로 받는다. 내려받은 CSV도 읽는다) |
| 내려받은 날짜 | 2026-11-11 |
| 인코딩 | |
| 열 이름 | OpenAPI: `BLDN_ID`, `BLDN_NM`, `ROUTE`, `LAT`, `LOT` (2026-11-11 샘플 키로 확인. 값 예: `0150`, `서울역`, `1호선`, `37.556228`, `126.972135`) |
| 행 수 | 784 (`list_total_count`, 2026-11-11) |
| 좌표가 빈 행 수 | 0 |

### 호선 표기 목록

호선 열의 서로 다른 값을 그대로 적는다. **운영 호선이 아니라 선로 이름이다.** 괄호 안은 빌드가 바꾸는 운영 호선이다(`normalize.LINE_ALIASES`).

- 1호선, 2호선, 3호선, 4호선, 5호선, 6호선, 7호선, 8호선, 9호선, 경의중앙선, 경춘선, 경강선, 서해선, 신분당선, 우이신설선, 신림선, 인천1호선, 인천2호선, 김포골드라인, 에버라인선, 의정부선
- 경부선·경인선·장항선(1호선), 경원선(1호선. 단 이촌·서빙고·한남·옥수·응봉·왕십리·청량리 행은 경의중앙선), 일산선(3호선), 과천선·안산선·진접선(4호선), 별내선(8호선), 9호선(연장)(9호선), 7호선(인천)(7호선), 신분당선(연장)·신분당선(연장2)(신분당선), 중앙선(경의중앙선), 분당선·수인선(수인분당선), 공항철도1호선(공항철도), 수도권 광역급행철도(GTX-A)
- 빌드 결과: 역 655개, 호선 연결 784개, 별칭 132개

### 실시간 API 이름 예외

| 역 id | api_name | 근거 |
|---|---|---|
| 응암 | 응암순환(상선) | 데이터셋 안내(스펙 6.5) |
| 공릉 | 공릉(서울산업대입구) | 〃 |
| 남한산성입구 | 남한산성입구(성남법원, 검찰청) | 〃 |
| 대모산입구 | 대모산 | 〃 |
| 천호 | 천호(풍납토성) | 〃 |
| 몽촌토성 | 몽촌토성(평화의문) | 〃 |

실제 조회로는 아직 확인하지 못했다. 2026-11-12 새벽에는 모든 역이 `INFO-200`이었다. 낮에 다시 확인한다.

### 같은 이름·1km 넘게 떨어진 역

| 이름 | 호선 | 좌표 | 나눌 id |
|---|---|---|---|
| 양평 | 경의중앙선(원본 "중앙선") | 37.4928, 127.4918 (5호선 양평과 53.6km) | `양평(경의중앙선)` |
| 운정 | GTX-A(원본 "수도권 광역급행철도") | 37.7161, 126.7284 (경의중앙선 운정과 3.6km) | 이름을 `운정중앙`으로 고침(`ROW_FIXES`) |

### 이용허락 문구

| 데이터셋 | 문구 |
|---|---|
| 서울시 지하철 실시간 도착정보 | |
| 서울시 역사마스터 정보 | |

## §3 카카오

| 항목 | 값 |
|---|---|
| 앱 이름 | |
| 카카오맵 무료 쿼터 적용 여부 | 2026-11-12 본인 REST 키로 키워드 검색·좌표→주소·경로 조회 모두 HTTP 200 (쿼터 수치는 콘솔 [통계] > [쿼터]에서 확인) |

### API별 일일 쿼터

| API | 스펙 설정값 | 실제 쿼터 | 확인일 |
|---|---|---|---|
| 키워드로 장소 검색 | 100,000 | | |
| 좌표로 주소 변환 | 10,000 | | |
| 대중교통 경로 조회 | 1,000 | | |
| 지도 Android SDK | 300,000 | | |

### 경로 API

| 항목 | 값 |
|---|---|
| 전체 URL | `https://dapi.kakao.com/v2/routing/publictraffic` (2026-11-11, 카카오맵 REST API 문서) |
| 메서드 | GET |
| 파라미터 | 필수 `start_x`, `start_y`, `end_x`, `end_y`(경도·위도). 선택 `s_name`, `e_name`, `input_coord`, `output_coord`(기본 WGS84). 경유지와 출발 시각 파라미터는 없다 |
| 인증 헤더 | `Authorization: KakaoAK {키}` |

### 경로 필드 대응표

"카카오 응답 경로"는 `routes[0].…`처럼 샘플에서 그대로 따라갈 수 있게 적는다.

| 서버 필드 | 카카오 응답 경로 | 변환 규칙 |
|---|---|---|
| 경로 목록 | `routes[]` | `status`가 `OK`일 때만 있다 |
| `totalSec` | `routes[].properties.totalTime` | 초 |
| `distanceM` | `routes[].properties.totalDistance` | m |
| `fare` | `routes[].properties.fare.value` | 원 |
| `transfers` | `routes[].properties.transfers` | |
| 단계 목록 | `routes[].steps[]` | |
| `step.mode` 판별 | `steps[].properties.type` = `SUBWAY`·`BUS`·`WALKING` | `WALKING` → `WALK`. 환승 도보도 `WALKING` 단계로 온다 |
| `durationSec` | `steps[].properties.time` | 초 |
| 단계 `distanceM` | `steps[].properties.distance` | m |
| `guidance` | `steps[].properties.guidance` | 예: `2호선 (홍대입구 > 을지로3가)` |
| `path` 좌표 형식 | `steps[].path.points` = `[x, y]` 배열 | `[위도, 경도]`로 뒤집는다 |
| `lineName` | `steps[].properties.vehicles[0].name` (지하철) | 예: `2호선`. `vehicles[].type`은 `일반` 등 |
| `routeName` | `steps[].properties.vehicles[].name` (버스) | `kakao_route_bus_subway.json`으로 변환을 만들 때 확정 |
| `stops` | `steps[].properties.stops[].name` | 이름만 있다. 괄호 이름도 온다(`경복궁(정부서울청사)`) |

### 오류 응답 형식과 코드

- 형식: **HTTP 200**에 본문 `status`로 온다(2026-11-12 실제 샘플: 같은 지점 `EQUAL_POINTS`, 너무 먼 곳 `NO_RESULTS`). `routes`는 없고 `properties.total`이 0이다.
- **테스트앱 주의:** 카카오 앱이 "테스트앱" 설정이면 하루 쿼터가 아주 작아 HTTP 400, `code: -10`("API limit has been exceeded.")이 온다(카카오 데브톡 2026-07-27 답변). 일반 앱인지 먼저 확인한다.

| 카카오 코드 | HTTP 상태 | 서버 오류 (스펙 6.3) |
|---|---|---|
| `EQUAL_POINTS` | | `INVALID_REQUEST` (400) |
| `STARTNODES_NULL` | | `NO_ROUTE`, reason `NO_START_STOP` (404) |
| `ENDNODES_NULL` | | `NO_ROUTE`, reason `NO_END_STOP` (404) |
| `NO_RESULTS` | | `NO_ROUTE`, reason `NO_RESULTS` (404) |
| `INVALID_REQUEST` | | `UPSTREAM_ERROR` (502) |
| 한도 초과 | 429 | 만료 캐시가 있으면 `stale`, 없으면 `QUOTA_EXCEEDED` (503) |

### 지하철 호선 이름 표기 목록

경로 응답의 지하철 단계에 나오는 호선 이름을 그대로 적는다.

-

## §4 카카오 지도 SDK 실험

| 항목 | 값 |
|---|---|
| SDK 버전 | |
| 디버그 키 해시 등록 | |
| 실행 기기 | |
| 결과 스크린샷 | `docs/analysis/map-experiment.png` |

## §5 Render

| 항목 | 값 |
|---|---|
| 서비스 URL | |
| 잠든 뒤 첫 응답 시간 (초) | 1회: · 2회: · 3회: |
| 깨어난 뒤 응답 시간 (초) | 1회: · 2회: · 3회: |

## §6 GitHub

| 항목 | 값 |
|---|---|
| 저장소 | `klanary/subway_easy` |
| 패키지명 | `io.github.klanary.jigeumchulbal` |
