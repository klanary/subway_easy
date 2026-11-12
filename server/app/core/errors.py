"""스펙 6.3의 오류 응답: {"error": {"code", "reason"(있을 때만), "message"}}."""

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

# 앱이 그대로 보여주는 문구. reason별 문구가 없으면 (code, None)을 쓴다.
_MESSAGES: dict[tuple[str, str | None], str] = {
    ("INVALID_REQUEST", None): "요청 형식이 올바르지 않아요.",
    ("INVALID_REQUEST", "EQUAL_POINTS"): "출발지와 도착지가 같아요.",
    ("INVALID_REQUEST", "SAME_STATION"): "출발역과 도착역이 같아요.",
    ("NOT_FOUND", None): "역 정보를 찾을 수 없어요.",
    ("NO_ROUTE", None): "대중교통 경로를 찾지 못했어요.",
    ("NO_ROUTE", "NO_START_STOP"): "출발지 근처에서 탈 수 있는 대중교통이 없어요.",
    ("NO_ROUTE", "NO_END_STOP"): "도착지 근처에서 내릴 수 있는 대중교통이 없어요.",
    ("NO_ROUTE", "NO_RESULTS"): "대중교통 경로를 찾지 못했어요.",
    ("NO_ROUTE", "NO_SUBWAY"): "지하철로 갈 수 있는 경로가 없어요.",
    ("RATE_LIMITED", None): "요청이 너무 많아요. 잠시 후 다시 시도해 주세요.",
    ("UPSTREAM_ERROR", None): "정보를 가져오지 못했어요. 잠시 후 다시 시도해 주세요.",
    ("QUOTA_EXCEEDED", None): "오늘 조회할 수 있는 횟수를 모두 썼어요. 내일 다시 시도해 주세요.",
    ("UPSTREAM_TIMEOUT", None): "응답이 늦어지고 있어요. 잠시 후 다시 시도해 주세요.",
}


class AppError(Exception):
    status: int = 500
    code: str = "INTERNAL_ERROR"

    def __init__(self, reason: str | None = None, message: str | None = None):
        self.reason = reason
        self.message = message or _MESSAGES.get((self.code, reason)) or _MESSAGES.get(
            (self.code, None), "알 수 없는 오류가 발생했어요."
        )
        super().__init__(self.code if reason is None else f"{self.code}: {reason}")


class InvalidRequest(AppError):
    status, code = 400, "INVALID_REQUEST"


class NotFound(AppError):
    status, code = 404, "NOT_FOUND"


class NoRoute(AppError):
    status, code = 404, "NO_ROUTE"


class RateLimited(AppError):
    status, code = 429, "RATE_LIMITED"


class UpstreamFailure(AppError):
    """외부 API 실패의 공통 부모. 직접 쓰지 않고 아래 세 하위 클래스를 쓴다."""


class UpstreamError(UpstreamFailure):
    status, code = 502, "UPSTREAM_ERROR"


class QuotaExceeded(UpstreamFailure):
    status, code = 503, "QUOTA_EXCEEDED"


class UpstreamTimeout(UpstreamFailure):
    status, code = 504, "UPSTREAM_TIMEOUT"


def error_response(err: AppError) -> JSONResponse:
    body: dict[str, str] = {"code": err.code}
    if err.reason is not None:
        body["reason"] = err.reason
    body["message"] = err.message
    return JSONResponse(status_code=err.status, content={"error": body})


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def handle_app_error(request: Request, exc: AppError) -> JSONResponse:
        return error_response(exc)

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        return error_response(InvalidRequest(reason="PARAMS"))
