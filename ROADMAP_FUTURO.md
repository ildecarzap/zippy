# 🗺️ Roadmap Estratégico y Futuros Desarrollos: Zeasy POS
### Ciem Tech Solutions C.A — Hacia el Siguiente Nivel

Este documento recopila las ideas, mejoras y módulos estratégicos identificados durante la fase de lanzamiento para evolucionar **Zeasy** de un punto de venta móvil a una suite comercial integral para comercios en Venezuela y la región.

---

## ✅ Módulo Estratégico Implementado: Libreta de Clientes y Fiados (Micro-Ledger v1.3.5)
*Objetivo alcanzado: Digitalizar la libreta de fiados y cuentas por cobrar de bodegas, comercios y trabajadores independientes con enfoque zero-KYC y local-first.*

- [x] **Base de Datos SQLite v3 Relacional:** Tablas `debtors` y `ledger_entries` con eliminación en cascada e índices optimizados.
- [x] **Convención de Signos Unificada:** `balance_usd` negativo para deudas (`Debe`), positivo para vueltos pendientes (`A favor`) y cero para cuentas al día.
- [x] **Botón «Fiar» en Teclado de Caja Registradora:** Carga rápida de deudas al instante con creación y selección de clientes en un solo paso.
- [x] **Compensación Inteligente de Saldos:** Descuento automático de saldos a favor previos al realizar nuevas compras fiadas y acreditación automática de pagos excedentes.
- [x] **Cobro por WhatsApp con 1 Toque:** Redacción de recordatorio con cálculo en USD y Bs a tasa oficial BCV, datos de Pago Móvil y código QR adjunto mediante `FileProvider`.
- [x] **✏️ Edición Protegida de Datos del Cliente:** Modal `dialog_edit_debtor.xml` para actualizar nombre, teléfono y cédula/RIF desde el detalle sin perder el historial de movimientos.
- [x] **🗑️ Eliminación Segura con Alertas Contables:** Alerta preventiva con desglose del saldo adeudado en USD y Bs (tasa BCV) o crédito a favor antes de confirmar la eliminación.
- [x] **🛡️ Doble Capa de Seguridad (Huella o PIN):** Validación obligatoria con `BiometricPrompt` (huella/PIN del teléfono) y fallback a PIN de seguridad propio de Zeasy (`app_security_pin`, 1234 por defecto, modificable en Ajustes) para que nadie que tome el teléfono pueda manipular los datos.
- [x] **Reportes Contables y Respaldos:** Sección deudores/saldos a favor en cierre diario, exportación a CSV y respaldo JSON completo.
- [x] **Dashboard Web para PC:** Visor contable con KPIs de deudores, búsqueda reactiva, modal de movimientos y cobro vía WhatsApp Web.

---

## 💡 Estrategia Comercial y Monetización para la Economía Informal (Bodegas, Taxistas y Transporte)
*Objetivo: Rentabilizar Zeasy de forma sostenible sin provocar que los usuarios desinstalen la app por miedo a suscripciones fijas.*

### 1. La Regla de Oro: "El Caballo de Troya" (Cero Rehenes)
- **Cobrar nunca debe ser rehén:** Si a un bodeguero o taxista se le bloquea la caja en pleno cobro, desinstalará la app de inmediato.
- **Plan Básico 100% Gratuito e Ilimitado:** 1 banco activo, sonido de caja registradora auténtico, teclado financiero con conversión BCV oficial, QR en pantalla completa y libreta esencial. El comercio siempre podrá cobrar gratis.

### 2. Monetización por "Superpoderes" (Lo que hace ganar más plata o ahorra dolores de cabeza)
- **Multibanco Simultáneo (4 bancos):** Detección automática en paralelo (Banesco + BDV + Mercantil + Provincial) sin tener que cambiar de banco a mano.
- **Soundbox por Voz Inteligente (TTS):** El teléfono anuncia en voz alta por altavoz el monto y pagador, permitiendo atender la cola sin tocar la pantalla.
- **Billeteras Digitales y Cripto:** Binance Pay y Zinli con validación QR inteligente.
- **Respaldo en la Nube Automatizado:** Protección contra robo o pérdida de celular para restaurar toda la libreta de fiados y movimientos.
- **Reportes Contables en PDF:** Cierre de jornada formal para el contador.

### 3. Modelo Psicológico: "Pases Prepagados / Recargas" vs. "Renta Fija"
- En lugar de "suscripción fija mensual", se utiliza el concepto de **Recarga Telefónica / Pase de Temporada**:
  - **Pase Mensual:** $1.99 - $2.49 / mes (menos de $0.08 al día).
  - **Pase Trimestral:** $4.99 (ahorro para 3 meses).
  - **Pase Anual:** $14.99 - $19.99 / año (pago único para todo el año).
- **Vencimiento Amigable:** Si el pase expira, la app **NO se bloquea**. Pasa al modo Básico y sigue cobrando con su banco principal.

### 4. Flujo de Cobro sin Fricción con CobrixPay
- **Cobro 100% en Bolívares por Pago Móvil:** Pasarela CobrixPay con cálculo a tasa oficial BCV.
- **Activación en 3 Segundos:** El webhook de CobrixPay activa o renueva la licencia al instante en el backend y la app, sin requerir envío de capturas ni aprobación humana.
- **Códigos y Tarjetas Prepagadas:** Códigos alfanuméricos (`ZIP-30-XXXX`) canjeables en la app para venta física o mediante distribuidores aliados.

---

## 📌 Fase 1: Control de Empleados y Seguridad en Mostrador (Modo Cajero)
*Objetivo: Permitir que los dueños de negocios dejen el teléfono en caja con empleados sin temor a manipulaciones o fuga de información.*

- [x] **PIN de Seguridad para el Dueño (Master PIN):**
  - Implementado con doble soporte: Biometría nativa Android (`BiometricPrompt`) + PIN de seguridad Zeasy para proteger carga/borrado de QR bancarios, modificación de ajustes y edición/eliminación de clientes en la libreta.
- [ ] **Modo "Cajero Limitado":**
  - El cajero solo puede: Marcar montos, mostrar el QR y escuchar la confirmación por voz.
  - El cajero **no** puede ver el total recaudado en el día ni transferir o exportar datos.
- [ ] **Modo Kiosko / Bloqueo de Aplicación:**
  - Fijar Zeasy en primer plano para evitar que el personal use el dispositivo para otros fines en horario laboral.

---

## 🖨️ Fase 2: Hardware de Calle e Impresión Térmica
*Objetivo: Brindar soporte a comercios formales que entregan comprobantes físicos a sus clientes.*

- [ ] **Soporte para Miniprinters Bluetooth (58mm / 80mm):**
  - Conexión directa vía Bluetooth con impresoras térmicas estándar (ESC/POS).
  - Impresión automática o manual de comprobante de cobro Pago Móvil (Fecha, Hora, Banco, Referencia, Monto en Bs y equivalente en $).
- [ ] **Diseño de Ticket Personalizable:**
  - Nombre del comercio, RIF, dirección y mensaje de despedida en el pie de página.

---

## ☁️ Fase 3: Monitoreo Remoto para el Dueño (Sincronización Multi-Dispositivo)
*Objetivo: Que el dueño pueda monitorear sus ventas desde cualquier lugar sin estar físicamente en el local.*

- [ ] **Panel de Ventas en Vivo (Live Stream de Cobros):**
  - Visualización en tiempo real desde el teléfono del dueño (o desde la web de Zeasy Hub) de cada cobro confirmado en la caja.
- [ ] **Múltiples Cajas / Terminales por Comercio:**
  - Capacidad para que un negocio con 2 o 3 cajas reporte bajo la misma cuenta corporativa en Zeasy Hub.
- [ ] **Alertas Push para el Dueño:**
  - Notificación push al celular del dueño cada vez que la caja de su tienda recibe un pago superior a cierto monto.

---

## ☀️ Fase 4: Optimización para Venta Ambulante y Transporte
*Objetivo: Facilitar la adopción en taxistas, mototaxis, quioscos y mercados a cielo abierto.*

- [ ] **Modo Alto Contraste Exterior (Luz Solar Directa):**
  - Paleta visual con negros puros y amarillos de alta visibilidad para pantallas bajo el sol del mediodía.
- [ ] **Widget de Escritorio de 1 Toque:**
  - Widget para la pantalla de inicio de Android que despliega el QR y teclado de cobro en 1 segundo sin abrir la app completa.
- [ ] **Comandos de Voz Rápidos:**
  - Atajo rápido para dictar: *"Cobrar 5 dólares"* y que Zeasy abra la pantalla con el cálculo BCV listo.

---

## 💼 Fase 5: Expansión de Servicios con CobrixPay
*Objetivo: Convertir a Zeasy en un canal de generación de ingresos para los comercios.*

- [ ] **Cobro de Servicios Públicos y Recargas (Movistar, Digitel, Cantv):**
  - Integrar catálogo de recargas a través de la pasarela CobrixPay con comisión para el comercio.
- [ ] **Microcréditos y Financiamiento a Comercios:**
  - Utilizar el historial de volumen de ventas verificado por Zeasy para otorgar financiamiento o planes especiales de CobrixPay.
