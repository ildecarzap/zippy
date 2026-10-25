# ⚡ Ecosistema Zeasy — Documentación Técnica y de Operaciones

**Plataforma de Caja Registradora Inteligente, Licenciamiento y Cobranza Automatizada**  
*Propiedad de Ciem Tech Solutions C.A (RIF: J-50849495-4)*

---

## 📌 Tabla de Contenidos
1. [Visión General del Sistema](#1-visión-general-del-sistema)
2. [Arquitectura y Componentes](#2-arquitectura-y-componentes)
3. [Flujo de Identidad del Dispositivo y Licenciamiento](#3-flujo-de-identidad-del-dispositivo-y-licenciamiento)
4. [Integración con CobrixPay (Cobro y Activación Automática)](#4-integración-con-cobrixpay-cobro-y-activación-automática)
5. [Estructura de la Base de Datos](#5-estructura-de-la-base-de-datos)
6. [Variables de Entorno (.env)](#6-variables-de-entorno-env)
7. [Guía de Despliegue en Google Cloud Platform (GCP Cloud Run)](#7-guía-de-despliegue-en-google-cloud-platform-gcp-cloud-run)
8. [Configuración de la Base de Datos en Supabase](#8-configuración-de-la-base-de-datos-en-supabase)
9. [Conectar la App Android (cobrix-pocket) a Producción](#9-conectar-la-app-android-cobrix-pocket-a-producción)
10. [Operaciones y Soporte Administrativo](#10-operaciones-y-soporte-administrativo)

---

## 1. Visión General del Sistema

El ecosistema **Zeasy** está compuesto por tres piezas clave que interactúan de forma armónica para brindar una experiencia de punto de venta y validación de pagos en Venezuela:

```
┌─────────────────────────────────────────────────────────────┐
│                    DISPOSITIVO MÓVIL                        │
│   App Android: cobrix-pocket (Zeasy Pocket POS)            │
│   - Caja registradora rápida                                │
│   - Soundbox (campana y voz de cobro)                       │
│   - Lectura de notificaciones bancarias                     │
│   - Sync periódico de licencia (ZeasySyncManager)           │
└──────────────┬──────────────────────────────▲───────────────┘
               │                              │
        1. Sync / Heartbeat            2. Estado Licencia
               │                              │
┌──────────────▼──────────────────────────────┴───────────────┐
│                     NUBE GCP / SERVER                       │
│   Backend & Web Hub: zeasy-web (FastAPI + Jinja2)           │
│   - Landing page oficial (/)                                │
│   - Instructivo interactivo (/guia)                         │
│   - Planes y precios (/planes)                              │
│   - Panel administrativo oculto (/portal-seguro-ciem)       │
│   - API de licenciamiento (/api/v1/license/*)               │
│   - Receptor de Webhooks (/webhooks/cobrixpay)              │
└──────────────┬──────────────────────────────▲───────────────┘
               │                              │
        3. Crear Deuda / Token         4. Webhook payment.success
               │                              │
┌──────────────▼──────────────────────────────┴───────────────┐
│                     FINTECH ENGINE                          │
│   Pasarela de Cobro: CobrixPay (Company 5: Ciem Tech)       │
│   - Genera payment_token seguro                             │
│   - Checkout hosted: https://app.cobrix-pay.com/pay/{token} │
│   - Conciliación de Pago Móvil en tiempo real               │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Arquitectura y Componentes

### A. App Android (`cobrix-pocket`)
* **Código fuente:** `cobrix-pocket/app/src/main/java/com/cobrix/pocket/`
* **Mánager de Licencia:** `utils/ZeasySyncManager.kt`
  * Se ejecuta al iniciar la aplicación (`MainActivity.kt`) y periódicamente en segundo plano.
  * Extrae de forma segura el perfil del comercio (Nombre comercial, Teléfono, Cédula/RIF y Versión).
  * Envía un pulso HTTP POST a `/api/v1/license/register`.
  * Recibe el estado actual de la licencia (`TRIAL`, `ACTIVE`, `EXPIRED`, `GRACE`) y los días de vigencia restantes.
  * Si la licencia está activa o en período de prueba, habilita todas las funciones PRO.
  * El botón **"Acerca de"** en el menú de la app abre en el navegador la URL del instructivo (`/guia`).

### B. Servidor Web & Panel de Control (`zeasy-web`)
* **Tecnología:** Python 3.11, FastAPI, SQLAlchemy ORM, PostgreSQL (o Supabase), Jinja2 con estilos en CSS puro con tonos pasteles profesionales.
* **Seguridad:**
  * Ruta administrativa privada configurable vía `ADMIN_SECRET_PATH` (por defecto `/portal-seguro-ciem`).
  * Autenticación con sesión basada en cookies cifradas `HTTPOnly` y soporte para Auth0.
  * Mitigación de fuerza bruta y CSRF.
* **Módulos clave:**
  * `app/routers/pages.py`: Páginas públicas (Homepage, Instructivo, Planes).
  * `app/routers/api.py`: Endpoints consumidos por la aplicación Android.
  * `app/routers/admin.py`: Panel de control para consultar comercios registrados, extender licencias y generar links de cobro.
  * `app/routers/webhooks.py`: Receptor de notificaciones de pago con validación de firma HMAC-SHA256.
  * `app/services/cobrixpay.py`: Cliente de integración directa con la base de datos y pasarela de CobrixPay.

---

## 3. Flujo de Identidad del Dispositivo y Licenciamiento

### 1. ¿Cómo se identifica cada teléfono?
Cada instalación genera un **`device_id`** persistente y único en el almacenamiento local del teléfono (ejemplo: `ZIP-600C`, `ZIP-4A82`). Este ID viaja en todos los encabezados y cuerpos de solicitud.

### 2. ¿Cómo se registran los dispositivos en zeasy-web?
1. Al abrir la app Zeasy, `ZeasySyncManager` dispara una petición:
   ```http
   POST /api/v1/license/register
   Content-Type: application/json

   {
     "device_id": "ZIP-600C",
     "merchant_name": "Ferretería tu tornillo 2026, C.A",
     "phone": "+584121234567",
     "doc_id": "V-18456789",
     "email": "contacto@tutornillo.com",
     "app_version": "1.0.0"
   }
   ```
2. El servidor busca si el `device_id` ya existe en la tabla `zeasy_device_licenses`:
   * **Si es nuevo:** Se registra automáticamente y se le otorga un **período de prueba (TRIAL) de 15 días** gratuitos.
   * **Si ya existe:** Actualiza `merchant_name`, `phone`, `doc_id`, `app_version` y refresca la fecha `last_seen_at`.
3. Devuelve al teléfono la respuesta con el estado de la licencia:
   ```json
   {
     "status": "success",
     "device_id": "ZIP-600C",
     "is_pro": true,
     "license_type": "TRIAL",
     "days_remaining": 15,
     "expires_at": "2026-11-05T18:30:00"
   }
   ```

---

## 4. Integración con CobrixPay (Cobro y Activación Automática)

### Datos de Facturación de CIEM
* **Compañía en CobrixPay:** `Ciem tech Solutions C.A`
* **ID de Compañía (`COBRIXPAY_COMPANY_ID`):** `5`
* **Precio mensual de Zeasy PRO:** **$2.99 USD / mes**

### Flujo de Generación de Token de Cobro
1. Desde el **Panel Administrativo**, el operador hace clic en **"Cobrar con CobrixPay"** en cualquier dispositivo.
2. Se abre un **Modal Interactivo** donde se selecciona el período deseado (1 mes = $2.99, 3 meses = $8.97, 12 meses = $35.88).
3. `zeasy-web` llama a `app/services/cobrixpay.py`:
   * Localiza o crea el deudor en la tabla `debtors` de CobrixPay usando la Cédula/RIF (`doc_id`), nombre del comercio y teléfono.
   * Si el comercio no tiene email registrado, se le asigna de forma transparente `{device_id}@zeasy.ciemtech.com`.
   * Registra una deuda en la tabla `debts` con `company_id: 5`, concepto detallado y metadata `{"app": "zeasy", "device_id": "ZIP-600C", "months": 1}`.
   * Obtiene el **`payment_token`** canónico (ejemplo: `GY7df7q1uhcGmGH3GSPO_vJg`).
4. Se genera la URL oficial de cobro:
   ```
   https://app.cobrix-pay.com/pay/GY7df7q1uhcGmGH3GSPO_vJg
   ```
5. El modal muestra el enlace y un mensaje de WhatsApp 100% editable para que el operador envíe el cobro con 1 clic al teléfono del cliente.

### Flujo de Confirmación Automática (Webhook)
```
[Cliente Paga en CobrixPay] 
           │
           ▼
[CobrixPay concilia Pago Móvil]
           │
           ▼ (HTTP POST con firma HMAC-SHA256)
[POST /webhooks/cobrixpay]
           │
           ├─ 1. Valida el secreto HMAC con COBRIXPAY_WEBHOOK_SECRET
           ├─ 2. Extrae device_id y months de los metadatos
           ├─ 3. Suma los meses a la vigencia actual del dispositivo
           ├─ 4. Cambia status a ACTIVE
           └─ 5. Guarda recibo en zeasy_payment_transactions
```

---

## 5. Estructura de la Base de Datos

### Tabla: `zeasy_device_licenses`
| Columna | Tipo | Descripción |
| :--- | :--- | :--- |
| `id` | SERIAL PRIMARY KEY | ID interno secuencial |
| `device_id` | VARCHAR(100) UNIQUE | Identificador único del teléfono |
| `merchant_name` | VARCHAR(150) | Nombre comercial del negocio |
| `phone` | VARCHAR(50) | Teléfono de contacto / WhatsApp |
| `doc_id` | VARCHAR(50) | Cédula de Identidad o RIF del titular |
| `email` | VARCHAR(100) | Correo electrónico (opcional) |
| `license_type` | VARCHAR(20) | `TRIAL`, `PRO_MONTHLY`, `PRO_ANNUAL`, `LIFETIME` |
| `status` | VARCHAR(20) | `ACTIVE`, `TRIAL`, `EXPIRED`, `GRACE`, `REVOKED` |
| `starts_at` | TIMESTAMP WITH TIME ZONE | Fecha de inicio de la licencia |
| `expires_at` | TIMESTAMP WITH TIME ZONE | Fecha de vencimiento |
| `last_seen_at` | TIMESTAMP WITH TIME ZONE | Último pulso de sincronización |
| `app_version` | VARCHAR(30) | Versión instalada en el dispositivo |
| `notes` | TEXT | Notas internas del operador |

### Tabla: `zeasy_payment_transactions`
| Columna | Tipo | Descripción |
| :--- | :--- | :--- |
| `id` | SERIAL PRIMARY KEY | ID de transacción |
| `device_id` | VARCHAR(100) | Dispositivo beneficiado |
| `transaction_ref` | VARCHAR(100) UNIQUE | Referencia bancaria o ID de cobro |
| `amount` | NUMERIC(10, 2) | Monto cobrado (ej. 2.99) |
| `currency` | VARCHAR(10) | Moneda (`USD` o `VES`) |
| `gateway` | VARCHAR(30) | Pasarela (`cobrixpay`, `manual`, etc.) |
| `status` | VARCHAR(20) | `COMPLETED`, `PENDING`, `FAILED` |
| `payload` | JSONB / TEXT | Payload completo del webhook |

---

## 6. Variables de Entorno (`.env`)

Copia `zeasy-web/.env.example` hacia `zeasy-web/.env` y ajusta según el entorno:

```env
# ==============================================================================
# CONFIGURACIÓN DEL ENTORNO
# ==============================================================================
APP_ENV=production
DEBUG=false
SECRET_KEY=genera_un_secreto_hexadecimal_largo_con_openssl_rand_hex_32
PORT=8080

# URL de la Base de Datos Supabase (Producción / Dev Oficial)
DATABASE_URL=postgresql://postgres.rjsabiheelhuotgqwgmy:CobrixPay2026+@aws-1-us-east-1.pooler.supabase.com:5432/postgres

# ==============================================================================
# SEGURIDAD Y PANEL ADMINISTRATIVO
# ==============================================================================
# Ruta privada de acceso al panel (Cámbiala a una que solo tú conozcas)
ADMIN_SECRET_PATH=/portal-seguro-ciem
ADMIN_USERNAME=admin_ciem
ADMIN_PASSWORD_HASH=$2b$12$....genera_tu_hash_con_bcrypt....

# Auth0 (Opcional si usas login federado)
AUTH0_CLIENT_ID=
AUTH0_CLIENT_SECRET=
AUTH0_DOMAIN=
AUTH0_CALLBACK_URL=https://zeasy.ciemtech.com/portal-seguro-ciem/callback

# ==============================================================================
# PASARELA COBRIXPAY
# ==============================================================================
COBRIXPAY_BASE_URL=https://app.cobrix-pay.com

# ID de la empresa en CobrixPay:
# - En pruebas locales: 5 (Ciem tech Solutions C.A)
# - En producción: Coloca el ID correspondiente a CIEM en el CobrixPay real
COBRIXPAY_COMPANY_ID=5
COBRIXPAY_PLAN_PRICE_USD=2.99
COBRIXPAY_WEBHOOK_SECRET=tu_secreto_compartido_para_verificar_firma_hmac

# Conexión dedicada a CobrixPay (Opcional):
# Si CobrixPay tiene una base de datos PostgreSQL separada a la de Zeasy en producción:
# COBRIXPAY_DATABASE_URL=postgresql://user:password@host:5432/cobrixpay_prod
COBRIXPAY_DATABASE_URL=
```

---

## 7. Guía de Despliegue en Google Cloud Platform (GCP Cloud Run)

**Google Cloud Run** es la opción recomendada para Zeasy Web porque:
1. Ofrece HTTPS automático con certificados SSL gestionados sin costo.
2. Escala a cero cuando no hay tráfico (costo mínimo o gratuito para inicios).
3. Permite inyectar variables de entorno de forma segura desde la consola o CLI.
4. Soporta el `Dockerfile` que ya está preparado.

### Paso 1: Autenticar Google Cloud CLI
En tu terminal local:
```bash
gcloud auth login
gcloud config set project TU_PROYECTO_GCP_ID
```

### Paso 2: Habilitar los servicios necesarios en GCP
```bash
gcloud services enable \
    run.googleapis.com \
    cloudbuild.googleapis.com \
    artifactregistry.googleapis.com
```

### Paso 3: Construir y Subir la Imagen con Google Cloud Build
Desde la raíz del proyecto `cobrix-pay`:
```bash
gcloud builds submit zeasy-web --tag gcr.io/TU_PROYECTO_GCP_ID/zeasy-web:latest
```

### Paso 4: Desplegar en Cloud Run
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

Al terminar, Google Cloud te entregará una URL HTTPS similar a:
`https://zeasy-web-xxxxxx-uc.a.run.app`

### Paso 5: Asociar Dominio Personalizado (ej. `zeasy.ciemtech.com`)
1. En la consola de GCP, ve a **Cloud Run → Administrar dominios personalizados**.
2. Haz clic en **Agregar asignación**.
3. Selecciona el servicio `zeasy-web` y escribe el subdominio `zeasy.ciemtech.com`.
4. Copia los registros DNS (tipo `CNAME` o `A`) provistos por Google y agrégalos en tu panel de DNS de tu dominio (Cloudflare, GoDaddy, Hostinger, etc.).

---

## 8. Configuración de la Base de Datos en Supabase

Para no depender de un servidor de base de datos local cuando estés en producción:

1. Entra a [https://supabase.com/](https://supabase.com/) e inicia sesión.
2. Crea un nuevo proyecto llamado `zeasy-db`.
3. Elige la región más cercana (ejemplo: `São Paulo (sa-east-1)` o `US East (N. Virginia)`).
4. Define una contraseña segura para el usuario `postgres` y guárdala.
5. Ve a **Project Settings → Database → Connection String**.
6. Selecciona la pestaña **URI** y modo **Connection Pooling (Transaction/Session)**.
7. Copia la cadena y colócala en tu variable `DATABASE_URL`:
   ```
   postgresql://postgres.[PROJECT_REF]:[PASSWORD]@aws-0-sa-east-1.pooler.supabase.com:6543/postgres
   ```
8. **No necesitas correr migraciones manuales:** En el primer arranque de `zeasy-web`, SQLAlchemy ejecutará `Base.metadata.create_all()` y creará automáticamente todas las tablas requeridas.

---

## 9. Conectar la App Android (`cobrix-pocket`) a Producción

Una vez que tengas tu URL de producción en GCP (por ejemplo `https://zeasy.ciemtech.com` o `https://zeasy-web-xxxx.run.app`):

1. Abre el archivo:
   `cobrix-pocket/app/src/main/java/com/cobrix/pocket/utils/ZeasySyncManager.kt`
2. Localiza la constante:
   ```kotlin
   private const val DEFAULT_BACKEND_URL = "http://192.168.0.191:8088"
   ```
3. Reemplázala con la URL de producción:
   ```kotlin
   private const val DEFAULT_BACKEND_URL = "https://zeasy.ciemtech.com"
   ```
4. En el archivo `cobrix-pocket/app/src/main/java/com/cobrix/pocket/ui/theme/views/DrawerMenu.kt`, actualiza el link del botón *"Acerca de Zeasy"* hacia `https://zeasy.ciemtech.com/guia`.
5. Compila el APK de producción:
   ```bash
   cd cobrix-pocket
   ./gradlew assembleRelease
   ```
   *(O `assembleDebug` para pruebas previas con la URL de producción).*

---

## 10. Operaciones y Soporte Administrativo

### Acceder al Panel de Control
1. Visita en tu navegador:
   `https://zeasy.ciemtech.com/portal-seguro-ciem/login`
2. Ingresa con tus credenciales administrativas.

### Tareas Comunes:
* **Ver qué comercios están usando la app:** La tabla principal muestra el nombre comercial, teléfono, Cédula/RIF, versión de la app, estado de la licencia y la hora de su último pulso de conexión (`Última Sinc`).
* **Extender licencia manualmente (1 Clic):** Si un cliente te pagó en efectivo o transferencia bancaria directa fuera de CobrixPay, haz clic en **+30 días**, **+90 días** o **+1 Año** para extenderle la vigencia al instante.
* **Cobrar por WhatsApp con CobrixPay:**
  1. Haz clic en el botón verde con el icono de WhatsApp (`Cobrar con CobrixPay`).
  2. Selecciona la cantidad de meses (1 mes = $2.99).
  3. Revisa o ajusta el mensaje en el recuadro editable si deseas personalizar el saludo.
  4. Haz clic en **"Abrir Chat de WhatsApp"**. El cliente recibirá el enlace directo `https://app.cobrix-pay.com/pay/{payment_token}` para pagar con Pago Móvil.
  5. Una vez que el cliente pague, Zeasy Web renovará la licencia en tiempo real de forma automática.
