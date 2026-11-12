from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse

router = APIRouter()

WEB_DIR = Path(__file__).resolve().parent.parent / "web"


@router.get("/", include_in_schema=False)
async def home() -> FileResponse:
    """역을 검색해 다음 열차를 보는 웹 화면. 서울시 공공데이터 활용사례의 공개 주소로 쓴다."""
    return FileResponse(WEB_DIR / "index.html", media_type="text/html; charset=utf-8")


@router.get("/logo.png", include_in_schema=False)
async def logo() -> FileResponse:
    return FileResponse(WEB_DIR / "logo.png", media_type="image/png")
