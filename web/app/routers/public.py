from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates
from app.config import settings

router = APIRouter()
templates = Jinja2Templates(directory="app/templates")

@router.get("/", response_class=HTMLResponse)
async def home_page(request: Request):
    """Página principal (Landing Page) de Zeasy."""
    return templates.TemplateResponse(
        request=request,
        name="index.html",
        context={
            "settings": settings,
            "page_title": "Zeasy • Tu Caja Registradora Inteligente y Punto de Cobro Móvil"
        }
    )

@router.get("/guia", response_class=HTMLResponse)
async def manual_page(request: Request):
    """Instructivo oficial interactivo para usuarios que ingresan desde la app o la web."""
    return templates.TemplateResponse(
        request=request,
        name="guia.html",
        context={
            "settings": settings,
            "page_title": "Instructivo y Manual de Uso • Zeasy"
        }
    )

@router.get("/planes", response_class=HTMLResponse)
async def plans_page(request: Request):
    """Comparativa de Plan Básico vs Plan PRO."""
    return templates.TemplateResponse(
        request=request,
        name="planes.html",
        context={
            "settings": settings,
            "page_title": "Planes y Precios • Zeasy"
        }
    )

@router.get("/legal", response_class=HTMLResponse)
@router.get("/seguridad", response_class=HTMLResponse)
async def legal_page(request: Request):
    """Página de Seguridad, Privacidad y Marco Legal de Zeasy."""
    return templates.TemplateResponse(
        request=request,
        name="legal.html",
        context={
            "settings": settings,
            "page_title": "Seguridad, Privacidad y Marco Legal • Zeasy"
        }
    )

