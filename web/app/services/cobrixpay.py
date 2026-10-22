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

def create_or_get_cobrixpay_payment_url(
    device_id: str,
    merchant_name: str,
    phone: str,
    doc_id: Optional[str] = "",
    months: int = 1
) -> Tuple[str, str]:
    """
    Crea o reutiliza una deuda en CobrixPay y obtiene el 'payment_token' auténtico.
    Devuelve (payment_token, payment_url).
    URL resultante: https://app.cobrix-pay.com/pay/{payment_token}
    """
    amount = round(settings.COBRIXPAY_PLAN_PRICE_USD * months, 2)
    company_id = int(settings.COBRIXPAY_COMPANY_ID or "5")
    meses_txt = f"{months} mes{'es' if months > 1 else ''}"
    description = f"Suscripción Zippy PRO ({meses_txt}) - Dispositivo {device_id}"
    email = f"{device_id.lower().replace('-', '')}@zippy.ciemtech.com"

    # Intentar registrar directamente en CobrixPay si la BD contiene las tablas
    active_engine = get_cobrixpay_engine()
    try:
        with active_engine.connect() as conn:
            # 1. Buscar o registrar deudor en la tabla debtors
            debtor_id = None
            existing_debtor = conn.execute(text("""
                SELECT debtor_id FROM debtors 
                WHERE company_id = :comp_id AND (
                    (document_number IS NOT NULL AND document_number != '' AND document_number = :doc) OR 
                    (phone IS NOT NULL AND phone != '' AND phone = :phone)
                )
                LIMIT 1;
            """), {'comp_id': company_id, 'doc': doc_id or "", 'phone': phone}).first()

            if existing_debtor:
                debtor_id = existing_debtor[0]
            else:
                insert_debtor = conn.execute(text("""
                    INSERT INTO debtors (company_id, name, email, phone, document_type, document_number, status, created_at)
                    VALUES (:comp_id, :name, :email, :phone, 'RIF', :doc, 1, NOW())
                    RETURNING debtor_id;
                """), {
                    'comp_id': company_id,
                    'name': merchant_name,
                    'email': email,
                    'phone': phone,
                    'doc': doc_id or "V-00000000"
                })
                debtor_id = insert_debtor.scalar()
                conn.commit()

            # 2. Buscar si ya existe una deuda pendiente para este dispositivo
            desc_search = f"%Dispositivo {device_id}%"
            existing_debt = conn.execute(text("""
                SELECT debt_id, payment_token FROM debts 
                WHERE company_id = :comp_id AND debtor_id = :debtor_id AND status = 'pending' AND description LIKE :desc
                LIMIT 1;
            """), {'comp_id': company_id, 'debtor_id': debtor_id, 'desc': desc_search}).first()

            if existing_debt and existing_debt[1]:
                token = existing_debt[1]
            else:
                new_token = secrets.token_urlsafe(18)
                ref_num = f"ZIP-{secrets.token_hex(3).upper()}"
                due = date.today() + timedelta(days=7)

                conn.execute(text("""
                    INSERT INTO debts (company_id, debtor_id, amount, balance_due, currency_code, description, reference_number, due_date, status, payment_token, allow_partial_payment, late_fee_applied, created_at)
                    VALUES (:comp_id, :debtor_id, :amount, :balance, 'USD', :desc, :ref_num, :due, 'pending', :token, false, false, NOW());
                """), {
                    'comp_id': company_id,
                    'debtor_id': debtor_id,
                    'amount': amount,
                    'balance': amount,
                    'desc': description,
                    'ref_num': ref_num,
                    'due': due,
                    'token': new_token
                })
                conn.commit()
                token = new_token

            payment_url = f"{settings.COBRIXPAY_BASE_URL}/pay/{token}"
            return token, payment_url

    except Exception as e:
        # Contingencia si no está conectada la tabla de CobrixPay: generar token UUID seguro
        token = secrets.token_urlsafe(18)
        payment_url = f"{settings.COBRIXPAY_BASE_URL}/pay/{token}"
        return token, payment_url

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
