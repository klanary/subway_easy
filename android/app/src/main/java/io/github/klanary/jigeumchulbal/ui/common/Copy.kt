package io.github.klanary.jigeumchulbal.ui.common

/** 사용자에게 보이는 문구. 서버 오류 문구는 서버 message를 그대로 쓴다. */
object Copy {
    const val SERVER_PROBLEM = "서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요."
    const val OFFLINE = "인터넷에 연결되어 있지 않아요."
    const val NETWORK_ERROR = "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."
    const val UNEXPECTED_ERROR = "문제가 생겼어요. 다시 시도해 주세요."
    const val RETRY = "다시 시도"
    const val STALE = "갱신 지연"
    const val MAP_FAILED = "지도를 불러오지 못했어요"
    const val NO_INFO = "정보 없음"
    const val ARRIVING = "곧 도착"
    const val BACK = "뒤로"

    // 통합 검색
    const val SEARCH_HINT = "역, 장소 검색"
    const val MY_PLACES = "내 장소"
    const val RECENT_SEARCHES = "최근 검색"
    const val STATIONS = "역"
    const val PLACES = "장소"
    const val REALTIME = "실시간"
    const val PICK_ON_MAP = "지도에서 선택"
    const val NO_RESULTS = "검색 결과가 없어요"

    // 경로 편집
    const val CURRENT_LOCATION = "현재 위치"
    const val ORIGIN_HINT = "출발지를 검색하거나 현재 위치를 허용해 주세요"
    const val ROUTE_EDIT_TITLE = "경로 찾기"
    const val ORIGIN = "출발"
    const val DEST = "도착"
    const val DEST_HINT = "도착지를 검색하세요"
    const val SWAP = "출발·도착 바꾸기"
    const val SEARCH_ROUTES = "경로 검색"

    // 지도 핀 선택
    const val NO_ADDRESS = "주소 정보 없음"
    const val NO_STATION_NEARBY = "근처 2km 안에 역이 없어요"
    const val PIN_LABEL = "지도에서 선택한 위치"
    const val USE_THIS_PLACE = "이 위치로 지정"

    // 경로 결과
    const val NO_ROUTES = "경로가 없어요"
    const val ROUTE_GONE = "경로 정보가 없어요. 경로를 다시 검색해 주세요."

    // 역 상세
    const val NO_ARRIVALS = "도착 정보가 없어요"
    const val FAVORITE = "즐겨찾기"
    const val EXPRESS = "급행"
    const val LAST_TRAIN = "막차"

    // 홈
    const val APP_NAME = "지금출발"
    const val SETTINGS = "설정"
    const val SEARCH_BAR = "어디로 갈까요?"
    const val FAVORITE_STATIONS = "즐겨찾는 역"
    const val HOME = "집"
    const val SCHOOL = "학교"
    const val WORK = "회사"
    const val CUSTOM_PLACE = "직접 추가"
}
