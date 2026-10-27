# ⚡ Zeasy (Ecosistema Web & Mobile)

**Zeasy** es la solución ágil de cobros presenciales, caja rápida y soundbox digital para comerciantes, taxistas y emprendedores en Venezuela, desarrollado por **Ciem tech solutions C.A**.

---

## 📁 Estructura del Repositorio

```text
zeasy-app/
├── web/                  # Backend FastAPI, Landing Page y Panel Administrativo Central
│   ├── app/              # Modelos, routers, servicios de autenticación y webhooks
│   ├── templates/        # Vistas Jinja2 (Landing, Planes, Dashboard Admin con 1-clic)
│   ├── static/           # Estilos CSS modernos y recursos estáticos
│   ├── Dockerfile        # Contenedor optimizado para Cloud Run o VPS
│   └── requirements.txt  # Dependencias de Python
│
├── mobile/               # Aplicación Nativa Android (Kotlin)
│   ├── app/              # Código fuente nativo, arquitectura MVVM offline-first
│   │   ├── src/main/java/com/cobrix/pocket/
│   │   │   ├── data/     # Base de datos local SQLite y modelos
│   │   │   ├── service/  # Listener de notificaciones bancarias y parsers
│   │   │   ├── ui/       # MainActivity, FullScreen QR y animación de confetis
│   │   │   └── utils/    # Gestión de planes PRO/Básico, tasa BCV y Soundbox
│   └── build.gradle.kts  # Configuración Gradle Android
│
└── README.md
```

---

## 🚀 Componentes Principales

### 1. Aplicación Móvil (`mobile/`)
* **Cobro en Caja Rápida:** Teclado interactivo y conversión dinámica VES/USD a tasa BCV.
* **QR Bancario Pantalla Completa:** Muestra el QR con brillo al 100% tanto en el plan Básico como en PRO.
* **Seguridad Biométrica Protegida:** Validación por Huella Digital o PIN al cargar o modificar códigos QR y al guardar cambios de configuración en la cuenta.
* **Persistencia Reactiva (Anti-Pérdida de Datos):** Respaldo inmediato de datos escritos en Ajustes para evitar que se borren al conceder permisos del sistema.
* **Celebración con Confetis:** Pantalla animada personalizable al recibir pagos confirmados.
* **Detección Multibanco en Tiempo Real:** Enrutador con soporte para Banesco, BDV, Mercantil, Provincial, BNC y Bancamiga, además de SMS.
* **Soundbox Parlante:** Anuncio de cobros por voz o timbre de caja registradora.
* **Cierre de Jornada:** Registro de cobros, gastos y ganancia neta diaria.

### 2. Hub Web & Panel de Control (`web/`)
* **Landing Page & Planes:** Presentación de la app y planes para comerciantes.
* **Panel Administrativo Protegido:** Gestión de licencias en 1 clic:
  * Extender PRO (+30 días).
  * Degradar a Básico si no paga.
  * Suspender / Reactivar dispositivos.
  * Generar links de cobro y enviar recordatorios por WhatsApp.
* **API REST:** Endpoints para sincronización automática y verificación de licencias.
* **Base de Datos:** PostgreSQL en la nube (Supabase) con fallback local.

---

## 🛠️ Despliegue Rápido

### Web
```bash
cd web
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --host 0.0.0.0 --port 8088 --reload
```

### Mobile
Abre la carpeta `mobile/` en Android Studio y ejecuta la compilación en tu dispositivo o genera el APK release:
```bash
cd mobile
./gradlew assembleDebug
```

---

© 2026 **Ciem tech solutions C.A** • Todos los derechos reservados.
