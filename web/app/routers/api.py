from datetime import datetime, timedelta
import logging
import requests
from fastapi import APIRouter, Depends, Query, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session
from app.database import get_db
from app.models import DeviceLicense
from app.config import settings
from app.services.cobrixpay import create_or_get_cobrixpay_payment_url

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
    """
    license_obj = db.query(DeviceLicense).filter(DeviceLicense.device_id == device_id).first()
    
    if not license_obj:
        return {
            "device_id": device_id,
            "registered": False,
            "is_pro": False,
            "plan_type": "BASIC",
            "remaining_days": 0,
            "is_active": True,
            "message": "Dispositivo en Plan Básico gratuito"
        }

    license_obj.last_sync_at = datetime.utcnow()
    db.commit()

    return {
        "device_id": license_obj.device_id,
        "merchant_name": license_obj.merchant_name,
        "phone": license_obj.phone,
        "doc_id": license_obj.doc_id,
        "email": license_obj.email,
        "registered": True,
        "is_pro": license_obj.is_pro_active,
        "plan_type": license_obj.plan_type,
        "remaining_days": license_obj.remaining_days,
        "trial_ends_at": license_obj.trial_ends_at.isoformat() if license_obj.trial_ends_at else None,
        "pro_expires_at": license_obj.pro_expires_at.isoformat() if license_obj.pro_expires_at else None,
        "is_active": license_obj.is_active,
        "server_time": datetime.utcnow().isoformat()
    }

@router.post("/license/register")
def register_device(
    payload: RegisterDeviceRequest,
    db: Session = Depends(get_db)
):
    """
    Registra o sincroniza un dispositivo instalado.
    Si es nuevo, puede registrarse con 7 días de prueba PRO si el usuario lo solicita.
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
        license_obj.app_version = payload.app_version
        license_obj.last_sync_at = datetime.utcnow()
        db.commit()

    return {
        "status": "success",
        "device_id": license_obj.device_id,
        "plan_type": license_obj.plan_type,
        "is_pro": license_obj.is_pro_active,
        "remaining_days": license_obj.remaining_days
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
