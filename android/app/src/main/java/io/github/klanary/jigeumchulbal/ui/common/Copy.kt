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
    const val SEARCH_HINT = "역 이름 또는 초성"
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
    const val VIA = "경유"
    const val VIA_HINT = "경유지를 검색하세요"
    const val ADD_VIA = "+ 경유지"
    const val REMOVE_VIA = "경유지 지우기"
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
    const val FAVORITE_ROUTES = "자주 가는 경로"
    const val ADD_FAVORITE = "★ 즐겨찾기"
    const val REMOVE_FAVORITE = "★ 즐겨찾기 해제"
    const val HOME = "집"
    const val SCHOOL = "학교"
    const val WORK = "회사"
    const val CUSTOM_PLACE = "직접 추가"

    // 설정
    const val ADD_CUSTOM_PLACE = "+ 직접 추가"
    const val NOT_SET = "지정 안 됨"
    const val SET_BY_SEARCH = "검색"
    const val SET_ON_MAP = "지도"
    const val DELETE = "삭제"
    const val ALERT_TIMING = "알림 시점"
    const val DEPART_MARGIN = "출발 여유 시간"
    const val ALIGHT_LEAD = "하차·환승 알림"
    const val STATION_LEAD = "역 상세 승차 전 알림"
    const val MAX_WAIT = "최대 대기 시간"
    const val PERMISSIONS = "권한"
    const val PERMISSION_LOCATION = "위치"
    const val PERMISSION_NOTIFICATIONS = "알림"
    const val PERMISSION_EXACT_ALARM = "알람 및 리마인더"
    const val GRANTED = "허용됨"
    const val NOT_GRANTED = "허용 안 됨"
    const val OPEN_SETTINGS = "설정 열기"
    const val ABOUT = "정보"

    // 통학 세션
    const val SESSION_TITLE = "통학 알림"
    const val START_ALIGHT_ALERT = "출발(하차 알림)"
    const val PRE_BOARDING_ALERT = "승차 전 알림"
    const val NO_REALTIME_FOR_ROUTE = "이 경로는 실시간 열차 정보를 쓸 수 없어요"
    const val SESSION_IN_PROGRESS = "통학 알림 진행 중"
    const val NO_SESSION = "진행 중인 통학 알림이 없어요"
    const val MAY_BE_LATE = "알림이 늦을 수 있어요"

    /** 탄 열차를 3회 연속 못 찾았을 때 진행 중 알림 부제(위치 결합 스펙 7.2). */
    const val TRACKING_LOST = "실시간 추적 끊김"

    // 탄 열차 직접 고르기(위치 결합 스펙 7.1)
    const val PICK_TRAIN_HINT = "탄 열차를 고르면 도착 시각을 맞춰 드려요"
    const val PICK_TRAIN = "탄 열차 고르기"
    const val CHANGE_TRAIN = "열차 바꾸기"
    const val OTHER_TRAIN = "다른 열차였어요"
    const val PICK_TRAIN_TITLE = "어느 열차에 탔나요?"
    const val NO_NEAR_TRAINS = "근처에 고를 수 있는 열차가 없어요"
    const val LOADING_TRAINS = "근처 열차를 불러오고 있어요"

    // 경로 상세에서 탑승을 알아냈을 때 묻기(위치 결합 스펙 7.1)
    const val BOARDED_QUESTION = "열차에 타셨나요?"
    const val START_ALIGHT_ALERTS = "하차 알림 시작"
    const val NOT_BOARDED = "아니에요"
    const val IN_APP_ONLY = "알림 권한이 없어 이 화면에서만 안내해요"
    const val ALIGHT_EXPECTED = "도착 예정"
    const val TRANSFER = "환승"
    const val VIA_POINT = "경유지"
    const val SAVED_LOCATION = "저장한 위치"
    const val FINAL_POINT = "도착"
    const val INTRO_TITLE = "알림을 허용해 주세요"
    const val INTRO_BODY = "출발할 시각과 내릴 역을 알림으로 알려드려요. 정확한 시각에 울리려면 '알람 및 리마인더' 권한도 필요해요."
    const val CONTINUE = "계속"
    const val EXACT_ALARM_ASK = "정확한 시각에 알리려면 '알람 및 리마인더'를 허용해 주세요"
    const val LATER = "나중에"
    const val REPLACE_SESSION_ASK = "진행 중인 통학 알림을 끝내고 새로 시작할까요?"
    const val START_NEW = "새로 시작"
    const val CANCEL = "취소"
    const val OK = "확인"
    const val ATTRIBUTION = "이 앱은 서울 열린데이터광장의 '서울시 지하철 실시간 도착정보'·'서울시 역사마스터 정보'·" +
        "'서울시 지하철역 시간표'와 카카오 길찾기 API를 사용합니다."
    // 노선도 홈(재설계 스펙 4.1)
    const val LOCATE = "현재 위치"
    const val STATION_NOT_ON_MAP = "노선도에 없는 역이에요"
    const val LOCATION_UNAVAILABLE = "현재 위치를 알 수 없어요"
    const val ROUTINES = "루틴"
    const val NO_ROUTINES = "등록한 루틴이 없어요"
    const val NO_FAVORITE_ROUTES = "자주 가는 경로가 없어요"
    const val FIND_ROUTE = "경로 찾기"
    const val EXPAND = "펼치기"
    const val COLLAPSE = "접기"
    // 역 시트(재설계 스펙 4.2)
    const val NO_TRAIN_INFO = "열차 정보가 없어요"
    const val UNSUPPORTED_LINE = "실시간 정보를 제공하지 않는 노선이에요"
    const val BY_TIMETABLE = "시간표 기준"
    const val SHEET_NOW = "지금"
    const val SHEET_PLUS_1H = "+1시간"
    const val SHEET_PLUS_2H = "+2시간"
    const val SHEET_PICK_TIME = "시각 선택"
    const val CLOSE = "닫기"
    const val AS_FROM = "출발로"
    const val AS_TO = "도착으로"
    const val AS_VIA = "경유로"
    const val LINEMAP_SOURCE = "노선도: 위키미디어 공용 Seoul_subway_linemap_ko.svg (퍼블릭 도메인, IRTC1015, 2025-06-29 판)"
}
