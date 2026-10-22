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

def create_or_get_cobrixpay_payment_url(
    device_id: str,
    merchant_name: str,
    phone: str,
    doc_id: Optional[str] = "",
    months: int = 1,
    email: Optional[str] = ""
) -> Tuple[str, str]:
    """
    Llama a la API oficial de CobrixPay (POST /api/v1/debts) con token de autenticación
    para registrar la deuda real del comercio CIEM y obtener su payment_token auténtico.
    Devuelve (payment_token, payment_url).
    URL resultante: https://app.cobrix-pay.com/pay/{payment_token}
    """
    amount = round(settings.COBRIXPAY_PLAN_PRICE_USD * months, 2)
    meses_txt = f"{months} mes{'es' if months > 1 else ''}"
    description = f"Suscripción Zippy PRO ({meses_txt}) - Dispositivo {device_id}"

    clean_phone = phone.strip()
    if clean_phone.startswith("0"):
        clean_phone = f"+58{clean_phone[1:]}"
    elif not clean_phone.startswith("+") and len(clean_phone) == 10:
        clean_phone = f"+58{clean_phone}"
    elif clean_phone and not clean_phone.startswith("+"):
        clean_phone = f"+{clean_phone}"
    if not clean_phone:
        clean_phone = "+584140000000"

    target_email = email.strip() if (email and "@" in email) else f"{device_id.lower().replace('-', '')}@zippy.ciemtech.com"

    try:
        headers = get_cobrixpay_auth_headers()
        headers["Content-Type"] = "application/json"

        debt_api_url = f"{settings.COBRIXPAY_API_URL.rstrip('/')}/api/v1/debts"
        payload = {
            "amount": float(amount),
            "currency_code": "USD",
            "description": description,
            "due_date": (date.today() + timedelta(days=7)).isoformat(),
            "allow_partial_payment": False,
            "debtor_data": {
                "name": merchant_name.strip() or f"Comercio {device_id}",
                "email": target_email,
                "phone": clean_phone,
                "document_number": doc_id.strip() if doc_id else "V-00000000",
                "document_type": "RIF"
            }
        }

        resp = requests.post(debt_api_url, json=payload, headers=headers, timeout=12)
        if resp.status_code in (200, 201):
            debt_data = resp.json()
            payment_token = debt_data.get("payment_token")
            if payment_token:
                payment_url = f"{settings.COBRIXPAY_BASE_URL.rstrip('/')}/pay/{payment_token}"
                logger.info(f"✅ Deuda creada exitosamente en CobrixPay API para {device_id}: token {payment_token}")
                return payment_token, payment_url
            else:
                logger.error(f"⚠️ CobrixPay API retornó 200 pero sin payment_token: {debt_data}")
                raise Exception(f"CobrixPay API retornó respuesta sin payment_token: {debt_data}")
        else:
            logger.error(f"⚠️ CobrixPay API respondió con error ({resp.status_code}): {resp.text}")
            raise Exception(f"CobrixPay API respondió con error ({resp.status_code}): {resp.text}")

    except Exception as api_err:
        logger.error(f"Error al crear deuda en CobrixPay API: {api_err}")
        raise api_err

def build_whatsapp_message_text(merchant_name: str, payment_url: str, months: int = 1) -> str:
    """
    Construye el texto plano del mensaje de WhatsApp para que el administrador pueda revisarlo y editarlo.
    """
    amount = round(settings.COBRIXPAY_PLAN_PRICE_USD * months, 2)
    meses_txt = f"{months} mes{'es' if months > 1 else ''}"

    return (
        f"¡Hola {merchant_name}! 👋\n\n"
        f"⚡ Aquí tienes tu enlace oficial de CobrixPay para activar o renovar tu suscripción de *Zippy PRO* por {meses_txt} (${amount:.2f} USD):\n\n"
        f"🔗 {payment_url}\n\n"
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
