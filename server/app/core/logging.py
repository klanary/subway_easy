"""한 줄 JSON 로그 (스펙 6.8).

httpx·httpcore의 기본 INFO 로그는 서울시 키가 든 URL을 그대로 남기므로 WARNING으로 올린다.
외부 호출 URL은 app.clients.http.mask_url로 가린 뒤에만 로그에 넣는다.
"""

import json
import logging
import sys

from app.core.clock import kst_from_epoch

# LogRecord가 기본으로 갖는 속성. 이것 말고 extra로 넘긴 키만 로그에 붙인다.
_STANDARD_ATTRS = set(vars(logging.LogRecord("", 0, "", 0, "", None, None))) | {
    "message",
    "asctime",
    "taskName",
}


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "ts": kst_from_epoch(record.created).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        for key, value in vars(record).items():
            if key not in _STANDARD_ATTRS and not key.startswith("_"):
                payload[key] = value
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False, default=str)


def configure_logging() -> None:
    """루트 로거에 JSON 처리기를 하나만 단다. create_app이 여러 번 불러도 중복되지 않는다."""
    root = logging.getLogger()
    if not any(getattr(h, "_jigeumchulbal", False) for h in root.handlers):
        handler = logging.StreamHandler(sys.stderr)
        handler.setFormatter(JsonFormatter())
        handler._jigeumchulbal = True  # type: ignore[attr-defined]
        root.addHandler(handler)
    root.setLevel(logging.INFO)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
