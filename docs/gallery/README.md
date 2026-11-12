# Seoul Open Data Plaza use case (gallery) application

Purpose: register a use case so the daily call limit on the realtime subway API keys is lifted. Links to localhost, GitHub or Notion are rejected, so the link is the web screen of the deployed server.

## Links

| Field | Value |
|---|---|
| Link name 1 | 지금출발 웹 (다음 열차 보기) |
| URL 1 | https://jigeumchulbal-server.onrender.com/ |
| Link name 2 | 지금출발 웹 — 시청역 예시 |
| URL 2 | https://jigeumchulbal-server.onrender.com/?station=시청 |

- The server runs on a free plan. If nobody visits for a while it goes to sleep, and the first load takes 30–60 seconds. Open it once just before you apply.

## Use case (gallery) description

역 이름을 검색하면 서울 지하철 역의 호선·방향별 다음 열차 두 대를 큰 글씨로 보여 주는 웹·안드로이드 서비스입니다. 실시간 도착 정보가 없는 방향은 역별 열차 시간표로 채웁니다.

## Main features

출근길에 지하철 앱을 열면 정보가 많아 정작 "내가 탈 열차가 몇 분 뒤에 오는지"를 찾기 어려워 만들었습니다.
- 역 이름이나 초성(예: ㅅㅊ)으로 검색하면 호선·방향별 다음 열차 두 대를 남은 분, 행선지, 막차·급행 표시와 함께 보여 줍니다(20초마다 갱신).
- 실시간 도착 정보가 없는 방향은 서울시 역별 열차 시간표로 채우고 "시간표"로 표시합니다.
- 안드로이드 앱에서는 노선도에서 역을 고르고, 빠른 경로와 환승 열차 시각을 보고, 출퇴근 루틴을 등록해 "지금 출발하세요" 알림을 받을 수 있습니다.
- 사용 데이터: 서울시 지하철 실시간 도착정보, 서울시 역코드로 지하철역별 열차 시간표 정보, 서울시 지하철역 정보.

## Images

| Field | File | Size |
|---|---|---|
| Logo image | `docs/gallery/logo140.png` | 140×140 |
| Screen image | `docs/gallery/screen700.png` | 700×700 (시청역, 2026-11-13 00:53, around the last train) |

- File names use only English letters and numbers, as the form requires.
- The screen image was captured after midnight, so there are many "막차" (last train) tags. Retaking it in daytime makes it look more natural:
  run the local server, then
  `msedge --headless=new --window-size=700,700 --virtual-time-budget=15000 --screenshot=docs/gallery/screen700.png "http://127.0.0.1:8000/?station=시청"`
