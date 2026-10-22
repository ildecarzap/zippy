# ==============================================================================
# DOCKERFILE ZIPPY WEB & CONTROL HUB (CIEM TECH SOLUTIONS C.A)
# Optimizado para Google Cloud Platform (Cloud Run / Compute Engine)
# ==============================================================================

FROM python:3.11-slim

# Evitar generación de .pyc y habilitar logs inmediatos sin buffering
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8080

WORKDIR /app

# Instalar dependencias del sistema mínimas para PostgreSQL y compilación segura
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    libpq-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copiar requirements e instalar dependencias
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# Copiar la aplicación completa
COPY app/ ./app/

# Exponer el puerto predeterminado (GCP Cloud Run inyecta dinámicamente $PORT)
EXPOSE 8080

# Comando de ejecución con soporte para el puerto dinámico de Cloud Run
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8080}"]
