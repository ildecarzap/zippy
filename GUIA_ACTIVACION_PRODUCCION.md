# 🚀 Guía de Retorno a Producción (Reactivación de Restricciones y Planes)

Esta guía explica cómo volver a activar las restricciones comerciales, banners de cobro y límites de plan una vez que **Google Play haya aprobado la aplicación Zeasy** y esté lista para publicarse al público comercial.

---

## 📌 Contexto de la Versión de Revisión (v1.3.3 / Build 8)

Durante el período de revisión en Google Play, la aplicación tiene activo el **Switch Maestro de Revisión**:
* `PLAY_STORE_REVIEW_MODE = true`
* Todas las funciones avanzadas (**Multibanco, Binance Pay, Soundbox de voz, Dictado de voz y Reportes PDF/Excel**) están 100% desbloqueadas.
* Se ocultaron todos los banners de pago, alertas de *"Tu prueba está por finalizar"* y botones de compra por WhatsApp para cumplir estrictamente con las políticas de Google Play y no ser observados por cobros externos.

---

## 🛠️ Paso a Paso para Reactivar Restricciones (Versión Comercial)

Cuando Google Play apruebe la app y decidas lanzar la versión comercial con cobros, sigue estos 3 simples pasos:

### 1️⃣ Apagar el Switch Maestro en el Código

Abre el archivo:
📄 [`mobile/app/src/main/java/com/cobrix/pocket/utils/PlanCapabilityManager.kt`](file:///home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/mobile/app/src/main/java/com/cobrix/pocket/utils/PlanCapabilityManager.kt)

Busca la línea 39 y cambia `true` por `false`:

```kotlin
// ==============================================================================
// 🚀 SWITCH MAESTRO DE REVISIÓN GOOGLE PLAY / EDICIÓN ABIERTA
// ==============================================================================
const val PLAY_STORE_REVIEW_MODE = false // 👈 CAMBIAR AQUÍ A FALSE
```

Al ponerlo en `false`:
* Vuelve la regla de **1 solo banco para el Plan Básico Gratuito**.
* Vuelve el período de prueba de **7 días gratis**.
* Vuelven los banners de alerta de renovación y el modal interactivo de suscripción PRO con [CobrixPay](file:///home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/DOCUMENTACION_ZEASY.md).
* Las funciones avanzadas (Voz, Multibanco, PDF, Binance Pay) volverán a requerir suscripción activa.

---

### 2️⃣ Subir la Versión en Gradle

Abre el archivo:
📄 [`mobile/app/build.gradle.kts`](file:///home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/mobile/app/build.gradle.kts)

Incrementa el número de compilación (`versionCode`) y el nombre de versión (`versionName`):

```kotlin
    defaultConfig {
        applicationId = "com.ciemtech.zeasy"
        minSdk = 24
        targetSdk = 36
        versionCode = 9          // 👈 Incrementar (ej. de 8 a 9)
        versionName = "1.3.4"    // 👈 Subir versión visible (ej. a 1.3.4)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
```

---

### 3️⃣ Generar el Nuevo Android App Bundle (.aab)

En la terminal o en Android Studio, compila el bundle firmado para producción:

```bash
cd mobile
./gradlew bundleRelease
```

El archivo generado estará ubicado en:
`mobile/app/build/outputs/bundle/release/app-release.aab`

---

### 4️⃣ Subir a Google Play Console

1. Entra a [Google Play Console](https://play.google.com/console).
2. Selecciona tu app **Zeasy**.
3. Ve a **Producción** (o Pruebas abiertas/cerradas según corresponda).
4. Crea una nueva versión, arrastra el nuevo `.aab` (versión 1.3.4 / 9) y publícala.

---

## 🔒 ¿Qué sucederá con los usuarios que instalaron la versión de prueba?

* **No pierden ningún dato:** Sus datos de negocio, teléfonos, cédulas y códigos QR que ya cargaron se mantendrán intactos.
* Al actualizarse a la nueva versión desde la Play Store, la app consultará el servidor y activará su período de prueba regular o les solicitará renovar si ya lo consumieron.
