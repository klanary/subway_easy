# 90. Navigation 내부 전용 API 걷어 내기

> Claude Code가 쓴 초안이다. 본인이 직접 고쳐 쓴다(스펙 12절).

- 무엇을 만들었나: 역 검색에서 고른 역을 앞 화면에 돌려주는 `NavController.returnPick`을 공개 API로만 다시 썼다. 전에는 back stack 전체 목록(`currentBackStack`)을 뒤에서부터 훑어 받을 화면을 찾았는데, 이 속성은 Navigation 라이브러리 내부 전용(`@RestrictTo(LIBRARY_GROUP)`)이라 lint가 오류(RestrictedApi)로 막았다.
- 왜 이렇게 했나 (검토한 대안과 고른 이유):
  - 새 방식: 한 번 뒤로 가고, 맨 위가 검색 화면인 동안 계속 뒤로 간 뒤, 남은 맨 위 화면(`currentBackStackEntry`)의 SavedStateHandle에 결과를 넣는다. 받을 화면을 "찾는" 대신 "검색 화면을 다 걷어 낸 뒤 맨 위"로 정의를 바꾸니 목록이 필요 없었다.
  - 결과를 넣는 시점이 "닫기 전"에서 "닫은 뒤"로 바뀌었다. 받는 쪽(`OnPickResult`)은 SavedStateHandle의 StateFlow를 지켜보므로 순서와 상관없이 한 번 받는다.
  - `@SuppressLint("RestrictedApi")`로 덮는 방법도 있었지만, 라이브러리를 올릴 때 조용히 깨질 수 있어 고르지 않았다.
- 막혔던 점과 해결:
  - 기기(에뮬레이터)가 없어 Robolectric으로 확인했다. 바꾸기 전에 "검색이 두 겹 열린 경우"와 "노선도(홈)에서 연 검색" 테스트를 먼저 더해, 옛 코드에서 통과하는 것을 보고 나서 바꿨다(동작을 지키는 리팩터링).
- 예상 면접 질문과 내 답:
  - Q. lint 오류인데 앱은 잘 돌았다. 왜 고쳐야 하나?
  - A. 내부 전용 API는 하위 호환을 약속하지 않는다. 지금은 돌아도 라이브러리 버전을 올리면 이름이나 동작이 바뀔 수 있고, 그때는 컴파일이 깨지거나 더 나쁘면 조용히 다른 화면에 결과가 간다.
