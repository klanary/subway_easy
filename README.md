# 지금출발

![server](https://github.com/klanary/subway_easy/actions/workflows/server.yml/badge.svg)
![android](https://github.com/klanary/subway_easy/actions/workflows/android.yml/badge.svg)

지하철로 통학하는 사람을 위한 안드로이드 앱과 중계 서버. 역 실시간 도착, 길찾기와 지도, "지금 출발하면 탈 수 있는 열차" 알림과 하차 알림을 한곳에서 제공한다.

> 저장소 이름은 `subway_easy`, 앱 이름은 지금출발(가칭)이다.

## 저장소 구조

```
subway_easy/
├─ android/                 안드로이드 앱 (Kotlin, Jetpack Compose)
├─ server/                  중계 서버 (Python, FastAPI)
├─ docs/
│  ├─ superpowers/specs/    설계 스펙
│  ├─ superpowers/plans/    구현 계획
│  ├─ analysis/             원본 앱 분석, 1단계 확인 결과
│  ├─ measurements/         UX 비교, 부하 테스트 결과
│  └─ learning/             학습 노트
├─ .github/
│  ├─ workflows/            CI (server.yml, android.yml)
│  └─ ISSUE_TEMPLATE/       버그 템플릿
└─ README.md
```

## 문서

- [1차(MVP) 설계 스펙](docs/superpowers/specs/2026-11-02-jigeumchulbal-mvp-design.md)
- [구현 계획](docs/superpowers/plans/README.md)
- [1단계 확인 결과](docs/analysis/api-verification.md)
- [원본 앱 분석](docs/analysis/original-app.md)
- [원본 대비 UX 측정](docs/measurements/ux-comparison.md)
- [학습 노트](docs/learning/README.md)

## 배포

- 서버: https://jigeumchulbal-server.onrender.com (Render 무료 요금제, `render.yaml` 블루프린트, `develop` 브랜치, 2026-11-12)
- 공개 저장소 주소로 연결해 자동 배포는 꺼져 있다. 새 커밋은 Render 대시보드의 [Manual Deploy]로 올린다.
- 키 2개(`SEOUL_REALTIME_API_KEY`, `SEOUL_OPENAPI_KEY`)는 대시보드 환경 변수에만 있다. 카카오 키는 경로를 직접 계산하면서(2026-11-18) 쓰지 않는다.
- 경로: VM이 매일 03:30에 올리는 열차 운행표(릴리스 `measured-timetable`의 `trips-…`)로 서버가 RAPTOR 검색을 한다. 운행표를 받기 전에는 경로 요청이 503("경로 정보를 준비 중이에요")이다.
- 열차 고르기: 경로 응답의 지하철 단계마다 후보 열차(고른 열차 30분 전~2시간 뒤, 최대 40대)가 붙는다. 경로 상세에서 구간마다 [이전]/[다음]·급행/일반을 고르면 앱이 바로 다시 계산한다. 바꾼 구간만 고정하고, 뒤 구간은 앞 열차가 내린 뒤 탈 수 있는 첫 열차로 맞추며, 고정한 구간끼리 어긋나면 경고한다. [지금 출발 알림]은 고른 첫 열차에 맞추고, 놓치면 "고른 열차를 놓쳤어요"로 끝난다. 루틴은 고른 열차를 저장하지 않는다.
- 실측 시간표: 서버는 `MEASURED_BASE_URL`(기본: 이 저장소 릴리스 `measured-timetable`)에서 VM 결과물을 6시간마다 받는다. 비우면 실측을 쓰지 않는다(2026-11-18).
- 지표(`/v1/metrics`)는 대시보드 환경 변수 `METRICS_TOKEN`을 정해 두고, 같은 값을 `X-Metrics-Token` 헤더로 보내야 보인다. 정하지 않으면 404다(2026-11-17).
- 2026-11-12 배포 직후 확인: `/health`, 역 검색, 영등포구청 실시간 도착, 마포→공덕 경로, 영등포구청 5호선 시간표 모두 정상.
- 앱은 `android/local.properties`의 `server.baseUrl`을 이 주소로 둔다. 무료 서버는 쉬다가 깨어나는 데 1분 가까이 걸린다.

## 데이터 출처

- 서울 열린데이터광장: 서울시 지하철 실시간 도착정보, 서울시 역사마스터 정보, 서울시 지하철역 정보, 서울시 지하철역 시간표
- VM 위치 수집(서울시 실시간 열차 위치정보)으로 만든 실측 시간표·구간 시간
- 노선도: 직접 그린 "수도권 철도 노선도 아틀라스 에디션"(`server/data/raw/linemap_atlas.svg`, 2026-11-19). `server/scripts/build_linemap.py`가 역 위치표를 만든다.
