from datetime import datetime
from sqlalchemy import Column, Integer, String, Boolean, DateTime, Float, Text
from app.database import Base

class DeviceLicense(Base):
    __tablename__ = "zippy_device_licenses"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String(100), unique=True, index=True, nullable=False)
    merchant_name = Column(String(200), default="Comercio Zeasy", nullable=False)
    phone = Column(String(50), default="", nullable=False)
    doc_id = Column(String(50), nullable=True) # Cédula de identidad o RIF
    email = Column(String(150), nullable=True)
    plan_type = Column(String(20), default="BASIC", nullable=False) # BASIC, TRIAL, PRO
    trial_ends_at = Column(DateTime, nullable=True)
    pro_expires_at = Column(DateTime, nullable=True)
    is_active = Column(Boolean, default=True, nullable=False)
    app_version = Column(String(20), default="1.0.0", nullable=True)
    last_sync_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)
    notes = Column(Text, nullable=True)

    # Control de enlaces activos y prevención de spam (CobrixPay)
    pending_debt_id = Column(Integer, nullable=True)
    pending_payment_token = Column(String(200), nullable=True)
    pending_payment_url = Column(String(500), nullable=True)
    pending_link_expires_at = Column(DateTime, nullable=True)

    # Código de activación autogenerado para soporte manual
    activation_code = Column(String(50), unique=True, index=True, nullable=True)

    @property
    def is_pro_active(self) -> bool:
        if not self.is_active:
            return False
        now = datetime.utcnow()
        if self.plan_type == "PRO" and self.pro_expires_at and self.pro_expires_at > now:
            return True
        if self.plan_type == "TRIAL" and self.trial_ends_at and self.trial_ends_at > now:
            return True
        return False

    @property
    def remaining_days(self) -> int:
        now = datetime.utcnow()
        if self.plan_type == "PRO" and self.pro_expires_at and self.pro_expires_at > now:
            return (self.pro_expires_at - now).days
        if self.plan_type == "TRIAL" and self.trial_ends_at and self.trial_ends_at > now:
            return (self.trial_ends_at - now).days
        return 0


class PaymentTransaction(Base):
    __tablename__ = "zippy_payment_transactions"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String(100), index=True, nullable=False)
    cobrix_debt_id = Column(Integer, nullable=True)
    payment_link = Column(String(500), nullable=True)
    amount_usd = Column(Float, default=2.99, nullable=False)
    currency = Column(String(10), default="USD", nullable=False)
    status = Column(String(30), default="pending", nullable=False) # pending, completed, failed
    transaction_reference = Column(String(100), nullable=True)
    payment_method = Column(String(50), nullable=True)
    paid_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)


class AdminUser(Base):
    __tablename__ = "zippy_admin_users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(100), unique=True, index=True, nullable=False)
    password_hash = Column(String(255), nullable=False)
    email = Column(String(150), nullable=True)
    auth0_sub = Column(String(200), nullable=True)
    is_active = Column(Boolean, default=True, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)


class SystemSetting(Base):
    __tablename__ = "zippy_system_settings"

    key = Column(String(100), primary_key=True)
    value = Column(String(500), nullable=False)
    description = Column(String(255), nullable=True)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class DashboardAccessPin(Base):
    __tablename__ = "zippy_dashboard_pins"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String(100), index=True, nullable=False)
    phone = Column(String(50), index=True, nullable=False)
    pin = Column(String(10), nullable=False)
    expires_at = Column(DateTime, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)
    is_used = Column(Boolean, default=False, nullable=False)
