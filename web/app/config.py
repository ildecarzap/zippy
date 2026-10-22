import os
from typing import Optional
from pydantic_settings import BaseSettings
from dotenv import load_dotenv

load_dotenv()

class Settings(BaseSettings):
    APP_NAME: str = "Zippy Web & Control Hub"
    ENVIRONMENT: str = os.getenv("ENVIRONMENT", "development")
    DEBUG: bool = os.getenv("DEBUG", "true").lower() == "true"

    # Base de Datos (PostgreSQL local por defecto; en producción: Supabase)
    DATABASE_URL: str = os.getenv(
        "DATABASE_URL",
        "postgresql://postgres:postgrespassword@localhost:5432/cobrixpay"
    )

    # Ruta privada y oculta para el panel de administración
    ADMIN_SECRET_PATH: str = os.getenv("ADMIN_SECRET_PATH", "portal-seguro-ciem")
    ADMIN_SESSION_SECRET: str = os.getenv(
        "ADMIN_SESSION_SECRET",
        "ciem-super-secret-key-zippy-2026-auth-protection-9921"
    )

    # Credenciales iniciales de administrador (modificables desde .env)
    INITIAL_ADMIN_USER: str = os.getenv("INITIAL_ADMIN_USER", "admin_ciem")
    INITIAL_ADMIN_PASSWORD: str = os.getenv("INITIAL_ADMIN_PASSWORD", "Zippy2026#CiemAdmin")

    # Integración con Auth0 (Opcional si se habilita)
    AUTH0_DOMAIN: str = os.getenv("AUTH0_DOMAIN", "")
    AUTH0_CLIENT_ID: str = os.getenv("AUTH0_CLIENT_ID", "")
    AUTH0_CLIENT_SECRET: str = os.getenv("AUTH0_CLIENT_SECRET", "")
    AUTH0_CALLBACK_URL: str = os.getenv("AUTH0_CALLBACK_URL", "http://localhost:8088/portal-seguro-ciem/auth0/callback")

    # Integración con CobrixPay (Webhooks y Enlaces de Pago)
    COBRIXPAY_BASE_URL: str = os.getenv("COBRIXPAY_BASE_URL", "https://app.cobrix-pay.com")
    COBRIXPAY_API_URL: str = os.getenv("COBRIXPAY_API_URL", "https://api.cobrix-pay.com")
    COBRIXPAY_WEBHOOK_SECRET: str = os.getenv("COBRIXPAY_WEBHOOK_SECRET", "zippy_ciem_webhook_secret_key_2026")
    COBRIXPAY_COMPANY_ID: str = os.getenv("COBRIXPAY_COMPANY_ID", "5")
    COBRIXPAY_PLAN_PRICE_USD: float = float(os.getenv("COBRIXPAY_PLAN_PRICE_USD", "2.99"))
    COBRIXPAY_DATABASE_URL: Optional[str] = os.getenv("COBRIXPAY_DATABASE_URL", None)

    # Datos Corporativos de CIEM
    COMPANY_NAME: str = "Ciem tech solutions C.A"
    COMPANY_RIF: str = "J-50849495-4"
    COMPANY_EMAIL: str = "ildemaro.carrasco@outlook.com"
    COMPANY_PHONE: str = "+58 4242603112"
    COMPANY_WHATSAPP: str = "584242603112"

settings = Settings()
