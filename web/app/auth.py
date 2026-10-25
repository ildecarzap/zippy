import time
import bcrypt
from typing import Optional
from fastapi import Request, HTTPException, status, Depends
from itsdangerous import URLSafeTimedSerializer, BadSignature, SignatureExpired
from sqlalchemy.orm import Session
from app.config import settings
from app.database import get_db
from app.models import AdminUser

serializer = URLSafeTimedSerializer(settings.ADMIN_SESSION_SECRET)

SESSION_COOKIE_NAME = "zeasy_admin_session"
SESSION_MAX_AGE = 86400 * 7 # 7 días

def hash_password(password: str) -> str:
    """Genera hash seguro bcrypt con truncado automático a 72 bytes."""
    pwd_bytes = password.encode("utf-8")[:72]
    salt = bcrypt.gensalt()
    return bcrypt.hashpw(pwd_bytes, salt).decode("utf-8")

def verify_password(plain_password: str, hashed_password: str) -> bool:
    """Verifica si la contraseña coincide con el hash almacenado."""
    try:
        pwd_bytes = plain_password.encode("utf-8")[:72]
        hash_bytes = hashed_password.encode("utf-8")
        return bcrypt.checkpw(pwd_bytes, hash_bytes)
    except Exception:
        return False

def create_session_token(user_id: int, username: str) -> str:
    payload = {
        "uid": user_id,
        "usr": username,
        "ts": time.time()
    }
    return serializer.dumps(payload)

def verify_session_token(token: str) -> Optional[dict]:
    try:
        data = serializer.loads(token, max_age=SESSION_MAX_AGE)
        return data
    except (BadSignature, SignatureExpired):
        return None

def get_current_admin(request: Request, db: Session = Depends(get_db)) -> AdminUser:
    token = request.cookies.get(SESSION_COOKIE_NAME) or request.cookies.get("zippy_admin_session")
    if not token:
        raise HTTPException(
            status_code=status.HTTP_307_TEMPORARY_REDIRECT,
            headers={"Location": f"/{settings.ADMIN_SECRET_PATH}/login"}
        )
    
    data = verify_session_token(token)
    if not data:
        raise HTTPException(
            status_code=status.HTTP_307_TEMPORARY_REDIRECT,
            headers={"Location": f"/{settings.ADMIN_SECRET_PATH}/login"}
        )

    user = db.query(AdminUser).filter(AdminUser.id == data.get("uid"), AdminUser.is_active == True).first()
    if not user:
        raise HTTPException(
            status_code=status.HTTP_307_TEMPORARY_REDIRECT,
            headers={"Location": f"/{settings.ADMIN_SECRET_PATH}/login"}
        )
    return user

def ensure_initial_admin(db: Session):
    """Crea el usuario administrador inicial si no existe en la base de datos."""
    admin = db.query(AdminUser).filter(AdminUser.username == settings.INITIAL_ADMIN_USER).first()
    if not admin:
        hashed = hash_password(settings.INITIAL_ADMIN_PASSWORD)
        new_admin = AdminUser(
            username=settings.INITIAL_ADMIN_USER,
            password_hash=hashed,
            email=settings.COMPANY_EMAIL,
            is_active=True
        )
        db.add(new_admin)
        db.commit()
