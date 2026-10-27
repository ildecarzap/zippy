# 🚀 Guía Definitiva de Publicación y Despliegue en Google Play Store
### Zeasy POS — Ciem Tech Solutions C.A

Esta guía detalla paso a paso el proceso estándar para preparar, firmar y publicar la aplicación móvil **Zeasy** en Google Play Console con éxito, cumpliendo con las políticas de Google Play de 2026.

---

## 📋 Requisitos Previos

1. **Cuenta de Desarrollador de Google Play Console:**
   - Registro en [play.google.com/console](https://play.google.com/console).
   - Pago único de registro de Google ($25 USD).
   - Verificación de identidad (DNI / RIF de la empresa Ciem Tech Solutions C.A).

2. **Android Studio** instalado y configurado con Java 17 o Java 21.

3. **Material Gráfico Requerido para la Ficha de la Tienda:**
   - **Ícono de la app:** 512 x 512 px (PNG de 32 bits con canal alfa).
   - **Gráfico de funciones (Banner):** 1024 x 500 px (JPG o PNG de 24 bits, sin transparencia).
   - **Capturas de pantalla del teléfono:** Mínimo 2 capturas (recomendado 4 a 6 capturas) en resolución mínima de 1080 x 1920 px (ratio 16:9 o 9:16).
     - *Captura 1:* Pantalla de cobro / Teclado numérico con cálculo en Bs y Tasa BCV.
     - *Captura 2:* Pantalla de QR Dinámico con selector Multibanco (Banesco, Mercantil, Provincial, Venezuela).
     - *Captura 3:* Cuadre de caja diario y reporte de ingresos/egresos.
     - *Captura 4:* Modo Soundbox y dictado por voz.

---

## 🛠️ Paso 1: Generar el Keystore de Firma de Producción (Release Keystore)

Para subir la app a Google Play, el paquete debe estar firmado criptográficamente.

### Opción A: Desde la Terminal (Recomendado)
Ejecuta el siguiente comando en tu máquina (guarda este archivo en un lugar seguro y NUNCA lo subas a un repositorio público):

```bash
keytool -genkeypair -v -keystore zeasy-release-key.jks \
  -alias zeasy-key-alias \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Ciem Tech Solutions, OU=Mobile Dev, O=Ciem Tech Solutions C.A, L=Caracas, ST=Miranda, C=VE"
```
- Define una contraseña segura para el Keystore (ejemplo: guárdala en un gestor de contraseñas).

### Opción B: Desde Android Studio
1. Ve a **Build** > **Generate Signed Bundle / APK...**
2. Selecciona **Android App Bundle (.aab)** y pulsa **Next**.
3. En *Key store path*, pulsa **Create new...**
4. Selecciona la carpeta donde guardarás `zeasy-release-key.jks`, define contraseñas y completa los datos de la empresa.

> [!CAUTION]
> **MUY IMPORTANTE:** Haz una copia de seguridad segura de `zeasy-release-key.jks` y de sus contraseñas (en Google Drive seguro, Dropbox o Bitwarden). Si pierdes esta llave, no podrás actualizar la app en Google Play nunca más.

---

## 📦 Paso 2: Configurar la Firma y Generar el Android App Bundle (.aab)

Google Play exige el formato **.aab** (Android App Bundle), no APK directo.

1. Verifica la versión en `mobile/app/build.gradle.kts`:
   ```kotlin
   defaultConfig {
       applicationId = "com.cobrix.pocket"
       minSdk = 24
       targetSdk = 34
       versionCode = 3      // Incrementar +1 en cada actualización
       versionName = "1.2.0"
   }
   ```

2. Genera el bundle de producción desde la terminal:
   ```bash
   cd zeasy-app/mobile
   ./gradlew bundleRelease
   ```
   *O desde Android Studio:* **Build** > **Generate Signed Bundle / APK** > **Android App Bundle** > Seleccionar tu Keystore > Marcar `release` > **Finish**.

3. El archivo generado estará ubicado en:
   `zeasy-app/mobile/app/build/outputs/bundle/release/app-release.aab`

---

## 📝 Paso 3: Crear la Aplicación en Google Play Console

1. Inicia sesión en [Google Play Console](https://play.google.com/console).
2. Haz clic en **Crear aplicación** (botón superior derecho).
3. Completa los datos iniciales:
   - **Nombre de la aplicación:** `Zeasy POS - Cobro Móvil y Caja` (o el nombre comercial preferido).
   - **Idioma predeterminado:** Español (Latinoamérica) - `es-419`.
   - **Tipo de aplicación:** Aplicación.
   - **Gratis o de pago:** Gratis (con compras/suscripción externa mediante CobrixPay).
   - Acepta las declaraciones de políticas de Google.

---

## 🛡️ Paso 4: Declaraciones de Políticas y Contenido de la App

En el menú lateral izquierdo, ve a **Contenido de la aplicación** (App Content) y completa cada cuestionario:

1. **Política de Privacidad (Privacy Policy):**
   - URL de la política: Puedes alojar la política en la web de Zeasy (ej: `https://zeasy-app-902301530733.europe-west1.run.app/terminos` o una página en Notion / GitHub Pages).
2. **Acceso a funciones / Credenciales de prueba:**
   - Selecciona: *"Todas las funciones están disponibles sin restricciones de inicio de sesión"* (ya que Zippy usa registro automático de dispositivo).
3. **Anuncios:**
   - Selecciona: *"No, mi aplicación no contiene anuncios"*.
4. **Público objetivo y contenido:**
   - Edad: 18 años o más (Comercios, taxistas, profesionales independientes).
5. **Aplicaciones financieras:**
   - Selecciona: *"Herramienta de punto de venta / registro contable / pagos comerciales"*.
6. **Seguridad de los datos (Data Safety):**
   - Declara qué datos recopila la app:
     - **Información del dispositivo:** ID del dispositivo (para validación de licencias).
     - **Información de contacto (Opcional):** Correo y teléfono si el comerciante los ingresa en ajustes.
     - **Datos financieros:** Registros locales de ventas y cobros (almacenados en el dispositivo).
     - Declara que los datos **están cifrados en tránsito (HTTPS)**.
7. **Permiso de Notificaciones Bancarias:**
   - Zippy incluye un servicio `NotificationListenerService` para lectura de confirmaciones SMS/Push de Pago Móvil.
   - En la justificación escribe:
     > *"La aplicación utiliza el acceso a notificaciones exclusivamente con autorización expresa del comerciante para detectar transferencias de Pago Móvil en tiempo real y anunciar el cobro por voz, evitando estafas con comprobantes falsos en comercios."*

---

## 🎨 Paso 5: Ficha Principal de la Tienda (Store Listing)

En el menú lateral, ve a **Presencia en Google Play Store** > **Ficha principal de la tienda**:

- **Nombre de la app:** `Zeasy POS - Pago Móvil & Caja`
- **Descripción corta (80 caracteres):**
  > `Punto de venta móvil con cálculo BCV, QR multibanco, soundbox y control de caja.`
- **Descripción completa:**
  ```text
  Zeasy es el punto de venta inteligente y caja registradora móvil diseñado para comerciantes, taxistas y profesionales independientes en Venezuela.

  ⚡ CARACTERÍSTICAS PRINCIPALES:
  • Teclado de cobro rápido con conversión inmediata Bolívares / Dólares a la tasa oficial BCV.
  • Generación de código QR Pago Móvil dinámico compatible con todos los bancos.
  • Soporte Multibanco simultáneo (Banesco, Mercantil, Provincial, BDV, Bancamiga y más).
  • Soundbox con voz parlante: Escucha el monto exacto de los pagos recibidos por altavoz o bluetooth.
  • Carga rápida de ingresos y gastos por dictado de voz.
  • Cuadre de caja diario y balance de jornada para un cierre contable sin errores.
  • Respaldo en la nube y exportación de reportes detallados en Excel y PDF.

  Seguro, liviano, sin comisiones ocultas y con funcionamiento garantizado incluso sin conexión.
  Desarrollado con orgullo por Ciem Tech Solutions C.A.
  ```
- **Recursos gráficos:** Sube el ícono (512x512), banner (1024x500) y las capturas de pantalla de tu teléfono.

---

## 🚀 Paso 6: Subir el Bundle y Lanzar

1. Ve a **Producción** (o primero a **Pruebas internas / Cerradas** si deseas que 2 o 3 personas de tu equipo lo prueben antes).
2. Haz clic en **Crear nueva versión**.
3. Sube el archivo `app-release.aab`.
4. En **Nombre de la versión**, ingresa: `1.2.0`.
5. En **Notas de la versión (es-419)**:
   ```text
   • Integración oficial con CobrixPay para renovación de planes.
   • Pantalla celebratoria y activación por código manual.
   • Cálculo automático de divisas a Tasa oficial BCV.
   • Mejoras de rendimiento y seguridad biométrica.
   ```
6. Haz clic en **Revisar versión** > **Iniciar lanzamiento a producción**.

---

## ⏳ Tiempos de Revisión de Google
- Si la cuenta de desarrollador es nueva, la primera revisión suele tardar entre **2 a 5 días hábiles**.
- Las actualizaciones posteriores (`1.2.1`, etc.) se aprueban normalmente en **menos de 24 horas**.
