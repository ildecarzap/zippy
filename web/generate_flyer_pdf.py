import base64
import os
import subprocess

LOGO_PATH = "/home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/web/app/static/images/zeasy-logo.png"
QR_PATH = "/home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/web/app/static/images/qr-whatsapp.png"

with open(LOGO_PATH, "rb") as f:
    logo_b64 = "data:image/png;base64," + base64.b64encode(f.read()).decode("utf-8")

with open(QR_PATH, "rb") as f:
    qr_b64 = "data:image/png;base64," + base64.b64encode(f.read()).decode("utf-8")

# Template para la mitad publicitaria (Frente)
def get_front_half():
    return f"""
    <div class="half-card front-card">
      <!-- Header -->
      <div class="h-header">
        <div class="brand-row">
          <img src="{logo_b64}" alt="Zeasy Logo" class="brand-img">
          <div>
            <div class="brand-name">Zeasy</div>
            <div class="brand-sub">Pocket POS & Soundbox Inteligente</div>
          </div>
        </div>
        <div class="header-right">
          <span class="pill pill-green">🔒 Cero Custodia • 100% Seguro</span>
          <div class="corp-txt">Ciem Tech Solutions C.A • RIF: J-50849495-4</div>
        </div>
      </div>

      <!-- Hero Banner -->
      <div class="hero-strip">
        <div class="hero-title">¡Dile adiós a las <span>capturas falsas</span> y a las <span>colas</span> en tu negocio!</div>
        <div class="hero-desc">Tu celular Android cobra por ti: Proyecta los QR de tus bancos, canta cada Pago Móvil en voz alta al instante y cuadra tu caja con la tasa oficial BCV.</div>
      </div>

      <!-- 3 Pilares Clave -->
      <div class="pillars-row">
        <div class="pillar">
          <div class="pillar-head">
            <span class="p-icon">🗣️</span>
            <strong>Soundbox con Voz y Campana</strong>
          </div>
          <p>Al caer el pago suena la campana <em>"¡Ka-ching!"</em> y una voz canta: <strong>"¡Pago de 450 Bs en Banesco!"</strong>. ¡Verificación en 2 segundos!</p>
        </div>
        <div class="pillar">
          <div class="pillar-head">
            <span class="p-icon">⚡</span>
            <strong>Todos tus QR en Pantalla</strong>
          </div>
          <p>Muestra tus QR bancarios (Banesco, BDV, Mercantil, BNC, Bancamiga) en pantalla gigante o envía los datos por WhatsApp en un toque.</p>
        </div>
        <div class="pillar">
          <div class="pillar-head">
            <span class="p-icon">💵</span>
            <strong>Tasa Oficial BCV y Arqueo</strong>
          </div>
          <p>Sincronización automática con la tasa oficial del BCV. Arqueos de caja en VES/USD y reportes descargables en Excel y PDF auditables.</p>
        </div>
      </div>

      <!-- Footer / Promo Oferta -->
      <div class="promo-strip">
        <div class="promo-left">
          <div class="promo-tag">⭐ Oferta de Lanzamiento</div>
          <div class="promo-title">7 Días de Prueba Gratis con todo activado</div>
          <div class="promo-sub">Luego solo <strong>$2.99 USD / mes</strong> pagando por Pago Móvil en Bolívares. ¡Sin comisiones por ventas!</div>
        </div>
        <div class="promo-right">
          <div class="qr-box">
            <img src="{qr_b64}" alt="QR WhatsApp">
          </div>
          <div class="contact-box">
            <span class="c-label">Escanea o escribe:</span>
            <strong>+58 424-2603112</strong>
          </div>
        </div>
      </div>
    </div>
    """

# Template para la mitad instructiva (Reverso)
def get_back_half():
    return f"""
    <div class="half-card back-card">
      <!-- Header -->
      <div class="h-header">
        <div class="brand-row">
          <img src="{logo_b64}" alt="Zeasy Logo" class="brand-img" style="width: 32px; height: 32px;">
          <div>
            <div class="brand-name" style="font-size: 16px;">Zeasy • Guía de Mostrador</div>
            <div class="brand-sub">Instructivo de Puesta en Marcha Rápida (3 Minutos)</div>
          </div>
        </div>
        <div class="header-right">
          <span class="pill pill-amber">⚠️ 3 Reglas de Oro Indispensables</span>
          <div class="corp-txt">Para dueños y personal de caja</div>
        </div>
      </div>

      <!-- Recuadro 3 Puntos Críticos -->
      <div class="rules-box">
        <div class="rules-grid">
          
          <div class="rule-item">
            <div class="rule-title rule-red">
              <span class="r-badge b-red">1</span>
              <strong>QRs de Bancos: ¡SIN MONTO!</strong>
            </div>
            <p class="rule-desc">Descarga los QR de tus bancos siempre <strong>sin monto configurado</strong> (monto abierto o en 0).</p>
            <div class="rule-why why-red"><strong>⚠️ ¿Por qué?</strong> Si le colocas monto al QR, quedará fijo y siempre cobrará lo mismo a cada cliente.</div>
          </div>

          <div class="rule-item">
            <div class="rule-title rule-blue">
              <span class="r-badge b-blue">2</span>
              <strong>Notificaciones Bancarias Activas</strong>
            </div>
            <p class="rule-desc">Activa en tu celular los <strong>permisos de notificaciones y alertas push</strong> de las apps de tus bancos.</p>
            <div class="rule-why why-blue"><strong>🔔 ¿Por qué?</strong> Zeasy escucha las notificaciones del banco para cantar el pago al segundo.</div>
          </div>

          <div class="rule-item">
            <div class="rule-title rule-green">
              <span class="r-badge b-green">3</span>
              <strong>Mismo Número en Celular y Bancos</strong>
            </div>
            <p class="rule-desc">El teléfono con Zeasy debe tener el <strong>mismo número afiliado a Pago Móvil</strong> en tus bancos.</p>
            <div class="rule-why why-green"><strong>📱 ¿Por qué?</strong> Las alertas bancarias llegan únicamente al equipo con la línea asociada.</div>
          </div>

        </div>
      </div>

      <!-- 4 Pasos Rápidos en Mostrador -->
      <div class="steps-row">
        <div class="step-mini">
          <div class="s-num">1</div>
          <div class="s-body">
            <strong>Configura y Sube QR</strong>
            <span>Coloca tu comercio, teléfono y activa tus bancos subiendo sus QR sin monto.</span>
          </div>
        </div>
        <div class="step-mini">
          <div class="s-num">2</div>
          <div class="s-body">
            <strong>Cobra en Pantalla</strong>
            <span>Toca el banco elegido y proyecta el QR en grande para que el cliente lo escanee.</span>
          </div>
        </div>
        <div class="step-mini">
          <div class="s-num">3</div>
          <div class="s-body">
            <strong>Confirmación Sonora</strong>
            <span>El Soundbox suena <em>"¡Ka-ching!"</em> y canta el monto. ¡Entrega sin pedir capturas!</span>
          </div>
        </div>
        <div class="step-mini">
          <div class="s-num">4</div>
          <div class="s-body">
            <strong>Cierre con Tasa BCV</strong>
            <span>Revisa tu total en Bs/USD a tasa oficial BCV y descarga tu reporte en Excel/PDF.</span>
          </div>
        </div>
      </div>

      <!-- Tip de Batería y Soporte -->
      <div class="bottom-support-strip">
        <div class="batt-note">
          <strong>⚡ Batería Android (Xiaomi/Samsung/Motorola):</strong> Ajustes > Apps > Zeasy > Batería > Selecciona <strong>"Sin Restricciones"</strong> para no dormir la app.
        </div>
        <div class="tech-contact">
          <span>Soporte WhatsApp: <strong>+58 424-2603112</strong></span>
          <span style="color: #647473; font-size: 8px;">Ciem Tech Solutions C.A • RIF: J-50849495-4</span>
        </div>
      </div>

    </div>
    """

cut_divider = """
<div class="cut-line">
  <div class="cut-text">✂ - - - - - - - - - - - - - - - - - - - - - - - - - CORTAR AQUÍ (MEDIA CARTA) - - - - - - - - - - - - - - - - - - - - - - - - - ✂</div>
</div>
"""

html = f"""<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <title>Zeasy - Folletos Compactos Media Carta</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@400;500;600;700;800;900&family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
  <style>
    @page {{
      size: letter portrait;
      margin: 0;
    }}
    * {{
      box-sizing: border-box;
      margin: 0;
      padding: 0;
    }}
    body {{
      font-family: 'Outfit', 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
      background-color: #F8FAF9;
      color: #273434;
      line-height: 1.25;
      -webkit-print-color-adjust: exact;
      print-color-adjust: exact;
    }}
    .sheet {{
      width: 8.5in;
      height: 11in;
      max-height: 11in;
      padding: 0.28in 0.38in;
      background: #F8FAF9;
      page-break-after: always;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      overflow: hidden;
      position: relative;
    }}
    .sheet:last-child {{
      page-break-after: avoid;
    }}

    /* Línea de corte */
    .cut-line {{
      height: 0.18in;
      display: flex;
      align-items: center;
      justify-content: center;
      position: relative;
      margin: 2px 0;
    }}
    .cut-line::before {{
      content: "";
      position: absolute;
      left: 0;
      right: 0;
      top: 50%;
      border-top: 1.5px dashed #A0AEC0;
      z-index: 1;
    }}
    .cut-text {{
      position: relative;
      z-index: 2;
      background: #F8FAF9;
      padding: 0 10px;
      font-size: 8px;
      font-weight: 700;
      color: #718096;
      letter-spacing: 1px;
      text-transform: uppercase;
    }}

    /* Tarjeta de Media Carta */
    .half-card {{
      height: 5.08in;
      max-height: 5.08in;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      background: #FFFFFF;
      border: 1px solid #DCE6E4;
      border-radius: 12px;
      padding: 12px 16px;
      box-shadow: 0 1px 4px rgba(0,0,0,0.03);
      overflow: hidden;
    }}

    /* Headers */
    .h-header {{
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: 6px;
      border-bottom: 1.5px solid #E2E8E7;
    }}
    .brand-row {{
      display: flex;
      align-items: center;
      gap: 9px;
    }}
    .brand-img {{
      width: 38px;
      height: 38px;
      object-fit: contain;
      border-radius: 8px;
      background: #FFFFFF;
      padding: 2px;
      border: 1px solid #BCD9D6;
    }}
    .brand-name {{
      font-size: 20px;
      font-weight: 900;
      color: #3D6B69;
      line-height: 1;
    }}
    .brand-sub {{
      font-size: 9px;
      font-weight: 600;
      color: #647473;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      margin-top: 2px;
    }}
    .header-right {{
      text-align: right;
    }}
    .pill {{
      display: inline-block;
      padding: 2px 9px;
      border-radius: 12px;
      font-size: 8.8px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.3px;
    }}
    .pill-green {{ background: #E6F0EE; color: #246A48; border: 1px solid #B7E8D0; }}
    .pill-amber {{ background: #FEF3C7; color: #92400E; border: 1px solid #FCD34D; }}
    .corp-txt {{
      font-size: 8.5px;
      color: #718096;
      margin-top: 2px;
    }}

    /* Hero en Frente */
    .hero-strip {{
      background: linear-gradient(135deg, #EBF5F3 0%, #DDEFEA 100%);
      border: 1px solid #BCD9D6;
      border-radius: 9px;
      padding: 9px 14px;
      text-align: center;
      margin: 6px 0;
    }}
    .hero-title {{
      font-size: 16px;
      font-weight: 900;
      color: #1A3331;
      line-height: 1.2;
    }}
    .hero-title span {{
      color: #3D6B69;
    }}
    .hero-desc {{
      font-size: 9.6px;
      color: #4A5B5A;
      margin-top: 3px;
      line-height: 1.35;
    }}

    /* 3 Pilares en Frente */
    .pillars-row {{
      display: grid;
      grid-template-columns: 1fr 1fr 1fr;
      gap: 8px;
      margin: 5px 0;
    }}
    .pillar {{
      background: #F8FAF9;
      border: 1px solid #E2E8E7;
      border-radius: 8px;
      padding: 8px 9px;
    }}
    .pillar-head {{
      display: flex;
      align-items: center;
      gap: 5px;
      margin-bottom: 4px;
    }}
    .pillar-head strong {{
      font-size: 10.5px;
      color: #1F3837;
      line-height: 1.2;
    }}
    .p-icon {{
      font-size: 13px;
    }}
    .pillar p {{
      font-size: 8.8px;
      color: #556665;
      line-height: 1.32;
    }}

    /* Promo y QR en Frente */
    .promo-strip {{
      background: linear-gradient(135deg, #1F3837 0%, #2A4C4A 100%);
      border-radius: 9px;
      padding: 7px 12px;
      color: #FFFFFF;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 10px;
    }}
    .promo-left {{
      flex: 1;
    }}
    .promo-tag {{
      background: #FCD34D;
      color: #78350F;
      font-size: 8px;
      font-weight: 900;
      text-transform: uppercase;
      padding: 1.5px 6px;
      border-radius: 8px;
      display: inline-block;
      margin-bottom: 2px;
    }}
    .promo-title {{
      font-size: 12.5px;
      font-weight: 900;
      line-height: 1.15;
      color: #FFFFFF;
    }}
    .promo-sub {{
      font-size: 8.8px;
      color: #D1E5E3;
      margin-top: 2px;
      line-height: 1.25;
    }}
    .promo-right {{
      display: flex;
      align-items: center;
      gap: 8px;
    }}
    .qr-box {{
      width: 48px;
      height: 48px;
      background: #FFFFFF;
      border-radius: 6px;
      padding: 3px;
      flex-shrink: 0;
    }}
    .qr-box img {{
      width: 100%;
      height: 100%;
      object-fit: contain;
    }}
    .contact-box {{
      text-align: right;
      font-size: 8.5px;
    }}
    .c-label {{
      display: block;
      color: #D1E5E3;
      font-size: 7.8px;
      text-transform: uppercase;
    }}
    .contact-box strong {{
      font-size: 10.5px;
      color: #FCD34D;
      display: block;
      white-space: nowrap;
    }}
    .c-web {{
      display: block;
      color: #A7C7C5;
      font-size: 7.8px;
    }}

    /* ESTILOS REVERSO (INSTRUCTIVO) */
    .rules-box {{
      background: linear-gradient(135deg, #FFFBEB 0%, #FEF3C7 100%);
      border: 1.5px solid #F59E0B;
      border-radius: 8px;
      padding: 7px 9px;
      margin: 5px 0;
    }}
    .rules-grid {{
      display: grid;
      grid-template-columns: 1fr 1fr 1fr;
      gap: 7px;
    }}
    .rule-item {{
      background: #FFFFFF;
      border: 1px solid #FCD34D;
      border-radius: 7px;
      padding: 6px 7px;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
    }}
    .rule-title {{
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 9.2px;
      margin-bottom: 2px;
    }}
    .rule-red strong {{ color: #991B1B; }}
    .rule-blue strong {{ color: #1E40AF; }}
    .rule-green strong {{ color: #065F46; }}
    .r-badge {{
      width: 17px;
      height: 17px;
      border-radius: 50%;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: 8.8px;
      font-weight: 900;
      flex-shrink: 0;
    }}
    .b-red {{ background: #FEE2E2; color: #DC2626; }}
    .b-blue {{ background: #DBEAFE; color: #2563EB; }}
    .b-green {{ background: #D1FAE5; color: #059669; }}
    .rule-desc {{
      font-size: 8.4px;
      color: #334155;
      line-height: 1.25;
      margin-bottom: 3px;
    }}
    .rule-why {{
      font-size: 7.8px;
      line-height: 1.22;
      padding: 3px 5px;
      border-radius: 4px;
      font-weight: 500;
    }}
    .why-red {{ background: #FEF2F2; color: #991B1B; border-left: 2px solid #EF4444; }}
    .why-blue {{ background: #EFF6FF; color: #1E40AF; border-left: 2px solid #3B82F6; }}
    .why-green {{ background: #ECFDF5; color: #065F46; border-left: 2px solid #10B981; }}

    /* Pasos Mini */
    .steps-row {{
      display: grid;
      grid-template-columns: 1fr 1fr 1fr 1fr;
      gap: 6px;
      margin: 4px 0;
    }}
    .step-mini {{
      background: #F8FAF9;
      border: 1px solid #E2E8E7;
      border-radius: 7px;
      padding: 6px 7px;
      display: flex;
      gap: 5px;
      align-items: flex-start;
    }}
    .s-num {{
      background: #3D6B69;
      color: #FFFFFF;
      width: 18px;
      height: 18px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 9.5px;
      font-weight: 900;
      flex-shrink: 0;
    }}
    .s-body strong {{
      display: block;
      font-size: 8.8px;
      color: #1F3837;
      line-height: 1.15;
      margin-bottom: 2px;
    }}
    .s-body span {{
      display: block;
      font-size: 7.8px;
      color: #556665;
      line-height: 1.22;
    }}

    /* Barra inferior reverso */
    .bottom-support-strip {{
      display: flex;
      justify-content: space-between;
      align-items: center;
      background: #EFF6FF;
      border: 1px solid #BFDBFE;
      border-radius: 7px;
      padding: 5px 10px;
      font-size: 8.2px;
      gap: 8px;
    }}
    .batt-note {{
      color: #1E40AF;
      line-height: 1.25;
      flex: 1;
    }}
    .tech-contact {{
      text-align: right;
      color: #1E3A8A;
      white-space: nowrap;
      display: flex;
      flex-direction: column;
      align-items: flex-end;
    }}
  </style>
</head>
<body>

  <!-- ==========================================
       PÁGINA 1: FRENTE (2 COPIAS PUBLICITARIAS)
       ========================================== -->
  <div class="sheet">
    <!-- Mitad Superior (Copia 1) -->
    {get_front_half()}

    <!-- Línea de Corte -->
    {cut_divider}

    <!-- Mitad Inferior (Copia 2 - Idéntica) -->
    {get_front_half()}
  </div>

  <!-- ==========================================
       PÁGINA 2: REVERSO (2 COPIAS INSTRUCTIVO)
       ========================================== -->
  <div class="sheet">
    <!-- Mitad Superior (Copia 1) -->
    {get_back_half()}

    <!-- Línea de Corte -->
    {cut_divider}

    <!-- Mitad Inferior (Copia 2 - Idéntica) -->
    {get_back_half()}
  </div>

</body>
</html>
"""

output_html = "/home/ildemaro-carrasco/.gemini/antigravity-ide/brain/4752b171-ccd5-4c30-912d-1f3e06227368/scratch/zeasy_folleto_compacto.html"
with open(output_html, "w", encoding="utf-8") as f:
    f.write(html)

print("HTML compacto generado exitosamente en:", output_html)
