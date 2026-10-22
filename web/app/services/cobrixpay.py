import json
import hmac
import hashlib
import urllib.parse
import secrets
from datetime import date, timedelta
from typing import Dict, Any, Tuple, Optional
from sqlalchemy import text, create_engine
from app.config import settings
from app.database import engine

_cobrixpay_engine = None

def get_cobrixpay_engine():
    """
    Retorna el engine de base de datos de CobrixPay.
    Si COBRIXPAY_DATABASE_URL está configurado, usa esa conexión dedicada.
    De lo contrario, usa el engine principal de Zippy.
    """
    global _cobrixpay_engine
    if settings.COBRIXPAY_DATABASE_URL:
        if _cobrixpay_engine is None:
            _cobrixpay_engine = create_engine(
                settings.COBRIXPAY_DATABASE_URL,
                pool_pre_ping=True
            )
        return _cobrixpay_engine
    return engine

def canonical_python_json(value: Any) -> str:
    """Serialización canónica compatible al 100% con la firma de CobrixPay."""
    return json.dumps(value, sort_keys=True, separators=(',', ':'))

def verify_cobrixpay_signature(payload: Dict[str, Any], signature_header: str) -> bool:
    """Verifica la firma HMAC-SHA256 del webhook recibido de CobrixPay."""
    if not settings.COBRIXPAY_WEBHOOK_SECRET:
        return False
    
    payload_sin_firma = {k: v for k, v in payload.items() if k != "signature"}
    canonical = json.dumps(payload_sin_firma, sort_keys=True)
    
    firma_calculada = hmac.new(
        settings.COBRIXPAY_WEBHOOK_SECRET.encode("utf-8"),
        canonical.encode("utf-8"),
        hashlib.sha256
    ).hexdigest()

    return hmac.compare_digest(firma_calculada, signature_header)

import logging
import requests
from datetime import datetime

logger = logging.getLogger("zippy.cobrixpay")

_cached_token = None
_token_expires_at = None

def get_cobrixpay_auth_headers() -> Dict[str, str]:
    """
    Retorna los encabezados de autenticación para consumir la API de CobrixPay.
    Soporta:
    1. API Key directa (X-API-Key: cb_live_...)
    2. Token estático Bearer (Authorization: Bearer ...)
    3. Login OAuth2 dinámico (/api/v1/token) usando COBRIXPAY_API_USER y COBRIXPAY_API_PASSWORD
    """
    global _cached_token, _token_expires_at

    # 1. API Key
    if settings.COBRIXPAY_API_KEY:
        return {"X-API-Key": settings.COBRIXPAY_API_KEY}

    # 2. Token Bearer estático
    if settings.COBRIXPAY_API_TOKEN:
        return {"Authorization": f"Bearer {settings.COBRIXPAY_API_TOKEN}"}

    # 3. Login por usuario y contraseña
    if settings.COBRIXPAY_API_USER and settings.COBRIXPAY_API_PASSWORD:
        now = datetime.utcnow()
        if _cached_token and _token_expires_at and now < _token_expires_at:
            return {"Authorization": f"Bearer {_cached_token}"}

        login_url = f"{settings.COBRIXPAY_API_URL.rstrip('/')}/api/v1/token"
        try:
            resp = requests.post(
                login_url,
                data={
                    "username": settings.COBRIXPAY_API_USER,
                    "password": settings.COBRIXPAY_API_PASSWORD
                },
                headers={"Content-Type": "application/x-www-form-urlencoded"},
                timeout=10
            )
            if resp.status_code == 200:
                data = resp.json()
                token = data.get("access_token")
                if token:
                    _cached_token = token
                    _token_expires_at = now + timedelta(minutes=90)
                    return {"Authorization": f"Bearer {token}"}
                else:
                    raise Exception(f"Respuesta de CobrixPay sin access_token: {data}")
            else:
                raise Exception(f"Credenciales de CobrixPay inválidas ({resp.status_code}): {resp.text}")
        except Exception as e:
            logger.error(f"Error autenticando con CobrixPay API: {e}")
            raise e

    raise Exception(
        "Credenciales de CobrixPay API no configuradas. "
        "Agrega COBRIXPAY_API_USER y COBRIXPAY_API_PASSWORD (o COBRIXPAY_API_KEY / COBRIXPAY_API_TOKEN) en las variables de entorno."
    )

def cancel_cobrixpay_debt(debt_id: int, reason: str = "Tiempo de pago expirado") -> bool:
    """
    Llama a la API oficial de CobrixPay (POST /api/v1/debts/{debt_id}/cancel)
    para anular la deuda en CobrixPay cuando el cliente no pagó a tiempo o se solicita un nuevo enlace.
    Evita la proliferación de deudas fantasmas y protege contra ataques masivos.
    """
    if not debt_id:
        return False
    try:
        headers = get_cobrixpay_auth_headers()
        headers["Content-Type"] = "application/json"
        url = f"{settings.COBRIXPAY_API_URL.rstrip('/')}/api/v1/debts/{debt_id}/cancel"
        payload = {"reason": reason}
        resp = requests.post(url, json=payload, headers=headers, timeout=12)
        if resp.status_code in (200, 204):
            logger.info(f"✅ Deuda #{debt_id} anulada exitosamente en CobrixPay ({reason}).")
            return True
        else:
            logger.warning(f"⚠️ CobrixPay cancel retornó {resp.status_code}: {resp.text}")
            return False
    except Exception as e:
        logger.error(f"❌ Error al anular deuda #{debt_id} en CobrixPay: {e}")
        return False

def cleanup_expired_pending_debts(db: Any) -> int:
    """
    Barre las licencias que tengan una deuda pendiente vencida (pending_link_expires_at <= utcnow)
    y la anula automáticamente en CobrixPay, liberando la cuenta para futuras solicitudes.
    """
    from app.models import DeviceLicense
    now = datetime.utcnow()
    expired_licenses = db.query(DeviceLicense).filter(
        DeviceLicense.pending_debt_id.isnot(None),
        DeviceLicense.pending_link_expires_at.isnot(None),
        DeviceLicense.pending_link_expires_at <= now
    ).all()

    cancelled_count = 0
    for lic in expired_licenses:
        if lic.pending_debt_id:
            logger.info(f"⌛ Enlace expirado para dispositivo {lic.device_id}. Anulando deuda #{lic.pending_debt_id} en CobrixPay...")
            cancel_cobrixpay_debt(lic.pending_debt_id, reason="Enlace de pago Zippy expiró sin pago")
            lic.pending_debt_id = None
            lic.pending_payment_token = None
            lic.pending_payment_url = None
            lic.pending_link_expires_at = None
            cancelled_count += 1

    if cancelled_count > 0:
        db.commit()
        logger.info(f"🧹 Se anularon y limpiaron {cancelled_count} deudas expiradas en CobrixPay.")
    return cancelled_count

def create_or_get_cobrixpay_payment_url(
    device: Any,
    months: int = 1,
    force_new: bool = False,
    db: Optional[Any] = None
) -> Tuple[str, str, bool, Optional[datetime], Optional[int]]:
    """
    Gestiona la creación o reutilización de enlaces de pago oficiales de CobrixPay.
    
    Reglas Anti-Spam y Expiración:
    1. Si ya existe un enlace activo no vencido y not force_new:
       Reutiliza el enlace existente (is_reused = True) y NO crea otra deuda en CobrixPay.
    2. Si el enlace anterior venció (o force_new = True):
       Anula la deuda previa en CobrixPay (POST /debts/{id}/cancel) y crea una nueva.
    3. Al crear la nueva deuda en CobrixPay:
       Envía 'expires_at' con el tiempo límite (COBRIXPAY_LINK_EXPIRE_HOURS) para que
       CobrixPay también invalide el checkout si pasa el plazo.
    """
    now = datetime.utcnow()

    # 1. Verificar si ya tiene un enlace activo vigente (Anti-Spam)
    if (
        not force_new
        and getattr(device, "pending_payment_token", None)
        and getattr(device, "pending_payment_url", None)
        and getattr(device, "pending_link_expires_at", None)
        and device.pending_link_expires_at > now
    ):
        logger.info(f"🛡️ Reutilizando enlace de pago activo para dispositivo {device.device_id} (vigente hasta {device.pending_link_expires_at})")
        return (
            device.pending_payment_token,
            device.pending_payment_url,
            True,
            device.pending_link_expires_at,
            device.pending_debt_id
        )

    # 2. Si tenía un enlace anterior y venció (o se solicita forzar nuevo), anular en CobrixPay
    old_debt_id = getattr(device, "pending_debt_id", None)
    if old_debt_id:
        reason = "Reemplazo de enlace solicitado por el comercio" if force_new else "Tiempo de pago expirado sin concretar"
        cancel_cobrixpay_debt(old_debt_id, reason=reason)
        device.pending_debt_id = None
        device.pending_payment_token = None
        device.pending_payment_url = None
        device.pending_link_expires_at = None

    # 3. Preparar parámetros para la nueva deuda
    amount = round(settings.COBRIXPAY_PLAN_PRICE_USD * months, 2)
    meses_txt = f"{months} mes{'es' if months > 1 else ''}"
    description = f"Suscripción Zippy PRO ({meses_txt}) - Dispositivo {device.device_id}"

    clean_phone = (getattr(device, "phone", "") or "").strip()
    if clean_phone.startswith("0"):
        clean_phone = f"+58{clean_phone[1:]}"
    elif not clean_phone.startswith("+") and len(clean_phone) == 10:
        clean_phone = f"+58{clean_phone}"
    elif clean_phone and not clean_phone.startswith("+"):
        clean_phone = f"+{clean_phone}"
    if not clean_phone:
        clean_phone = "+584140000000"

    device_email = (getattr(device, "email", "") or "").strip()
    target_email = device_email if (device_email and "@" in device_email) else f"{device.device_id.lower().replace('-', '')}@zippy.ciemtech.com"

    # Fecha de expiración estricta
    expires_at = now + timedelta(hours=settings.COBRIXPAY_LINK_EXPIRE_HOURS)

    try:
        headers = get_cobrixpay_auth_headers()
        headers["Content-Type"] = "application/json"

        debt_api_url = f"{settings.COBRIXPAY_API_URL.rstrip('/')}/api/v1/debts"
        payload = {
            "amount": float(amount),
            "currency_code": "USD",
            "description": description,
            "due_date": (now.date() + timedelta(days=7)).isoformat(),
            "expires_at": expires_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "allow_partial_payment": False,
            "debtor_data": {
                "name": (getattr(device, "merchant_name", "") or "").strip() or f"Comercio {device.device_id}",
                "email": target_email,
                "phone": clean_phone,
                "document_number": (getattr(device, "doc_id", "") or "").strip() if getattr(device, "doc_id", None) else "V-00000000",
                "document_type": "RIF"
            }
        }

        resp = requests.post(debt_api_url, json=payload, headers=headers, timeout=12)
        if resp.status_code in (200, 201):
            debt_data = resp.json()
            payment_token = debt_data.get("payment_token")
            debt_id = debt_data.get("debt_id")
            if payment_token:
                payment_url = f"{settings.COBRIXPAY_BASE_URL.rstrip('/')}/pay/{payment_token}"
                
                # Actualizar estado de la licencia en BD
                device.pending_debt_id = debt_id
                device.pending_payment_token = payment_token
                device.pending_payment_url = payment_url
                device.pending_link_expires_at = expires_at
                if db:
                    db.commit()

                logger.info(f"✅ Nueva deuda #{debt_id} creada en CobrixPay para {device.device_id}: token {payment_token} (Expira: {expires_at})")
                return payment_token, payment_url, False, expires_at, debt_id
            else:
                logger.error(f"⚠️ CobrixPay API retornó 200 pero sin payment_token: {debt_data}")
                raise Exception(f"CobrixPay API retornó respuesta sin payment_token: {debt_data}")
        else:
            logger.error(f"⚠️ CobrixPay API respondió con error ({resp.status_code}): {resp.text}")
            raise Exception(f"CobrixPay API respondió con error ({resp.status_code}): {resp.text}")

    except Exception as api_err:
        logger.error(f"Error al crear deuda en CobrixPay API: {api_err}")
        raise api_err

def build_whatsapp_message_text(merchant_name: str, payment_url: str, months: int = 1, expire_hours: int = 24) -> str:
    """
    Construye el texto plano del mensaje de WhatsApp para que el administrador pueda revisarlo y editarlo.
    """
    amount = round(settings.COBRIXPAY_PLAN_PRICE_USD * months, 2)
    meses_txt = f"{months} mes{'es' if months > 1 else ''}"

    return (
        f"¡Hola {merchant_name}! 👋\n\n"
        f"⚡ Aquí tienes tu enlace oficial de CobrixPay para activar o renovar tu suscripción de *Zippy PRO* por {meses_txt} (${amount:.2f} USD):\n\n"
        f"🔗 {payment_url}\n\n"
        f"⏳ *Nota:* Este enlace es válido por {expire_hours} horas. Pasado este tiempo se anula automáticamente por seguridad.\n\n"
        f"💳 Puedes pagar en Bolívares por Pago Móvil a la tasa oficial del día o con los métodos disponibles.\n"
        f"✅ Al confirmar el pago, tu aplicación *Zippy* activará todas las funciones PRO automáticamente sin necesidad de canjear códigos.\n\n"
        f"— Soporte CIEM Tech Solutions C.A"
    )

def build_whatsapp_direct_url(phone: str, message: str) -> str:
    """Genera la URL https://wa.me/{phone}?text={encoded_message}."""
    clean_phone = "".join(filter(str.isdigit, phone))
    if clean_phone.startswith("0"):
        clean_phone = "58" + clean_phone[1:]
    elif not clean_phone.startswith("58") and len(clean_phone) == 10:
        clean_phone = "58" + clean_phone

    encoded_text = urllib.parse.quote(message)
    return f"https://wa.me/{clean_phone}?text={encoded_text}"
