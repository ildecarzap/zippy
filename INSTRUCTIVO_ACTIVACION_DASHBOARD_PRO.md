# 🚀 Guía de Activación: Restricción y Monetización de Zeasy Dashboard PC ($2.99/mes)

Este documento detalla los pasos exactos para activar el acceso exclusivo al **Dashboard PC** (`dashboard.zeasy.app`) únicamente para usuarios con suscripción **Zeasy PRO**, una vez concluida la fase de pruebas con Google Play.

---

## 📌 Estado Actual (Fase de Pruebas Google Play)
* **Web:** Libre y abierta para revisión y pruebas sin ningún tipo de bloqueo ni credenciales (`DASHBOARD_PRO_AUTH_REQUIRED=false`).
* **App Móvil:** La tarjeta *"Zeasy Dashboard en PC"* y el botón *"Generar Código de Acceso"* están ocultos (`android:visibility="gone"`), evitando cualquier confusión o rechazo en Google Play.
* **Todo el código backend, frontend y móvil ya está desarrollado e integrado.**

---

## 🛠️ Pasos para Encender en Producción Comercial

### Paso 1: Activar la Bandera en el Servidor Web (Cloud Run / .env)
En tu servicio de producción (Google Cloud Run) o en tu archivo [web/.env](file:///home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/web/.env):

Cambia el valor de la variable de entorno:
```bash
# Cambiar de false a true:
DASHBOARD_PRO_AUTH_REQUIRED=true
```

*(También está definida por defecto en [web/app/config.py](file:///home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/web/app/config.py)).*

Al activar esta variable:
1. El Dashboard Web (`dashboard.zeasy.app`) exigirá automáticamente número de teléfono y el PIN de 6 dígitos antes de permitir procesar o cargar cualquier archivo de ventas.
2. Si el teléfono no tiene una suscripción Zeasy PRO activa en la base de datos `zippy_device_licenses`, el acceso es denegado.
3. Al introducir el PIN correcto de un usuario PRO, se genera una sesión autenticada válida por **30 días**.

---

### Paso 2: Mostrar la Tarjeta de Vinculación en la App Móvil
En el archivo de interfaz de la app Android:
[mobile/app/src/main/res/layout/activity_main.xml](file:///home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/mobile/app/src/main/res/layout/activity_main.xml)

Busca la etiqueta:
```xml
<!-- TARJETA VINCULAR DASHBOARD PC (EXCLUSIVO ZEASY PRO) -->
<com.google.android.material.card.MaterialCardView
    android:id="@+id/cardSettingsDashboardPc"
    ...
    android:visibility="gone">
```

Cambia `android:visibility="gone"` por:
```xml
    android:visibility="visible">
```

*(La lógica de generación de PIN y comunicación con el servidor ya está 100% programada en [MainActivity.kt](file:///home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/mobile/app/src/main/java/com/cobrix/pocket/ui/MainActivity.kt) y [ZeasySyncManager.kt](file:///home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/mobile/app/src/main/java/com/cobrix/pocket/utils/ZeasySyncManager.kt)).*

---

## 🔄 Flujo del Usuario en Producción

1. **El Comerciante abre la App en su teléfono:**
   - Va a **Ajustes > Zeasy Dashboard en PC**.
   - Presiona: **"🔑 Generar Código de Acceso PC"**.
   - Si tiene **Plan Básico**: la app le indica que el Dashboard PC es exclusivo de Zeasy PRO ($2.99/mes) y le ofrece el enlace de pago.
   - Si tiene **Zeasy PRO**: la app genera un PIN de 6 dígitos (ej: `729-104`) válido por 10 minutos.

2. **El Comerciante abre la Web en su computadora:**
   - Entra a `https://dashboard.zeasy.app`.
   - Aparece el modal de seguridad: *"Vincular con Zeasy PRO"*.
   - Ingresa su teléfono de Pago Móvil y el código PIN de 6 dígitos.
   - Presiona: **"🔓 Desbloquear Dashboard PC"**.

3. **Acceso Autorizado por 30 Días:**
   - El servidor valida que el PIN coincida y que su suscripción PRO esté vigente.
   - La computadora guarda la autorización durante 30 días, permitiéndole entrar directo a auditar sus jornadas y exportar a Excel/PDF sin tener que pedir el PIN en cada sesión.

---

## 🧪 Pruebas Locales del Flujo
Para probar el flujo en local antes de desplegar:
1. En `web/.env`, coloca `DASHBOARD_PRO_AUTH_REQUIRED=true`.
2. Reinicia el servidor web FastAPI (`uvicorn app.main:app --reload --port 8088`).
3. En `activity_main.xml`, coloca temporalmente `android:visibility="visible"` en `cardSettingsDashboardPc`.
4. Abre la app en el emulador o teléfono, pulsa *"Generar Código de Acceso PC"*, copia el PIN y desbloquea en `http://localhost:8088/dashboard`.
