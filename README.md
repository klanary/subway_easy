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
