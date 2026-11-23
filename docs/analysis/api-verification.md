# 1단계 확인 결과

스펙 14절의 확인 항목을 실제로 확인한 결과를 적는다. 절 번호(§1~§6)는 스펙 14절의 항목 번호와 같고, 이후 계획은 "§3 경로 필드 대응표"처럼 이 번호로 가리킨다. 확인 결과가 스펙과 다르면 스펙을 먼저 고치고 스펙 부록 B에 이력을 남긴다.

> 공개 저장소다. 키 문자열과 본인 집·학교 좌표를 적지 않는다. 샘플 좌표는 공공 장소만 쓴다.

## §1 서울시 실시간 도착 API

| 항목 | 값 |
|---|---|
| 확인일 | |
| 요청 URL 형식 | `http://swopenapi.seoul.go.kr/api/subway/{키}/json/realtimeStationArrival/{시작}/{끝}/{역 이름}` |
| 샘플 | `server/tests/fixtures/seoul_realtime_*.json` |

### 필드 확인표

| 스펙 이름 | 실제 이름 | 값 예시 | 비고 |
|---|---|---|---|
| `barvlDt` | | | |
| `recptnDt` | | | |
| `arvlMsg2` | | | |
| `trainLineNm` | | | |
| `updnLine` | | | |
| `btrainSttus` | | | |
| 막차 표시 | | | |
| `subwayId` | | | |
| `arvlCd` | | | |

### 호선 코드표 (`subwayId` → 호선)

| `subwayId` | 호선 |
|---|---|
| | |

### 응답 코드표

| 경우 | 코드 | 메시지 | 서버 처리 (스펙 6.3) |
|---|---|---|---|
| 정상 | | | 그대로 변환 |
| 데이터 없음 | | | 빈 `groups` (200) |
| 일일 한도 초과 | | | 만료 캐시가 있으면 `stale`, 없으면 `QUOTA_EXCEEDED` (503) |
| 그 밖 | | | `UPSTREAM_ERROR` (502) |

## §2 서울시 역사마스터 정보

| 항목 | 값 |
|---|---|
| 파일 이름 | `server/data/raw/station_master.csv` |
| 내려받은 날짜 | |
| 인코딩 | |
| 열 이름 | |
| 행 수 | |
| 좌표가 빈 행 수 | |

### 호선 표기 목록

호선 열의 서로 다른 값을 그대로 적는다.

-

### 실시간 API 이름 예외

| 역 id | api_name | 근거 |
|---|---|---|
| | | |

### 같은 이름·1km 넘게 떨어진 역

| 이름 | 호선 | 좌표 | 나눌 id |
|---|---|---|---|
| | | | |

### 이용허락 문구

| 데이터셋 | 문구 |
|---|---|
| 서울시 지하철 실시간 도착정보 | |
| 서울시 역사마스터 정보 | |

## §3 카카오

| 항목 | 값 |
|---|---|
| 앱 이름 | |
| 카카오맵 무료 쿼터 적용 여부 | |

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
| 전체 URL | |
| 메서드 | |
| 파라미터 | |
| 인증 헤더 | `Authorization: KakaoAK {키}` |

### 경로 필드 대응표

"카카오 응답 경로"는 `routes[0].…`처럼 샘플에서 그대로 따라갈 수 있게 적는다.

| 서버 필드 | 카카오 응답 경로 | 변환 규칙 |
|---|---|---|
| 경로 목록 | | |
| `totalSec` | | |
| `distanceM` | | |
| `fare` | | |
| `transfers` | | |
| 단계 목록 | | |
| `step.mode` 판별 | | |
| `durationSec` | | |
| 단계 `distanceM` | | |
| `guidance` | | |
| `path` 좌표 형식 | | |
| `lineName` | | |
| `routeName` | | |
| `stops` | | |

### 오류 응답 형식과 코드

- 형식:

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
