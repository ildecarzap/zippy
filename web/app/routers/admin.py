import logging
import math
from datetime import datetime, timedelta
from typing import Optional
from fastapi import APIRouter, Request, Depends, Form, HTTPException, status
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy.orm import Session
from app.config import settings
from app.database import get_db
from app.models import DeviceLicense, AdminUser, PaymentTransaction, SystemSetting
from app.auth import (
    verify_password, hash_password, create_session_token, get_current_admin,
    SESSION_COOKIE_NAME, SESSION_MAX_AGE
)
from app.services.cobrixpay import (
    create_or_get_cobrixpay_payment_url,
    cancel_cobrixpay_debt,
    cleanup_expired_pending_debts,
    build_whatsapp_message_text,
    build_whatsapp_direct_url
)

logger = logging.getLogger("zeasy.admin")
router = APIRouter(prefix=f"/{settings.ADMIN_SECRET_PATH}", tags=["Private Admin Portal"])
templates = Jinja2Templates(directory="app/templates")

def get_system_setting(db: Session, key: str, default: str) -> str:
    setting = db.query(SystemSetting).filter(SystemSetting.key == key).first()
    return setting.value if setting else default

def set_system_setting(db: Session, key: str, value: str, description: Optional[str] = None):
    setting = db.query(SystemSetting).filter(SystemSetting.key == key).first()
    if setting:
        setting.value = str(value)
        if description:
            setting.description = description
        setting.updated_at = datetime.utcnow()
    else:
        setting = SystemSetting(key=key, value=str(value), description=description)
        db.add(setting)
    db.commit()

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
    page: int = 1,
    page_size: int = 15,
    q: Optional[str] = None,
    filter_plan: Optional[str] = None,
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Panel principal de gestión de licencias Zeasy con paginación optimizada."""
    try:
        # Barrer automáticamente deudas expiradas para anularlas en CobrixPay
        cleanup_expired_pending_debts(db)
    except Exception as e:
        logger.warning(f"Error en cleanup_expired_pending_debts: {e}")

    now = datetime.utcnow()
    total_devices = db.query(DeviceLicense).count()
    pro_devices = db.query(DeviceLicense).filter(
        DeviceLicense.plan_type == "PRO",
        DeviceLicense.is_active == True,
        DeviceLicense.pro_expires_at > now
    ).count()
    trial_devices = db.query(DeviceLicense).filter(
        DeviceLicense.plan_type == "TRIAL",
        DeviceLicense.is_active == True,
        DeviceLicense.trial_ends_at > now
    ).count()
    basic_devices = max(0, total_devices - pro_devices - trial_devices)

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

    total_filtered = query.count()
    total_pages = max(1, math.ceil(total_filtered / page_size))
    page = max(1, min(page, total_pages))
    offset = (page - 1) * page_size

    devices = query.order_by(DeviceLicense.created_at.desc()).offset(offset).limit(page_size).all()

    # Asegurar códigos de activación para los dispositivos de la página
    from app.services.cobrixpay import generate_activation_code
    dirty = False
    for d in devices:
        if not getattr(d, 'activation_code', None):
            d.activation_code = generate_activation_code(d.device_id)
            dirty = True
    if dirty:
        db.commit()

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
            "page": page,
            "page_size": page_size,
            "total_pages": total_pages,
            "total_filtered": total_filtered,
            "start_item": offset + 1 if total_filtered > 0 else 0,
            "end_item": min(offset + page_size, total_filtered),
            "has_prev": page > 1,
            "has_next": page < total_pages,
            "prev_page": page - 1,
            "next_page": page + 1,
            "secret_path": settings.ADMIN_SECRET_PATH
        }
    )

@router.post("/licenses/{license_id}/activate-pro")
async def activate_pro_license(
    license_id: int,
    days: int = Form(30),
    months: Optional[int] = Form(None),
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Extiende manualmente el plan PRO de un comercio en 1 clic (1m=30d, 3m=90d, 12m=365d)."""
    device = db.query(DeviceLicense).filter(DeviceLicense.id == license_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado")

    if months:
        days = 365 if months == 12 else (90 if months == 3 else months * 30)

    now = datetime.utcnow()
    if device.plan_type == "PRO" and device.pro_expires_at and device.pro_expires_at > now:
        device.pro_expires_at += timedelta(days=days)
    else:
        device.pro_expires_at = now + timedelta(days=days)

    device.plan_type = "PRO"
    device.is_active = True
    db.commit()

    logger.info(f"⭐ [ADMIN] Plan PRO extendido por {days} días para #{device.id} ({device.merchant_name}) por {current_admin.username}")
    return RedirectResponse(
        url=f"/{settings.ADMIN_SECRET_PATH}/dashboard?success=pro_activated",
        status_code=status.HTTP_303_SEE_OTHER
    )

@router.post("/licenses/{license_id}/activate-trial")
async def activate_trial_license(
    license_id: int,
    days: int = Form(7),
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Activa o reactiva manualmente el período de prueba (Trial) de un comercio desde el panel."""
    device = db.query(DeviceLicense).filter(DeviceLicense.id == license_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado")

    now = datetime.utcnow()
    device.trial_ends_at = now + timedelta(days=days)
    device.plan_type = "TRIAL"
    device.is_active = True
    db.commit()

    logger.info(f"⏳ [ADMIN] Prueba (Trial) de {days} días activada para #{device.id} ({device.merchant_name}) por {current_admin.username}")
    return RedirectResponse(
        url=f"/{settings.ADMIN_SECRET_PATH}/dashboard?success=trial_activated",
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
    device.pending_debt_id = None
    device.pending_payment_token = None
    device.pending_payment_url = None
    device.pending_link_expires_at = None
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
    force_new: bool = False,
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Devuelve los datos de cobro con token auténtico de CobrixPay y el texto del mensaje para el modal."""
    device = db.query(DeviceLicense).filter(DeviceLicense.id == license_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado")

    try:
        token, payment_url, is_reused, expires_at, debt_id = create_or_get_cobrixpay_payment_url(
            device=device,
            months=months,
            force_new=force_new,
            db=db
        )
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

    message_text = build_whatsapp_message_text(
        merchant_name=device.merchant_name,
        payment_url=payment_url,
        months=months,
        expire_hours=settings.COBRIXPAY_LINK_EXPIRE_HOURS,
        db=db
    )
    wa_url = build_whatsapp_direct_url(device.phone, message_text)

    return JSONResponse({
        "device_id": device.device_id,
        "merchant_name": device.merchant_name,
        "phone": device.phone,
        "doc_id": device.doc_id,
        "email": device.email or "",
        "token": token,
        "payment_url": payment_url,
        "is_reused": is_reused,
        "expires_at": expires_at.isoformat() if expires_at else None,
        "debt_id": debt_id,
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

    try:
        token, payment_url, is_reused, expires_at, debt_id = create_or_get_cobrixpay_payment_url(
            device=device,
            months=months,
            force_new=False,
            db=db
        )
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

    message_text = build_whatsapp_message_text(
        merchant_name=device.merchant_name,
        payment_url=payment_url,
        months=months,
        expire_hours=settings.COBRIXPAY_LINK_EXPIRE_HOURS,
        db=db
    )
    wa_url = build_whatsapp_direct_url(device.phone, message_text)
    
    return RedirectResponse(url=wa_url, status_code=status.HTTP_303_SEE_OTHER)

@router.post("/licenses/{license_id}/cancel-pending-link")
async def cancel_pending_link_endpoint(
    license_id: int,
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Anula manualmente la deuda y enlace activo de CobrixPay para este dispositivo."""
    device = db.query(DeviceLicense).filter(DeviceLicense.id == license_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado")

    if device.pending_debt_id:
        cancel_cobrixpay_debt(device.pending_debt_id, reason="Anulado manualmente por administrador de Zeasy")

    device.pending_debt_id = None
    device.pending_payment_token = None
    device.pending_payment_url = None
    device.pending_link_expires_at = None
    db.commit()

    logger.info(f"🚫 [ADMIN] Enlace pendiente de #{device.id} ({device.merchant_name}) anulado por {current_admin.username}")
    return JSONResponse({"success": True, "message": "Enlace de pago y deuda anulados en CobrixPay exitosamente"})


@router.get("/licenses/{license_id}/details")
async def get_license_details(
    license_id: int,
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Devuelve el detalle exhaustivo del comercio, licencias y transacciones."""
    device = db.query(DeviceLicense).filter(DeviceLicense.id == license_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado")

    transactions = db.query(PaymentTransaction).filter(
        PaymentTransaction.device_id == device.device_id
    ).order_by(PaymentTransaction.created_at.desc()).limit(10).all()

    tx_list = [
        {
            "id": t.id,
            "amount_usd": t.amount_usd,
            "status": t.status,
            "reference": t.transaction_reference or "N/A",
            "payment_method": t.payment_method or "Desconocido",
            "paid_at": t.paid_at.strftime("%d/%m/%Y %H:%M") if t.paid_at else None,
            "created_at": t.created_at.strftime("%d/%m/%Y %H:%M") if t.created_at else None
        }
        for t in transactions
    ]

    return JSONResponse({
        "id": device.id,
        "device_id": device.device_id,
        "merchant_name": device.merchant_name,
        "phone": device.phone,
        "doc_id": device.doc_id or "",
        "email": device.email or "",
        "plan_type": device.plan_type,
        "is_pro_active": device.is_pro_active,
        "is_active": device.is_active,
        "remaining_days": device.remaining_days,
        "app_version": device.app_version or "1.0.0",
        "activation_code": getattr(device, "activation_code", "") or "",
        "created_at": device.created_at.strftime("%d/%m/%Y %H:%M") if device.created_at else "",
        "last_sync_at": device.last_sync_at.strftime("%d/%m/%Y %H:%M") if device.last_sync_at else "Nunca",
        "pro_expires_at": device.pro_expires_at.strftime("%d/%m/%Y") if device.pro_expires_at else None,
        "trial_ends_at": device.trial_ends_at.strftime("%d/%m/%Y") if device.trial_ends_at else None,
        "pending_payment_url": device.pending_payment_url,
        "pending_debt_id": device.pending_debt_id,
        "transactions": tx_list
    })


@router.post("/change-password")
async def change_admin_password(
    current_password: str = Form(...),
    new_password: str = Form(...),
    confirm_password: str = Form(...),
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Cambia la contraseña del administrador actual de forma segura."""
    if not verify_password(current_password, current_admin.password_hash):
        return JSONResponse({"success": False, "message": "La contraseña actual no es correcta"}, status_code=400)

    if len(new_password) < 6:
        return JSONResponse({"success": False, "message": "La nueva contraseña debe tener al menos 6 caracteres"}, status_code=400)

    if new_password != confirm_password:
        return JSONResponse({"success": False, "message": "Las contraseñas nuevas no coinciden"}, status_code=400)

    current_admin.password_hash = hash_password(new_password)
    db.commit()

    logger.info(f"🔑 [ADMIN] El usuario '{current_admin.username}' cambió su contraseña exitosamente.")
    return JSONResponse({"success": True, "message": "Contraseña actualizada exitosamente"})


@router.get("/settings", response_class=HTMLResponse)
async def admin_settings_page(
    request: Request,
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Vista de Ajustes de Perfil, Contraseña, Precios y Parámetros del Sistema."""
    pro_price = get_system_setting(db, "pro_plan_price_usd", str(settings.COBRIXPAY_PLAN_PRICE_USD))
    pro_price_3m = get_system_setting(db, "pro_price_3m", "7.99")
    pro_price_12m = get_system_setting(db, "pro_price_12m", "29.99")
    trial_days = get_system_setting(db, "trial_days", "7")
    expire_hours = get_system_setting(db, "cobrix_link_expire_hours", str(settings.COBRIXPAY_LINK_EXPIRE_HOURS))
    company_name = get_system_setting(db, "company_name", settings.COMPANY_NAME)
    company_rif = get_system_setting(db, "company_rif", settings.COMPANY_RIF)
    company_phone = get_system_setting(db, "company_phone", settings.COMPANY_PHONE)
    company_email = get_system_setting(db, "company_email", settings.COMPANY_EMAIL)

    return templates.TemplateResponse(
        request=request,
        name="admin/settings.html",
        context={
            "settings": settings,
            "admin": current_admin,
            "secret_path": settings.ADMIN_SECRET_PATH,
            "pro_price": pro_price,
            "pro_price_3m": pro_price_3m,
            "pro_price_12m": pro_price_12m,
            "trial_days": trial_days,
            "expire_hours": expire_hours,
            "company_name": company_name,
            "company_rif": company_rif,
            "company_phone": company_phone,
            "company_email": company_email,
            "success": request.query_params.get("success"),
            "error": request.query_params.get("error")
        }
    )


@router.post("/settings/system")
async def update_system_settings(
    pro_price: float = Form(...),
    pro_price_3m: float = Form(7.99),
    pro_price_12m: float = Form(29.99),
    trial_days: int = Form(...),
    expire_hours: int = Form(...),
    company_name: str = Form(...),
    company_rif: str = Form(...),
    company_phone: str = Form(...),
    company_email: str = Form(...),
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Guarda los parámetros de precios, paquetes de suscripción, días de prueba y datos corporativos."""
    set_system_setting(db, "pro_plan_price_usd", str(pro_price), "Precio mensual del Plan PRO en USD")
    set_system_setting(db, "pro_price_3m", str(pro_price_3m), "Precio paquete 3 meses en USD")
    set_system_setting(db, "pro_price_12m", str(pro_price_12m), "Precio paquete 12 meses en USD")
    set_system_setting(db, "trial_days", str(trial_days), "Días de prueba gratuita")
    set_system_setting(db, "cobrix_link_expire_hours", str(expire_hours), "Horas de expiración para enlaces CobrixPay")
    set_system_setting(db, "company_name", company_name.strip(), "Razón social")
    set_system_setting(db, "company_rif", company_rif.strip(), "RIF fiscal")
    set_system_setting(db, "company_phone", company_phone.strip(), "Teléfono oficial de contacto")
    set_system_setting(db, "company_email", company_email.strip(), "Correo oficial de soporte")

    logger.info(f"⚙️ [ADMIN] Parámetros del sistema y paquetes de precios actualizados por {current_admin.username}")
    return RedirectResponse(
        url=f"/{settings.ADMIN_SECRET_PATH}/settings?success=system_saved",
        status_code=status.HTTP_303_SEE_OTHER
    )


@router.post("/settings/profile")
async def update_admin_profile(
    username: str = Form(...),
    email: str = Form(...),
    current_admin: AdminUser = Depends(get_current_admin),
    db: Session = Depends(get_db)
):
    """Actualiza datos del perfil del administrador."""
    username = username.strip()
    if not username:
        return RedirectResponse(
            url=f"/{settings.ADMIN_SECRET_PATH}/settings?error=username_required",
            status_code=status.HTTP_303_SEE_OTHER
        )

    existing = db.query(AdminUser).filter(AdminUser.username == username, AdminUser.id != current_admin.id).first()
    if existing:
        return RedirectResponse(
            url=f"/{settings.ADMIN_SECRET_PATH}/settings?error=username_taken",
            status_code=status.HTTP_303_SEE_OTHER
        )

    current_admin.username = username
    current_admin.email = email.strip()
    db.commit()

    logger.info(f"👤 [ADMIN] Perfil actualizado para ID {current_admin.id}: {username}")
    return RedirectResponse(
        url=f"/{settings.ADMIN_SECRET_PATH}/settings?success=profile_saved",
        status_code=status.HTTP_303_SEE_OTHER
    )
