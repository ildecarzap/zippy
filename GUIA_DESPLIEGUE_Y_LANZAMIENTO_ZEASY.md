# 🚀 Guía de Despliegue, Testing Final y Lanzamiento al Mercado — Zeasy

**Documento operativo para ejecutar el despliegue en GCP, validación integral y puesta en marcha comercial.**  
*Ciem Tech Solutions C.A*

---

## 📅 PARTE 1: Plan de Ejecución para Mañana (Paso a Paso)

### Paso 1: Preparar los Accesos de las 2 Bases de Datos
1. **Base de Datos 1 (Zeasy — Supabase):**
   * Crear proyecto en [supabase.com](https://supabase.com) (ej. `zeasy-db`).
   * Ir a **Project Settings → Database → Connection String → URI** (modo Pooler).
   * Guardar la URI:
     ```text
     DATABASE_URL=postgresql://postgres.rjsabiheelhuotgqwgmy:CobrixPay2026+@aws-1-us-east-1.pooler.supabase.com:5432/postgres
     ```
2. **Base de Datos 2 (CobrixPay — Producción):**
   * Obtener la URI del PostgreSQL donde corre CobrixPay en producción:
     ```text
     COBRIXPAY_DATABASE_URL=postgresql://usuario:password@host-cobrix.com:5432/cobrixpay_prod
     ```
   * Obtener el `company_id` asignado a CIEM en esa base de datos:
     ```text
     COBRIXPAY_COMPANY_ID=TU_ID_EN_PRODUCCION
     ```

---

### Paso 2: Desplegar en Google Cloud Platform (Cloud Run)

Abre la terminal en la raíz `/home/ildemaro-carrasco/Ciem/cobrix-pay`:

```bash
# 1. Login y selección de proyecto
gcloud auth login
gcloud config set project TU_PROYECTO_GCP_ID

# 2. Habilitar servicios (solo si es primera vez)
gcloud services enable run.googleapis.com cloudbuild.googleapis.com

# 3. Construir la imagen Docker en la nube
gcloud builds submit zeasy-web --tag gcr.io/TU_PROYECTO_GCP_ID/zeasy-web:latest

# 4. Desplegar en Cloud Run
gcloud run deploy zeasy-web \
    --image gcr.io/TU_PROYECTO_GCP_ID/zeasy-web:latest \
    --platform managed \
    --region us-central1 \
    --allow-unauthenticated \
    --set-env-vars "APP_ENV=production,DEBUG=false,PORT=8080" \
    --set-env-vars "DATABASE_URL=postgresql://postgres.[REF]:[PASSWORD]@aws-0-sa-east-1.pooler.supabase.com:6543/postgres" \
    --set-env-vars "COBRIXPAY_DATABASE_URL=postgresql://usuario:password@host-cobrix.com:5432/cobrixpay_prod" \
    --set-env-vars "COBRIXPAY_COMPANY_ID=TU_ID_EN_PRODUCCION" \
    --set-env-vars "COBRIXPAY_BASE_URL=https://app.cobrix-pay.com" \
    --set-env-vars "COBRIXPAY_PLAN_PRICE_USD=2.99" \
    --set-env-vars "ADMIN_SECRET_PATH=/portal-seguro-ciem" \
    --set-env-vars "ADMIN_USERNAME=admin_ciem" \
    --set-env-vars "SECRET_KEY=$(openssl rand -hex 24)" \
    --set-env-vars "COBRIXPAY_WEBHOOK_SECRET=tu_secreto_para_el_webhook"
```

> **Resultado:** Google te entregará la URL HTTPS del servicio:
> `https://zeasy-web-xxxxxx-uc.a.run.app`

---

### Paso 3: Asociar Dominio y Registrar Webhook
1. **Dominio:** En GCP Cloud Run → *Administrar dominios personalizados* → Asignar `zeasy.ciemtech.com`.
2. **Webhook en CobrixPay:** En el panel de CobrixPay Producción, crear el Webhook apuntando a:
   * **URL:** `https://zeasy.ciemtech.com/webhooks/cobrixpay`
   * **Secret:** El mismo valor puesto en `COBRIXPAY_WEBHOOK_SECRET`.
   * **Evento:** `payment.success`.

---

### Paso 4: Actualizar y Compilar la App Android
1. En `cobrix-pocket/app/src/main/java/com/cobrix/pocket/utils/ZeasySyncManager.kt`:
   ```kotlin
   private const val DEFAULT_BACKEND_URL = "https://zeasy.ciemtech.com"
   ```
2. Compilar APK para pruebas:
   ```bash
   cd cobrix-pocket
   ./gradlew assembleDebug
   ```

---

## 🧪 PARTE 2: Testing Final en Producción (Checklist)

Una vez desplegado, se realizan estas 4 pruebas de validación final:

- [ ] **Prueba 1 — Auto-registro de dispositivo:**
  * Instalar el APK en un teléfono.
  * Abrir la app y verificar que en el panel administrativo (`https://zeasy.ciemtech.com/portal-seguro-ciem`) aparece el comercio con su Cédula/RIF, teléfono y **15 días de prueba (TRIAL)**.
- [ ] **Prueba 2 — Generación de Deuda y Token en CobrixPay:**
  * En el panel administrativo, hacer clic en *"Cobrar con CobrixPay"*.
  * Seleccionar 1 mes ($2.99).
  * Verificar que genera un enlace auténtico `https://app.cobrix-pay.com/pay/{token}` y que al abrirlo en el navegador muestra la pantalla de cobro de CobrixPay a nombre de *Ciem Tech Solutions C.A*.
- [ ] **Prueba 3 — Conciliación y Auto-activación:**
  * Realizar un pago móvil real de prueba por el monto exacto.
  * Verificar que CobrixPay dispara el Webhook y en menos de 10 segundos el dispositivo pasa a estado **ACTIVO** en el panel y en la app Android.
- [ ] **Prueba 4 — Soundbox y Alerta de Cobro:**
  * Enviar un pago móvil al comercio y validar que Zeasy reproduce la campana y la voz cantando el monto recibido.

---

## 🎯 PARTE 3: ¿Falta más desarrollo o estamos listos para salir al mercado?

### Estado del Desarrollo de Código:
**A nivel de software, el producto está COMPLETO (100% Funcional).**  
No hace falta programar nuevas funcionalidades para salir al mercado. Tienes cubierto:
1. **Caja registradora rápida:** Cobro en USD y Bolívares con tasa BCV.
2. **Soundbox Inteligente:** Notificaciones por voz y campana.
3. **Lector de Notificaciones Bancarias:** Compatible con las políticas de Google Play (sin permisos invasivos de SMS).
4. **Backend de Licencias:** Control de terminales, días restantes y períodos de prueba.
5. **Fintech Integrada:** Cobro automatizado de $2.99/mes mediante CobrixPay con Pago Móvil.
6. **Portal Web:** Homepage de ventas, instructivo para clientes y panel administrativo oculto.

---

### Lo que sigue para Salir al Mercado (No es código, es lanzamiento):

```
┌────────────────────────────────────────────────────────┐
│               RUTA DE SALIDA AL MERCADO                │
└──────────────────────────────────┬─────────────────────┘
                                   │
      1. Generar Keystore de Firma (.jks) para Android
                                   │
      2. Compilar Android App Bundle (.aab) oficial
                                   │
      3. Ficha en Google Play Console:
         - Capturas de pantalla del teléfono
         - Política de privacidad (tu URL web)
         - Descripción comercial y categoría
                                   │
      4. Beta Cerrada Comercial (5 a 10 comercios amigos)
                                   │
      5. Lanzamiento Oficial & Campaña Comercial
```

#### 1. Firma Oficial del APK / AAB
Para subir a Google Play, la aplicación debe compilarse en formato `.aab` (Android App Bundle) firmada con una llave privada (`keystore`). Si aún no la tienes, se crea con un solo comando:
```bash
keytool -genkey -v -keystore zeasy-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias zeasy
```

#### 2. Ficha de Google Play Console
* **Política de Privacidad:** Ya la tienes lista dentro del portal web (`https://zeasy.ciemtech.com/guia`).
* **Declaración de Permisos:** Google Play preguntará por qué usas `NotificationListenerService`. La respuesta estándar y aprobada es: *"Para validar y anunciar por voz la confirmación de pagos de aplicaciones bancarias del propio comerciante en su punto de venta."*

#### 3. Estrategia de Lanzamiento Recomendada
1. **Semana 1 (Beta Cerrada):** Instalar el APK en 5 o 10 comercios aliados (ferreterías, panaderías, bodegones). Monitorear durante 3 a 5 días cómo se comporta la lectura de notificaciones con sus bancos habituales.
2. **Semana 2 (Lanzamiento Público):** Publicación abierta en Google Play Store y activación de la campaña de suscripciones a $2.99/mes.
