# 🗺️ Hoja de Ruta y Roadmap de Evolución — Zippy

> **Estado del Proyecto:**  
> • Fase 1 (Core Funcional & Usabilidad): **100% Completada**  
> • Fase 2.1 (Branding Definitivo & Logo 3D Oficial — Zippy): **100% Completada**  
> • Fase 2.2 (Modelo Freemium, PlanCapabilityManager & Modal PRO): **100% Completada**  
> • Fase 2.3 (Copia de Seguridad, Migración entre Teléfonos y Cloud Sync): **100% Completada**  
> • Próximo Paso: Fase 3 (Micro-Backend de Sincronización Web de Tasa BCV y Panel Administrativo CIEM)

---

## 📌 1. Diagnóstico de lo Implementado

La aplicación móvil nativa Android **Zippy** cuenta con un núcleo de cobro, gestión, cuadre de caja y suscripción PRO completamente funcional, estable y probado:

* [x] **Identidad Oficial Zippy:**
  * Nombre oficial aprobado: **Zippy**.
  * Logotipo 3D oficial de alta resolución integrado en todos los mipmaps (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi) y adaptativo Android 13+.
  * Re-branding integral en strings, layouts, encabezados de reportes, servicios en segundo plano y diálogos.
* [x] **Cobro Rápido en Caja con Selector Dinámico de Bancos:**
  * Teclado financiero con conversión instantánea Bs. / USD.
  * Selector dinámico en cabecera para cambiar de banco con 1 toque.
  * **📸 QR Pantalla Completa:** Muestra el QR exclusivo y los datos del banco seleccionado con brillo al 100%.
  * **💬 Enviar por WhatsApp:** Envía solicitud de pago sin referencia en dólares y con el QR adjunto con 1 solo toque.
* [x] **Detección Automática Multibanco & Soundbox:**
  * Lectura de notificaciones push de los principales bancos nacionales (Banesco, BDV, Mercantil, Provincial, BNC, Bancamiga) y SMS.
  * Deduplicador en memoria y SQLite para evitar registros dobles.
  * 3 Modos de Soundbox: Voz (TTS), Caja Registradora y Silencioso con botón de prueba.
* [x] **Cierre de Jornada y Cuadre de Caja (Libreta Local Permanente):**
  * Base de datos local SQLite permanente y offline.
  * Ganancia neta real en Bs. y USD.
  * Filtros de vista rápida en pantalla: **[ Hoy ]**, **[ Ayer ]** y **[ Todos ]**.
  * Carga manual de ingresos y gastos categorizados con edición en vivo.
* [x] **🎙️ Carga Inteligente de Movimientos por Voz:**
  * Reconocimiento de voz nativo en español (`es-VE`).
  * Clasificación automática de tipo (ingreso vs. gasto), monto numérico, moneda y categoría.
* [x] **Generador de Reportes Contables y Auditoría:**
  * Reportes PDF nativos con estampa cronológica en nombre (`reporte_jornada_dd-MM-yy_HHmm.pdf`).
  * **Número de referencia bancaria destacado** en cada movimiento para validación en portal bancario.
  * Filtros por Hoy, Ayer, Mes o Rango personalizado con calendario.
  * Exportación vía WhatsApp, Correo Electrónico formal y visor PDF.
* [x] **👑 Modelo Freemium Zippy PRO & Blindaje de Plan Básico (`PlanCapabilityManager`):**
  * Todo usuario nuevo inicia **100% garantizado en Plan Básico Gratuito**: 1 solo banco activo predeterminado, cobro ilimitado, cierre de jornada y sonido de caja registradora auténtico.
  * Funciones PRO bloqueadas y protegidas hasta activar prueba o adquirir suscripción: Multibanco simultáneo (4 bancos), Exportación PDF formal con membrete y firma, Dictado por voz en jornada, y Soundbox parlante con síntesis de voz.
  * Encabezado interactivo con indicador en vivo del estado del plan: `⚡ Plan Básico • Toca para PRO` o `⭐ Prueba PRO (X d restantes)`.
  * Modal de Upgrade Zippy PRO (`dialog_pro_upgrade.xml`) con activación inmediata de **7 días de prueba gratis**, captura de lead por WhatsApp a Ciem tech solutions C.A y canje de código de activación.
* [x] **🔄 Sincronización Oficial de Tasa BCV (`BcvRateHelper`):**
  * Consulta en vivo de la tasa oficial del Banco Central de Venezuela en segundo plano.
  * Botón interactivo `🔄 BCV Oficial` en Ajustes para actualizar la tasa con 1 solo toque y autocalcular montos al instante.
* [x] **📲 Compartir Pagos por WhatsApp y Selector Nativo Seguro:**
  * Integración con `FileProvider` para compartir imágenes de códigos QR y datos de pago sin `FileUriExposedException`.
  * Apertura directa a chat con número de cliente o selector de contactos de WhatsApp para cobros rápidos en calle.
* [x] **☁️ Copia de Seguridad y Migración entre Dispositivos (`BackupManager` & `CloudSyncService`):**
  * Empaquetado completo de configuración, transacciones y códigos QR en formato JSON estructurado con codificación Base64.
  * Exportación directa para compartir a Google Drive, WhatsApp o correo.
  * Restauración de 1 clic desde cualquier archivo de respaldo para migrar sin pérdida de datos al cambiar de teléfono.
  * Protocolo de sincronización en la nube para cuentas Zippy PRO.
* [x] **Datos Corporativos Oficiales:**
  * Empresa: `Ciem tech solutions C.A`
  * RIF: `J-50849495-4`
  * Correo: `ildemaro.carrasco@outlook.com`
  * Teléfono WhatsApp: `+58 4242603112`
  * Sitio: `https://ciemtech.com` (Instructivo interactivo activo en `/guia`)

---

## 🚀 2. Fase 3: Backend, Panel Administrativo y Conexión Móvil (Completada ✅)

* [x] **Hub Web y Landing en Tonos Pasteles (`zippy-web`):**
  * Construido con FastAPI + Jinja2 en una carpeta 100% independiente de CobrixPay.
  * Homepage informativo (`/`) y manual interactivo paso a paso (`/guia`) para los comercios que ingresan desde la app.
  * Comparativa clara de Plan Básico ($0) vs. Zippy PRO ($2.99/mes) en `/planes`.
* [x] **Panel Administrativo Oculto y Seguro (`/portal-seguro-ciem/`):**
  * URL secreta personalizable para obscurecer el punto de acceso a atacantes.
  * Autenticación con cookies `HTTPOnly`, hasheo nativo `bcrypt` y soporte para Auth0 Universal Login.
  * Dashboard de control: visualización de dispositivos, métricas (PRO, Pruebas, Básicos) y buscador.
  * Activación remota en 1 clic (`⚡ +30d PRO`) y generador de enlaces de cobro CobrixPay ($2.99) enviados por WhatsApp.
* [x] **Pasarela de Cobros y Webhooks CobrixPay:**
  * Endpoint `/webhooks/cobrixpay` con verificación estricta de firma canónica HMAC-SHA256.
  * Renovación de plan PRO automática en menos de 50ms al confirmarse el Pago Móvil.
* [x] **Base de Datos PostgreSQL Local y Migración a Supabase:**
  * Conexión modularizada vía `DATABASE_URL` en `.env`.
  * Creación automática de tablas (`zippy_device_licenses`, `zippy_payment_transactions`, `zippy_admin_users`).
  * Compatible al 100% con Supabase Cloud.
* [x] **Sincronización en la App Android (`ZippySyncManager`):**
  * Registro automático del dispositivo (`device_id`, nombre de negocio, teléfono) al iniciar la app o guardar configuración.
  * Validación periódica y en segundo plano de la licencia PRO en el servidor.
  * Modo offline-first garantizado: si no hay internet, la app continúa operando con su último estado local.

---

## 🔮 3. Próximos Pasos (Fase 4: Sincronización de Datos en la Nube y Producción)

1. **Backup en la Nube Automatizado (Zippy Cloud Vault):**
   * Endpoint en `zippy-web` para recibir y almacenar instantáneas cifradas de transacciones y cierres de caja.
   * Restauración remota en caso de pérdida, robo o cambio de dispositivo al ingresar número de teléfono/RIF.
2. **Despliegue en la Nube:**
   * Conectar `zippy-web` a Supabase y desplegar en servidor VPS / Railway / Render con dominio oficial `ciemtech.com`.
3. **Generación del APK Release de Producción:**
   * Firma digital (Keystore) y optimizaciones ProGuard/R8 para distribución a los comercios.
