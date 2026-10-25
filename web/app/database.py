import logging
from sqlalchemy import create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from app.config import settings

logger = logging.getLogger("zeasy.database")

# Configurar URL de conexión con soporte explícito para psycopg2
db_url = settings.DATABASE_URL.strip()

# Asegurar protocolo postgresql+psycopg2 para SQLAlchemy
if db_url.startswith("postgres://"):
    db_url = db_url.replace("postgres://", "postgresql+psycopg2://", 1)
elif db_url.startswith("postgresql://") and not db_url.startswith("postgresql+"):
    db_url = db_url.replace("postgresql://", "postgresql+psycopg2://", 1)

try:
    engine = create_engine(
        db_url,
        pool_pre_ping=True,
        pool_recycle=300
    )
    with engine.connect() as conn:
        logger.info(f"✅ Conexión exitosa a la base de datos PostgreSQL: {engine.url.host}:{engine.url.port or 5432}/{engine.url.database}")
except Exception as e:
    logger.warning(f"⚠️ No se pudo conectar a PostgreSQL ({e}). Usando SQLite local de contingencia temporal...")
    engine = create_engine("sqlite:///./zeasy_fallback.db", connect_args={"check_same_thread": False})

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
