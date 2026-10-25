# Zeasy Web & Control Hub ⚡
**Portal Oficial, Instructivo de Usuario y Panel Privado de Licencias CIEM**  
*Desarrollado para Ciem Tech Solutions C.A (RIF: J-50849495-4)*

---

## 🎨 Características Principales

1. **Diseño en Tonos Pasteles:**
   * Paleta suave (verde salvia, pizarra suave, menta pastel, marfil cálido) sin colores chillones ni radioactivos.
   * Totalmente responsivo para móviles y computadoras.
2. **Homepage Oficial (`/`):**
   * Landing de presentación de Zeasy como la caja registradora inteligente y punto de cobro móvil para Venezuela.
3. **Instructivo Interactivo (`/guia`):**
   * Manual paso a paso para los usuarios que ingresan desde el botón *"Acerca de"* de la aplicación Android.
   * Explica configuración de bancos (Banesco, Mercantil, BNC, Venezuela), Soundbox con campana y voz, carga por voz, cierre de jornada y copias de seguridad.
4. **Panel Administrativo Oculto y Seguro (`/portal-seguro-ciem`):**
   * Ruta privada configurable vía variable de entorno `ADMIN_SECRET_PATH`.
   * Protección contra ataques de fuerza bruta, cookies de sesión `HTTPOnly` y mitigación CSRF.
   * Soporte listo para **Auth0 Universal Login**.
   * Gestión de dispositivos: Búsqueda por comercio, teléfono o ID de dispositivo.
   * **1-Clic PRO:** Extender plan PRO por 30, 90 o 365 días al instante.
   * **CobrixPay WhatsApp:** Genera el enlace de cobro y abre el chat de WhatsApp con el mensaje pre-armado para el comercio.
5. **Integración Webhook CobrixPay (`/webhooks/cobrixpay`):**
   * Validación canónica de firma HMAC-SHA256 según la especificación de CobrixPay.
   * Auto-renovación de Zeasy PRO en menos de 10 segundos al confirmarse el pago por Pago Móvil.
6. **API para la App Android:**
   * `GET /api/v1/license/check?device_id=...`: Valida estado PRO y días restantes.
   * `POST /api/v1/license/register`: Registra nuevos dispositivos al instalar la app.
   * `GET /api/v1/bcv`: Consulta centralizada y en tiempo real de la tasa oficial del BCV.

---

## 🚀 Cómo Ejecutar en Local

### 1. Activar la Base de Datos PostgreSQL Local
El contenedor de PostgreSQL ya está configurado en tu Docker Compose principal:
```bash
docker compose up -d postgres
```

### 2. Iniciar el Servidor Zeasy Web
```bash
cd zeasy-web
source .venv/bin/activate
uvicorn app.main:app --host 0.0.0.0 --port 8088 --reload
```

* **Homepage:** [http://localhost:8088/](http://localhost:8088/)
* **Instructivo:** [http://localhost:8088/guia](http://localhost:8088/guia)
* **Planes:** [http://localhost:8088/planes](http://localhost:8088/planes)
* **Panel Privado:** [http://localhost:8088/portal-seguro-ciem/login](http://localhost:8088/portal-seguro-ciem/login)
  * **Usuario:** `admin_ciem`
  * **Contraseña:** `Zeasy2026#CiemAdmin` *(personalizable en `.env`)*

---

## 📚 Documentación Técnica Completa
Para una explicación exhaustiva de la arquitectura, flujo de identidad de terminales, integración con CobrixPay (Company 5, tokens de checkout y webhooks), y despliegue paso a paso en GCP Cloud Run, consulta:
👉 **[DOCUMENTACION_ZEASY.md](../DOCUMENTACION_ZEASY.md)** en la raíz del repositorio.

---

## 🚀 Despliegue en Producción (Google Cloud Run)

El contenedor está 100% optimizado para **GCP Cloud Run** mediante el [Dockerfile](./Dockerfile).

### 1. Construir la imagen con Google Cloud Build:
```bash
gcloud builds submit zeasy-web --tag gcr.io/TU_PROYECTO_GCP_ID/zeasy-web:latest
```

### 2. Desplegar en Cloud Run con tus variables de entorno:
```bash
gcloud run deploy zeasy-web \
    --image gcr.io/TU_PROYECTO_GCP_ID/zeasy-web:latest \
    --platform managed \
    --region us-central1 \
    --allow-unauthenticated \
    --set-env-vars "APP_ENV=production,DEBUG=false,PORT=8080,ADMIN_SECRET_PATH=/portal-seguro-ciem,COBRIXPAY_BASE_URL=https://app.cobrix-pay.com,COBRIXPAY_COMPANY_ID=5,ZEASY_PRICE_USD=2.99" \
    --set-env-vars "DATABASE_URL=postgresql://postgres.xxx:TU_PASSWORD@aws-0-sa-east-1.pooler.supabase.com:6543/postgres" \
    --set-env-vars "SECRET_KEY=tu_secreto_generado,COBRIXPAY_WEBHOOK_SECRET=tu_webhook_secret"
```

---

## ☁️ Conexión con Supabase en la Nube
1. Crea tu proyecto en [supabase.com](https://supabase.com/).
2. En **Project Settings → Database → Connection String**, copia el **URI** (modo Session/Transaction pooler).
3. Configura `DATABASE_URL` con tu URI de Supabase.
4. Al arrancar `zeasy-web`, SQLAlchemy creará automáticamente todas las tablas requeridas (`zeasy_device_licenses`, `zeasy_payment_transactions`, `zeasy_admin_users`).

