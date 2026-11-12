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
- 키 3개(`SEOUL_REALTIME_API_KEY`, `SEOUL_OPENAPI_KEY`, `KAKAO_REST_API_KEY`)는 대시보드 환경 변수에만 있다.
- 2026-11-12 배포 직후 확인: `/health`, 역 검색, 영등포구청 실시간 도착, 마포→공덕 경로, 영등포구청 5호선 시간표 모두 정상.
- 앱은 `android/local.properties`의 `server.baseUrl`을 이 주소로 둔다. 무료 서버는 쉬다가 깨어나는 데 1분 가까이 걸린다.

## 데이터 출처

- 서울 열린데이터광장: 서울시 지하철 실시간 도착정보, 서울시 역사마스터 정보, 서울시 지하철역 정보, 서울시 지하철역 시간표
- 카카오 길찾기(대중교통 경로) API
- 노선도: 위키미디어 공용 [Seoul_subway_linemap_ko.svg](https://commons.wikimedia.org/wiki/File:Seoul_subway_linemap_ko.svg) (퍼블릭 도메인, IRTC1015, 2025-06-29 판). `server/scripts/build_linemap.py`가 역 위치표를 만든다.
