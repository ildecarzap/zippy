import logging
from datetime import datetime, timedelta
from fastapi import APIRouter, Request, HTTPException, Depends
from sqlalchemy.orm import Session
from app.database import get_db
from app.models import DeviceLicense, PaymentTransaction
from app.services.cobrixpay import verify_cobrixpay_signature

logger = logging.getLogger("zippy.webhooks")
router = APIRouter(prefix="/webhooks", tags=["CobrixPay Webhooks"])

@router.post("/cobrixpay")
async def receive_cobrixpay_webhook(
    request: Request,
    db: Session = Depends(get_db)
):
    """
    Receptor oficial de Webhooks de CobrixPay.
    Se ejecuta cuando un comercio paga la suscripción de Zippy PRO.
    """
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Cuerpo de petición inválido (no es JSON)")

    signature_header = request.headers.get("X-Webhook-Signature", "")
    
    # 1. Validar firma de seguridad HMAC-SHA256
    is_valid = verify_cobrixpay_signature(body, signature_header)
    if not is_valid:
        logger.warning(f"⚠️ Intento de webhook con firma inválida desde IP {request.client.host if request.client else 'unknown'}")
        raise HTTPException(status_code=401, detail="Firma de seguridad HMAC-SHA256 inválida")

    event_type = body.get("event")
    data = body.get("data", {})
    logger.info(f"🔔 Webhook CobrixPay recibido: evento '{event_type}'")

    # 2. Manejar evento de pago completado
    if event_type == "payment.completed":
        payment_info = data.get("payment", {})
        debt_info = data.get("debt", {})
        
        debt_id = debt_info.get("debt_id") or payment_info.get("debt_id")
        amount = float(payment_info.get("amount", 2.99))
        currency = payment_info.get("currency", "USD")
        tx_ref = payment_info.get("transaction_id", "")
        method = payment_info.get("payment_method", "Pago Móvil")
        description = debt_info.get("description", "")
        debtor_phone = debt_info.get("debtor_phone", "")

        # Extraer device_id de la descripción (ej: 'Suscripción Zippy PRO - Dispositivo 8a9b...')
        device_id = None
        if "Dispositivo " in description:
            parts = description.split("Dispositivo ")
            if len(parts) > 1:
                device_id = parts[1].strip().split(" ")[0]

        # Si no vino en la descripción, intentar buscar por teléfono del deudor
        license_obj = None
        if device_id:
            license_obj = db.query(DeviceLicense).filter(DeviceLicense.device_id == device_id).first()
        elif debtor_phone:
            clean_p = "".join(filter(str.isdigit, debtor_phone))
            license_obj = db.query(DeviceLicense).filter(DeviceLicense.phone.contains(clean_p[-8:])).first()

        if license_obj:
            now = datetime.utcnow()
            # Si el plan PRO ya está vigente, sumar 30 días a la fecha de vencimiento actual
            if license_obj.plan_type == "PRO" and license_obj.pro_expires_at and license_obj.pro_expires_at > now:
                new_expiry = license_obj.pro_expires_at + timedelta(days=30)
            else:
                new_expiry = now + timedelta(days=30)

            license_obj.plan_type = "PRO"
            license_obj.pro_expires_at = new_expiry
            license_obj.is_active = True

            # Limpiar enlace y deuda pendiente al concretarse el pago
            license_obj.pending_debt_id = None
            license_obj.pending_payment_token = None
            license_obj.pending_payment_url = None
            license_obj.pending_link_expires_at = None

            # Guardar registro de la transacción
            tx = PaymentTransaction(
                device_id=license_obj.device_id,
                cobrix_debt_id=debt_id,
                amount_usd=amount,
                currency=currency,
                status="completed",
                transaction_reference=tx_ref,
                payment_method=method,
                paid_at=now
            )
            db.add(tx)
            db.commit()

            logger.info(f"⭐ [ZIPPY PRO ACTIVADO] Dispositivo {license_obj.device_id} ({license_obj.merchant_name}) renovado hasta {new_expiry.strftime('%Y-%m-%d')}!")
            return {
                "received": True,
                "event": event_type,
                "status": "pro_activated",
                "device_id": license_obj.device_id,
                "new_expiry": new_expiry.isoformat()
            }
        else:
            logger.warning(f"⚠️ Pago recibido pero no se encontró dispositivo asociado para deuda #{debt_id} ('{description}')")
            return {
                "received": True,
                "event": event_type,
                "status": "unmatched_device"
            }

    # 3. Manejar anulación o expiración de deudas desde CobrixPay
    elif event_type in ("debt.cancelled", "debt.expired"):
        debt_info = data.get("debt", {})
        debt_id = debt_info.get("debt_id")
        if debt_id:
            lic = db.query(DeviceLicense).filter(DeviceLicense.pending_debt_id == debt_id).first()
            if lic:
                lic.pending_debt_id = None
                lic.pending_payment_token = None
                lic.pending_payment_url = None
                lic.pending_link_expires_at = None
                db.commit()
                logger.info(f"🗑️ Deuda pendiente #{debt_id} limpiada en Zippy tras evento '{event_type}'")
                return {"received": True, "event": event_type, "status": "pending_cleared"}

    return {"received": True, "event": event_type}
