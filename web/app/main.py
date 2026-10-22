import logging
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.database import engine, Base, SessionLocal
from app.auth import ensure_initial_admin
from app.routers import public, admin, api, webhooks

# Configurar logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("zippy.main")

# Inicializar tablas en la Base de Datos
try:
    Base.metadata.create_all(bind=engine)
    
    # Asegurar columnas de prevención de spam y enlaces activos
    from sqlalchemy import text
    with engine.connect() as conn:
        conn.execute(text("""
            ALTER TABLE zippy_device_licenses ADD COLUMN IF NOT EXISTS pending_debt_id INTEGER;
            ALTER TABLE zippy_device_licenses ADD COLUMN IF NOT EXISTS pending_payment_token VARCHAR(200);
            ALTER TABLE zippy_device_licenses ADD COLUMN IF NOT EXISTS pending_payment_url VARCHAR(500);
            ALTER TABLE zippy_device_licenses ADD COLUMN IF NOT EXISTS pending_link_expires_at TIMESTAMP WITH TIME ZONE;
        """))
        conn.commit()
    
    logger.info("✅ Tablas de Zippy creadas/verificadas en la base de datos.")
    
    # Crear usuario administrador inicial si no existe
    with SessionLocal() as db:
        ensure_initial_admin(db)
        logger.info(f"✅ Usuario administrador inicial verificado: '{settings.INITIAL_ADMIN_USER}'")
except Exception as e:
    logger.error(f"⚠️ Error al inicializar tablas o usuario administrador: {e}", exc_info=True)

# Crear aplicación FastAPI
app = FastAPI(
    title=settings.APP_NAME,
    description="Portal Web, Instructivo y Panel de Control de Licencias Zippy - CIEM Tech Solutions C.A",
    version="1.0.0",
    docs_url=None if not settings.DEBUG else "/docs",
    redoc_url=None
)

# Middleware de CORS para permitir peticiones desde la app móvil
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Servir archivos estáticos (CSS, JS, Imágenes)
app.mount("/static", StaticFiles(directory="app/static"), name="static")

# Registrar Routers
app.include_router(public.router)
app.include_router(api.router)
app.include_router(webhooks.router)
app.include_router(admin.router)

import asyncio
from app.services.cobrixpay import cleanup_expired_pending_debts

async def periodic_cobrixpay_cleanup():
    """Tarea en segundo plano que anula deudas de CobrixPay cuyos enlaces hayan expirado sin pago."""
    while True:
        try:
            await asyncio.sleep(1800)  # cada 30 minutos
            with SessionLocal() as db:
                cleanup_expired_pending_debts(db)
        except asyncio.CancelledError:
            break
        except Exception as e:
            logger.warning(f"Error en tarea periódica de limpieza de deudas CobrixPay: {e}")

@app.on_event("startup")
async def startup_event():
    logger.info(f"🚀 {settings.APP_NAME} iniciado con éxito.")
    logger.info(f"🌐 Homepage: http://localhost:8088/")
    logger.info(f"📖 Instructivo: http://localhost:8088/guia")
    logger.info(f"🛡️ Panel Privado: http://localhost:8088/{settings.ADMIN_SECRET_PATH}/login")
    
    # Iniciar tarea en segundo plano de limpieza de deudas CobrixPay expiradas
    asyncio.create_task(periodic_cobrixpay_cleanup())

if __name__ == "__main__":
    import os
    import uvicorn
    port = int(os.getenv("PORT", "8088"))
    uvicorn.run("app.main:app", host="0.0.0.0", port=port, reload=settings.DEBUG)
