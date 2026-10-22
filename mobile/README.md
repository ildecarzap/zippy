# ⚡ Zippy — Caja Inteligente y Asistente de Cobro Móvil

> **Nombre Oficial:** **Zippy**  
> **Versión:** 1.2.0 (Build 4) — Native Android (Kotlin).  
> **Identidad Visual:** 3D Brand Badge Oficial (Cyan Eléctrico & Deep Navy).

**Zippy** es una aplicación móvil nativa para Android diseñada específicamente para transportistas (taxis, mototaxis), pequeños comercios, bodegas, emprendedores y profesionales independientes en Venezuela y Latinoamérica. Transforma cualquier teléfono inteligente en un **punto de venta / caja registradora inteligente**, automatizando la validación de Pago Móvil, el cobro por WhatsApp con QR y el cuadre financiero de la jornada diaria en tiempo real.

---

## 🌟 Características Implementadas

### 1. Cobro Ágil en Caja
* **Teclado numérico financiero:** Entrada rápida en Bolívares (Bs.) o Dólares ($) con conversión bidireccional en tiempo real según la tasa BCV configurada.
* **Selector Rápido de Banco en Pantalla:** Botones/chips en la cabecera de caja para alternar al instante entre los bancos configurados (Banesco, BDV, Mercantil, Provincial, BNC, Bancamiga).
* **📸 Cobro en Persona (Modo Pantalla Completa):**
  * Despliega el código QR específico del banco seleccionado con brillo automático al 100%.
  * Tarjeta visual gigante con monto exacto a transferir, teléfono, banco, cédula/RIF y titular para escanear o copiar sin errores.
* **💬 Solicitud de Cobro por WhatsApp:**
  * Genera un mensaje formateado con los datos de Pago Móvil del banco activo y adjunta automáticamente la imagen del QR correspondiente.
  * Sin referencia en dólares en el mensaje al cliente para evitar confusiones.

### 2. Detección Inteligente Multibanco y Soundbox
* **Escucha en segundo plano (`PocketNotificationListenerService`):**
  * Lee notificaciones de las principales aplicaciones bancarias venezolanas y SMS bancarios.
  * Extrae monto exacto, banco emisor, nombre del pagador y número de referencia.
  * **Motor de deduplicación en memoria:** Evita registrar transacciones repetidas.
* **Soundbox Configurable (3 Modos):**
  * **🗣️ Voz (TTS):** Anuncia por el altavoz o radio Bluetooth: *"¡Pago recibido: 150 bolívares!"*.
  * **💰 Caja Registradora:** Sonido clásico y profesional de cobro exitoso.
  * **🔕 Silencioso:** Registro automático sin interrumpir llamadas o música.
  * **Botón de prueba:** Permite testear el volumen y sonido configurado al instante.

### 3. Cierre de Jornada y Libreta de Movimientos
* **Almacenamiento Local Permanente (SQLite):** Todos los movimientos se guardan de forma indefinida en el dispositivo, 100% offline y privado.
* **Balance Financiero Diario:**
  * Ganancia Neta en Bs. y USD: `Ganancia Neta = Ingresos - Gastos`.
  * Contador de cobros realizados y total de egresos operativos.
* **Filtros Rápidos en Pantalla:**
  * **[ Hoy ]:** Cuadre de caja de la jornada actual.
  * **[ Ayer ]:** Consulta inmediata de los cobros y gastos del día anterior.
  * **[ Todos ]:** Historial acumulado completo sin límites.
* **Registro de Movimientos Manuales:**
  * **Ingresos:** Efectivo Bs., Efectivo USD, Pago Móvil manual, Punto de venta / tarjeta, Transferencia.
  * **Gastos:** Gasolina, comida, repuestos, mantenimiento/taller, peajes y otros gastos.
  * **Edición y eliminación:** Permite corregir montos, notas o eliminar registros con actualización en vivo del balance.
* **🎙️ Carga de Movimientos por Voz:**
  * Reconocimiento nativo de voz en español (`es-VE`).
  * Reconoce automáticamente si es un ingreso o un gasto, monto numérico, moneda ($ o Bs.) y categoría (ej: *"Gasto 20 dólares de gasolina"* o *"Ingreso 150 bolívares en efectivo"*).
  * Diálogo de confirmación para validar o ajustar antes de registrar.

### 4. Generador de Reportes Contables y Auditoría
* **Reportes Formales en PDF (`ReportHelper`):**
  * Generación nativa con la API `android.graphics.pdf.PdfDocument`.
  * Nombre de archivo cronológico con fecha y hora de creación: `reporte_jornada_dd-MM-yy_HHmm.pdf`.
  * Encabezado con datos del negocio, rubro comercial, tasa BCV y firma de conformidad.
  * Desglose completo de ingresos y gastos categorizados.
  * **Número de Referencia Bancaria destacado** en cada fila para validar pagos reales en la banca en línea.
* **Filtros de Reporte:** Por día (Hoy, Ayer), Mes en curso o Rango de fechas personalizado con calendario.
* **Opciones de Compartir:** Vía WhatsApp, Correo Electrónico formal preconfigurado y apertura directa en visualizador PDF.

### 5. Gestor Multibanco y Recortador de QR
* **Configuración Unificada:** Carrusel interactivo para seleccionar cualquier banco nacional y gestionar sus parámetros en una sola tarjeta:
  * Subir y recortar imagen de QR exclusivo con resolución optimizada (`QrCropActivity`).
  * Previsualizar el QR guardado.
  * Activar/desactivar detección de pagos por entidad bancaria.
  * Definir banco principal predeterminado.
* **Soporte para SMS Bancarios:** Detección de pagos recibidos vía mensajería de texto.

### 6. Seguridad y Personalización
* **Seguridad Biométrica:** Bloqueo opcional con huella dactilar (`BiometricHelper`) al abrir la aplicación.
* **Modo Oscuro / Claro:** Selección manual o adaptación automática al tema del sistema operativo.
* **Rubro Comercial:** Selección entre más de 10 rubros (Transporte, Bodega, Restaurante, Farmacia, Taller, etc.) o rubro personalizado para que los reportes contables reflejen la actividad correcta.

---

## 🏗️ Arquitectura del Proyecto

```
com.cobrix.pocket/
├── data/
│   ├── db/
│   │   └── PocketDatabaseHelper.kt       # Base de datos SQLite local (permanente)
│   └── model/
│       ├── BankCatalog.kt                # Catálogo maestro de bancos nacionales y códigos
│       ├── BankInfo.kt                   # Modelo de entidad bancaria
│       ├── LicenseState.kt               # Estados de licencia y suscripción
│       ├── ParsedPayment.kt              # DTO de notificación bancaria parseada
│       ├── Transaction.kt                # Entidad de movimiento (ingresos, gastos, categorías)
│       └── UserProfile.kt                # Perfil del comerciante, banco principal y configuración
├── service/
│   ├── PocketNotificationListenerService.kt  # Interceptor de notificaciones bancarias
│   └── parser/                           # Parsers por banco con deduplicación
│       ├── BancamigaParser.kt
│       ├── BanescoParser.kt
│       ├── BdvParser.kt
│       ├── GenericSmsParser.kt
│       ├── MercantilParser.kt
│       ├── MultibankParserRouter.kt
│       └── ProvincialParser.kt
├── ui/
│   ├── FullScreenQrActivity.kt           # Pantalla gigante de QR y datos para cobro presencial
│   ├── MainActivity.kt                   # Controlador principal: Caja, Jornada, Ajustes y Voz
│   └── QrCropActivity.kt                 # Recortador de QR de alta resolución con vista previa
└── utils/
    ├── BiometricHelper.kt                # Autenticación biométrica nativa (BiometricPrompt)
    ├── LicenseManager.kt                 # Gestión de suscripción, telemetría y períodos de gracia
    ├── PreferencesHelper.kt              # Persistencia de ajustes, QRs por banco y tasas
    ├── ReportHelper.kt                   # Generador de reportes PDF nativos y exportación por email
    ├── SoundHelper.kt                    # Síntesis de voz (TTS) y efectos de sonido de caja
    └── WhatsAppHelper.kt                 # Generación de mensajes y despacho con QRs adjuntos
```

---

## 🏢 Información Corporativa y Soporte

* **Empresa Desarrolladora:** Ciem tech solutions C.A
* **RIF:** J-50849495-4
* **Correo de Contacto:** `ildemaro.carrasco@outlook.com`
* **Línea WhatsApp de Atención:** +58 4242603112
* **Sitio Web Oficial:** En desarrollo (Próximamente)

---

## 🚀 Compilación y Ejecución

```bash
# Navegar a la carpeta del proyecto
cd cobrix-pocket

# Asegurar JDK 17 o JDK 21 (Recomendado JBR 21)
export JAVA_HOME=/home/ildemaro-carrasco/.jdks/jbr-21.0.11

# Compilar APK de depuración
./gradlew assembleDebug

# El APK se genera en:
# app/build/outputs/apk/debug/app-debug.apk
```
