import logging
from datetime import datetime, timedelta
from typing import Optional
from fastapi import APIRouter, Request, Depends, Form, HTTPException, status
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy.orm import Session
from app.config import settings
from app.database import get_db
from app.models import DeviceLicense, AdminUser, PaymentTransaction
from app.auth import (
    verify_password, create_session_token, get_current_admin,
    SESSION_COOKIE_NAME, SESSION_MAX_AGE
)
from app.services.cobrixpay import (
    create_or_get_cobrixpay_payment_url,
    build_whatsapp_message_text,
    build_whatsapp_direct_url
)

logger = logging.getLogger("zippy.admin")
router = APIRouter(prefix=f"/{settings.ADMIN_SECRET_PATH}", tags=["Private Admin Portal"])
templates = Jinja2Templates(directory="app/templates")

@router.get("/login", response_class=HTMLResponse)
async def admin_login_page(request: Request, error: Optional[str] = None):
    """Página de inicio de sesión seguro en ruta privada."""
    return templates.TemplateResponse(
        request=request,
        name="admin/login.html",
        context={
            "settings": settings,
            "error": error,
            "secret_path": settings.ADMIN_SECRET_PATH
        }
    )

@router.post("/login")
async def admin_login_submit(
    request: Request,
    username: str = Form(...),
    password: str = Form(...),
    db: Session = Depends(get_db)
):
    """Procesa el inicio de sesión y asigna cookie segura."""
    admin = db.query(AdminUser).filter(AdminUser.username == username, AdminUser.is_active == True).first()
    
    if not admin or not verify_password(password, admin.password_hash):
        logger.warning(f"❌ Intento de acceso fallido para usuario '{username}' desde {request.client.host if request.client else 'unknown'}")
        return templates.TemplateResponse(
            request=request,
            name="admin/login.html",
            context={
                "settings": settings,
                "error": "Credenciales inválidas o cuenta inactiva.",
                "secret_path": settings.ADMIN_SECRET_PATH
            },
            status_code=400
        )

    token = create_session_token(admin.id, admin.username)
    response = RedirectResponse(
        url=f"/{settings.ADMIN_SECRET_PATH}/dashboard",
        status_code=status.HTTP_303_SEE_OTHER
    )
    response.set_cookie(
        key=SESSION_COOKIE_NAME,
        value=token,
        max_age=SESSION_MAX_AGE,
        httponly=True,
        samesite="lax",
        secure=not settings.DEBUG
    )
    logger.info(f"✅ Administrador '{admin.username}' inició sesión exitosamente.")
    return response

@router.get("/logout")
async def admin_logout():
    """Cierra la sesión y destruye la cookie."""
    response = RedirectResponse(
        url=f"/{settings.ADMIN_SECRET_PATH}/login",
        status_code=status.HTTP_303_SEE_OTHER
    )
    response.delete_cookie(SESSION_COOKIE_NAME)
    return response

@router.get("/dashboard", response_class=HTMLResponse)
async def admin_dashboard(
    request: Request,
    q: Optional[str] = None,
    filter_plan: Optional[str] = None,
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Panel principal de gestión de licencias Zippy."""
    query = db.query(DeviceLicense)

    if q:
        search = f"%{q.strip()}%"
        query = query.filter(
            (DeviceLicense.merchant_name.ilike(search)) |
            (DeviceLicense.phone.ilike(search)) |
            (DeviceLicense.doc_id.ilike(search)) |
            (DeviceLicense.device_id.ilike(search))
        )

    if filter_plan and filter_plan in ["PRO", "TRIAL", "BASIC"]:
        query = query.filter(DeviceLicense.plan_type == filter_plan)

    devices = query.order_by(DeviceLicense.created_at.desc()).all()

    total_devices = db.query(DeviceLicense).count()
    pro_devices = sum(1 for d in devices if d.is_pro_active and d.plan_type == "PRO")
    trial_devices = sum(1 for d in devices if d.is_pro_active and d.plan_type == "TRIAL")
    basic_devices = total_devices - pro_devices - trial_devices

    return templates.TemplateResponse(
        request=request,
        name="admin/dashboard.html",
        context={
            "settings": settings,
            "admin": current_admin,
            "devices": devices,
            "total_devices": total_devices,
            "pro_devices": pro_devices,
            "trial_devices": trial_devices,
            "basic_devices": basic_devices,
            "q": q or "",
            "filter_plan": filter_plan or "ALL",
            "secret_path": settings.ADMIN_SECRET_PATH
        }
    )

@router.post("/licenses/{license_id}/activate-pro")
async def activate_pro_license(
    license_id: int,
    days: int = Form(30),
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Extiende manualmente el plan PRO de un comercio en 1 clic."""
    device = db.query(DeviceLicense).filter(DeviceLicense.id == license_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado")

    now = datetime.utcnow()
    if device.plan_type == "PRO" and device.pro_expires_at and device.pro_expires_at > now:
        device.pro_expires_at += timedelta(days=days)
    else:
        device.pro_expires_at = now + timedelta(days=days)

    device.plan_type = "PRO"
    device.is_active = True
    db.commit()

    logger.info(f"⚡ [ADMIN] Licencia #{device.id} ({device.merchant_name}) extendida por {days} días por {current_admin.username}")
    return RedirectResponse(
        url=f"/{settings.ADMIN_SECRET_PATH}/dashboard?success=pro_activated",
        status_code=status.HTTP_303_SEE_OTHER
    )

@router.post("/licenses/{license_id}/downgrade-basic")
async def downgrade_device_to_basic(
    license_id: int,
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Degrada manualmente un dispositivo a Plan Básico si no desea pagar o venció su período."""
    device = db.query(DeviceLicense).filter(DeviceLicense.id == license_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado")

    device.plan_type = "BASIC"
    device.pro_expires_at = None
    device.trial_ends_at = None
    db.commit()

    logger.info(f"⬇️ [ADMIN] Licencia #{device.id} ({device.merchant_name}) degradada a Plan Básico por {current_admin.username}")
    return RedirectResponse(
        url=f"/{settings.ADMIN_SECRET_PATH}/dashboard?success=downgraded_to_basic",
        status_code=status.HTTP_303_SEE_OTHER
    )

@router.post("/licenses/{license_id}/toggle-status")
async def toggle_device_status(
    license_id: int,
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Activa o suspende una licencia."""
    device = db.query(DeviceLicense).filter(DeviceLicense.id == license_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado")

    device.is_active = not device.is_active
    db.commit()
    return RedirectResponse(
        url=f"/{settings.ADMIN_SECRET_PATH}/dashboard",
        status_code=status.HTTP_303_SEE_OTHER
    )

@router.get("/licenses/{license_id}/cobrix-data")
async def get_cobrix_payment_data(
    license_id: int,
    months: int = 1,
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Devuelve los datos de cobro con token auténtico de CobrixPay y el texto del mensaje para el modal."""
    device = db.query(DeviceLicense).filter(DeviceLicense.id == license_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado")

    token, payment_url = create_or_get_cobrixpay_payment_url(
        device_id=device.device_id,
        merchant_name=device.merchant_name,
        phone=device.phone,
        doc_id=device.doc_id,
        months=months
    )

    message_text = build_whatsapp_message_text(device.merchant_name, payment_url, months)
    wa_url = build_whatsapp_direct_url(device.phone, message_text)

    return JSONResponse({
        "device_id": device.device_id,
        "merchant_name": device.merchant_name,
        "phone": device.phone,
        "doc_id": device.doc_id,
        "token": token,
        "payment_url": payment_url,
        "message_text": message_text,
        "whatsapp_url": wa_url
    })

@router.post("/licenses/{license_id}/send-custom-whatsapp")
async def send_custom_whatsapp(
    license_id: int,
    phone: str = Form(...),
    custom_message: str = Form(...),
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Redirige al chat de WhatsApp con el mensaje personalizado y enlace de CobrixPay."""
    wa_url = build_whatsapp_direct_url(phone, custom_message)
    return RedirectResponse(url=wa_url, status_code=status.HTTP_303_SEE_OTHER)

@router.get("/licenses/{license_id}/whatsapp-link")
async def get_whatsapp_payment_redirect(
    license_id: int,
    months: int = 1,
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Genera el enlace con token auténtico de CobrixPay (/pay/{token}) y abre el chat de WhatsApp."""
    device = db.query(DeviceLicense).filter(DeviceLicense.id == license_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado")

    token, payment_url = create_or_get_cobrixpay_payment_url(
        device_id=device.device_id,
        merchant_name=device.merchant_name,
        phone=device.phone,
        doc_id=device.doc_id,
        months=months
    )

    message_text = build_whatsapp_message_text(device.merchant_name, payment_url, months)
    wa_url = build_whatsapp_direct_url(device.phone, message_text)
    
    return RedirectResponse(url=wa_url, status_code=status.HTTP_303_SEE_OTHER)
