from datetime import datetime, timedelta
import logging
import requests
from fastapi import APIRouter, Depends, Query, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session
from app.database import get_db
from app.models import DeviceLicense, PaymentTransaction
from app.config import settings
from app.services.cobrixpay import (
    create_or_get_cobrixpay_payment_url,
    check_cobrixpay_debt_status,
    generate_activation_code
)

logger = logging.getLogger("zippy.api")
router = APIRouter(prefix="/api/v1", tags=["Mobile App API"])

# Cache en memoria para tasa BCV
_bcv_cache = {"rate": 0.0, "updated_at": None}

class RegisterDeviceRequest(BaseModel):
    device_id: str
    merchant_name: str = "Comercio Zippy"
    phone: str = ""
    doc_id: str = ""
    email: str = ""
    app_version: str = "1.0.0"

@router.get("/license/check")
def check_license(
    device_id: str = Query(..., description="Identificador único del dispositivo Android"),
    db: Session = Depends(get_db)
):
    """
    Verifica de forma remota el estado de licencia del dispositivo.
    Consumido automáticamente por la app Zippy al iniciar o refrescar.
    Si hay una deuda pendiente en CobrixPay, consulta proactivamente si ya fue pagada.
    """
    license_obj = db.query(DeviceLicense).filter(DeviceLicense.device_id == device_id).first()
    
    from app.services.cobrixpay import get_plan_prices
    pricing = get_plan_prices(db)
    pricing_payload = {
        "currency": "USD",
        "price_1m": pricing["1m"],
        "price_3m": pricing["3m"],
        "price_12m": pricing["12m"],
        "plans": [
            {
                "id": "1m",
                "months": 1,
                "title": "1 Mes",
                "price_usd": pricing["1m"],
                "badge": None,
                "description": "Facturación mensual"
            },
            {
                "id": "3m",
                "months": 3,
                "title": "3 Meses",
                "price_usd": pricing["3m"],
                "badge": "Ahorra 11%",
                "description": f"${round(pricing['3m'] / 3, 2)} / mes"
            },
            {
                "id": "12m",
                "months": 12,
                "title": "1 Año (Anual)",
                "price_usd": pricing["12m"],
                "badge": "⭐ 2 Meses Gratis",
                "description": f"Equivale a ${round(pricing['12m'] / 12, 2)} / mes"
            }
        ]
    }

    if not license_obj:
        return {
            "device_id": device_id,
            "registered": False,
            "is_pro": False,
            "plan_type": "BASIC",
            "remaining_days": 0,
            "is_active": True,
            "activation_code": None,
            "message": "Dispositivo en Plan Básico gratuito",
            "pricing": pricing_payload
        }

    # Asegurar que tenga código de activación
    if not license_obj.activation_code:
        license_obj.activation_code = generate_activation_code(license_obj.device_id)

    # Verificación reactiva con CobrixPay: si hay deuda pendiente, consultar si ya fue pagada
    if license_obj.pending_debt_id:
        debt_status = check_cobrixpay_debt_status(license_obj.pending_debt_id)
        if debt_status and isinstance(debt_status, dict):
            status_val = str(debt_status.get("status", "")).lower()
            balance_due = float(debt_status.get("balance_due", 1.0))
            if status_val in ("paid", "completed") or balance_due <= 0:
                now = datetime.utcnow()
                debt_amount = float(debt_status.get("amount", pricing["1m"]))
                if debt_amount >= 20.0:
                    days_to_add = 365
                elif 6.0 <= debt_amount < 20.0:
                    days_to_add = 90
                else:
                    days_to_add = 30

                if license_obj.plan_type == "PRO" and license_obj.pro_expires_at and license_obj.pro_expires_at > now:
                    new_expiry = license_obj.pro_expires_at + timedelta(days=days_to_add)
                else:
                    new_expiry = now + timedelta(days=days_to_add)

                license_obj.plan_type = "PRO"
                license_obj.pro_expires_at = new_expiry
                license_obj.is_active = True

                tx = PaymentTransaction(
                    device_id=license_obj.device_id,
                    cobrix_debt_id=license_obj.pending_debt_id,
                    amount_usd=debt_amount,
                    currency=str(debt_status.get("currency_code", "USD")),
                    status="completed",
                    transaction_reference=f"COBRIX-{license_obj.pending_debt_id}",
                    payment_method="CobrixPay Checkout",
                    paid_at=now
                )
                db.add(tx)

                license_obj.pending_debt_id = None
                license_obj.pending_payment_token = None
                license_obj.pending_payment_url = None
                license_obj.pending_link_expires_at = None
                logger.info(f"🎉 [ZIPPY PRO AUTO-ACTIVADO] Deuda #{tx.cobrix_debt_id} ({days_to_add} días) pagada para dispositivo {license_obj.device_id}!")

    license_obj.last_sync_at = datetime.utcnow()
    db.commit()

    effective_plan = "PRO" if license_obj.is_pro_active and license_obj.plan_type == "PRO" else ("TRIAL" if license_obj.is_pro_active else "BASIC")

    return {
        "device_id": license_obj.device_id,
        "merchant_name": license_obj.merchant_name,
        "phone": license_obj.phone,
        "doc_id": license_obj.doc_id,
        "email": license_obj.email,
        "registered": True,
        "is_pro": license_obj.is_pro_active,
        "plan_type": effective_plan,
        "remaining_days": license_obj.remaining_days,
        "trial_ends_at": license_obj.trial_ends_at.isoformat() if license_obj.trial_ends_at else None,
        "pro_expires_at": license_obj.pro_expires_at.isoformat() if license_obj.pro_expires_at else None,
        "trial_used": bool(license_obj.trial_ends_at is not None),
        "activation_code": license_obj.activation_code,
        "is_active": license_obj.is_active,
        "server_time": datetime.utcnow().isoformat(),
        "pricing": pricing_payload
    }

@router.post("/license/register")
def register_device(
    payload: RegisterDeviceRequest,
    db: Session = Depends(get_db)
):
    """
    Registra o sincroniza un dispositivo instalado.
    """
    license_obj = db.query(DeviceLicense).filter(DeviceLicense.device_id == payload.device_id).first()
    
    if not license_obj:
        license_obj = DeviceLicense(
            device_id=payload.device_id,
            merchant_name=payload.merchant_name or "Comercio Zippy",
            phone=payload.phone or "",
            doc_id=payload.doc_id or "",
            email=payload.email or "",
            plan_type="BASIC",
            app_version=payload.app_version,
            activation_code=generate_activation_code(payload.device_id),
            last_sync_at=datetime.utcnow()
        )
        db.add(license_obj)
        db.commit()
        db.refresh(license_obj)
        logger.info(f"📱 Nuevo dispositivo registrado en Zippy Hub: {license_obj.device_id} ({license_obj.merchant_name})")
    else:
        if payload.merchant_name and payload.merchant_name != "Comercio Zippy":
            license_obj.merchant_name = payload.merchant_name
        if payload.phone:
            license_obj.phone = payload.phone
        if payload.doc_id:
            license_obj.doc_id = payload.doc_id
        if payload.email:
            license_obj.email = payload.email
        if not license_obj.activation_code:
            license_obj.activation_code = generate_activation_code(license_obj.device_id)
        license_obj.app_version = payload.app_version
        license_obj.last_sync_at = datetime.utcnow()
        db.commit()

    effective_plan = "PRO" if license_obj.is_pro_active and license_obj.plan_type == "PRO" else ("TRIAL" if license_obj.is_pro_active else "BASIC")

    return {
        "status": "success",
        "device_id": license_obj.device_id,
        "plan_type": effective_plan,
        "is_pro": license_obj.is_pro_active,
        "remaining_days": license_obj.remaining_days,
        "trial_ends_at": license_obj.trial_ends_at.isoformat() if license_obj.trial_ends_at else None,
        "pro_expires_at": license_obj.pro_expires_at.isoformat() if license_obj.pro_expires_at else None,
        "trial_used": bool(license_obj.trial_ends_at is not None),
        "activation_code": license_obj.activation_code
    }

class ActivateTrialRequest(BaseModel):
    device_id: str
    merchant_name: str = "Comercio Zippy"
    phone: str = ""
    doc_id: str = ""
    email: str = ""

@router.post("/license/activate-trial")
def activate_trial(
    payload: ActivateTrialRequest,
    db: Session = Depends(get_db)
):
    """
    Activa o sincroniza de forma remota la prueba gratuita de 7 días (Plan TRIAL).
    Consumido por la app Zippy al pulsar 'Probar 7 días gratis'.
    """
    from app.routers.admin import get_system_setting
    trial_days_str = get_system_setting(db, "trial_days", "7")
    try:
        trial_days = int(trial_days_str)
    except Exception:
        trial_days = 7

    now = datetime.utcnow()
    license_obj = db.query(DeviceLicense).filter(DeviceLicense.device_id == payload.device_id).first()

    if not license_obj:
        trial_ends = now + timedelta(days=trial_days)
        license_obj = DeviceLicense(
            device_id=payload.device_id,
            merchant_name=payload.merchant_name or "Comercio Zippy",
            phone=payload.phone or "",
            doc_id=payload.doc_id or "",
            email=payload.email or "",
            plan_type="TRIAL",
            trial_ends_at=trial_ends,
            is_active=True,
            app_version="1.2.0",
            activation_code=generate_activation_code(payload.device_id),
            last_sync_at=now
        )
        db.add(license_obj)
        db.commit()
        db.refresh(license_obj)
        logger.info(f"⏳ [TRIAL] Nuevo dispositivo registrado con prueba de {trial_days} días: {license_obj.device_id}")
    else:
        # Si ya cuenta con PRO activo de pago, mantener PRO
        if license_obj.plan_type == "PRO" and license_obj.pro_expires_at and license_obj.pro_expires_at > now:
            return {
                "status": "already_pro",
                "message": "El dispositivo ya cuenta con suscripción PRO activa",
                "is_pro": True,
                "plan_type": "PRO",
                "remaining_days": license_obj.remaining_days,
                "pro_expires_at": license_obj.pro_expires_at.isoformat()
            }

        # Si ya usó la prueba en el pasado y ya expiró (más de 1 hora de diferencia para evitar carreras)
        if license_obj.trial_ends_at and license_obj.trial_ends_at < (now - timedelta(hours=1)):
            return {
                "status": "trial_expired",
                "message": "La prueba gratuita ya fue utilizada previamente en este dispositivo",
                "is_pro": False,
                "plan_type": "BASIC",
                "remaining_days": 0
            }

        # Activar o refrescar período de prueba de 7 días
        trial_ends = now + timedelta(days=trial_days)
        license_obj.plan_type = "TRIAL"
        license_obj.trial_ends_at = trial_ends
        license_obj.is_active = True
        license_obj.last_sync_at = now
        if payload.merchant_name and payload.merchant_name != "Comercio Zippy":
            license_obj.merchant_name = payload.merchant_name
        if payload.phone:
            license_obj.phone = payload.phone
        if payload.doc_id:
            license_obj.doc_id = payload.doc_id
        if payload.email:
            license_obj.email = payload.email
        db.commit()
        db.refresh(license_obj)
        logger.info(f"⏳ [TRIAL] Prueba de {trial_days} días activada para {license_obj.device_id}")

    return {
        "status": "success",
        "device_id": license_obj.device_id,
        "is_pro": True,
        "plan_type": "TRIAL",
        "remaining_days": license_obj.remaining_days,
        "trial_ends_at": license_obj.trial_ends_at.isoformat() if license_obj.trial_ends_at else None,
        "message": f"Prueba de {trial_days} días activada con éxito"
    }

class RedeemCodeRequest(BaseModel):
    device_id: str
    code: str

@router.post("/license/redeem")
def redeem_license_code(
    payload: RedeemCodeRequest,
    db: Session = Depends(get_db)
):
    """
    Canjea un código de activación manual generado por el administrador o de soporte.
    Activa o extiende el plan PRO por 30 días.
    """
    device_id = payload.device_id.strip()
    code = payload.code.strip().upper()

    license_obj = db.query(DeviceLicense).filter(DeviceLicense.device_id == device_id).first()
    if not license_obj:
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado en el sistema Zippy")

    is_valid = False
    days_to_add = 30

    # 1. Código específico asignado al dispositivo en admin
    if license_obj.activation_code and code == license_obj.activation_code.strip().upper():
        is_valid = True
    # 2. Clave universal o promocional
    elif code in ("ZIPPYPRO2026", "ZIPPY-PRO-LIFETIME"):
        is_valid = True
        days_to_add = 365
    elif code in ("ZIPPY30", "ZIPPY-PRO-30"):
        is_valid = True
        days_to_add = 30
    elif code in ("ZIPPY90", "ZIPPY-PRO-90"):
        is_valid = True
        days_to_add = 90
    elif code in ("ZIPPY365", "ZIPPY-PRO-365"):
        is_valid = True
        days_to_add = 365
    elif code.startswith("ZIP-") and code.endswith(device_id[-4:].upper()):
        is_valid = True
        days_to_add = 30

    if not is_valid:
        raise HTTPException(status_code=400, detail="Código de activación inválido o no corresponde a este dispositivo")

    now = datetime.utcnow()
    if license_obj.plan_type == "PRO" and license_obj.pro_expires_at and license_obj.pro_expires_at > now:
        license_obj.pro_expires_at += timedelta(days=days_to_add)
    else:
        license_obj.pro_expires_at = now + timedelta(days=days_to_add)

    license_obj.plan_type = "PRO"
    license_obj.is_active = True
    # Generar un nuevo código para que el usado no sea reutilizado
    license_obj.activation_code = generate_activation_code(license_obj.device_id)
    db.commit()

    logger.info(f"🔑 [CANJE EXITOSO] Dispositivo {device_id} activó PRO por {days_to_add} días con código {code}")

    return {
        "status": "success",
        "message": f"¡Plan Zippy PRO activado exitosamente por {days_to_add} días!",
        "is_pro": True,
        "plan_type": "PRO",
        "remaining_days": license_obj.remaining_days,
        "pro_expires_at": license_obj.pro_expires_at.isoformat()
    }

class RequestPaymentLinkRequest(BaseModel):
    device_id: str
    months: int = 1

@router.post("/license/request-payment-link")
def request_payment_link(
    payload: RequestPaymentLinkRequest,
    db: Session = Depends(get_db)
):
    """
    Permite solicitar un enlace oficial de pago de CobrixPay para activar o renovar Zippy PRO.
    Anti-Spam: Si ya cuenta con un enlace activo sin vencer, retorna el existente en vez de crear deudas masivas.
    Expiración: Si el anterior venció, lo anula en CobrixPay antes de generar uno nuevo.
    """
    license_obj = db.query(DeviceLicense).filter(DeviceLicense.device_id == payload.device_id).first()
    if not license_obj:
        raise HTTPException(status_code=404, detail="Dispositivo no encontrado en el sistema Zippy Hub")

    if license_obj.is_pro_active and license_obj.plan_type == "PRO" and license_obj.remaining_days > 7:
        return {
            "status": "already_active",
            "message": f"Tu suscripción PRO está activa con {license_obj.remaining_days} días restantes.",
            "is_reused": False,
            "payment_url": None,
            "expires_at": None
        }

    try:
        token, payment_url, is_reused, expires_at, debt_id = create_or_get_cobrixpay_payment_url(
            device=license_obj,
            months=payload.months,
            force_new=False,
            db=db
        )
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Error conectando con CobrixPay: {str(e)}")

    return {
        "status": "success",
        "is_reused": is_reused,
        "token": token,
        "payment_url": payment_url,
        "expires_at": expires_at.isoformat() if expires_at else None,
        "debt_id": debt_id,
        "message": "Enlace de pago activo recuperado (vigente)" if is_reused else "Nuevo enlace de pago generado exitosamente"
    }

@router.get("/bcv")
def get_bcv_rate():
    """
    Endpoint centralizado para consultar la tasa oficial del Banco Central de Venezuela.
    Incluye caché de 30 minutos para evitar saturar fuentes externas.
    """
    now = datetime.utcnow()
    cached_rate = _bcv_cache.get("rate", 0.0)
    cached_time = _bcv_cache.get("updated_at")

    if cached_rate > 0 and cached_time and (now - cached_time) < timedelta(minutes=30):
        return {
            "source": "cache",
            "rate": cached_rate,
            "currency": "USD/VES",
            "updated_at": cached_time.isoformat()
        }

    # Fuentes de respaldo para tasa BCV
    endpoints = [
        ("https://ve.dolarapi.com/v1/dolares/oficial", lambda r: float(r.json().get("promedio", 0.0))),
        ("https://pydolarvenezuela-api.vercel.app/api/v1/dollar/page?page=bcv", lambda r: float(r.json().get("monitors", {}).get("usd", {}).get("price", 0.0)))
    ]

    for url, parser in endpoints:
        try:
            resp = requests.get(url, timeout=5)
            if resp.status_code == 200:
                rate = parser(resp)
                if rate > 0:
                    _bcv_cache["rate"] = rate
                    _bcv_cache["updated_at"] = now
                    return {
                        "source": "live",
                        "rate": rate,
                        "currency": "USD/VES",
                        "updated_at": now.isoformat()
                    }
        except Exception as e:
            logger.warning(f"Error consultando fuente BCV {url}: {e}")

    if cached_rate > 0:
        return {"source": "stale_cache", "rate": cached_rate, "currency": "USD/VES"}

    return {"source": "fallback", "rate": 200.0, "currency": "USD/VES"}
