import base64

LOGO_PATH = "/home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/web/app/static/images/zeasy-logo.png"
QR_PATH = "/home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/web/app/static/images/qr-whatsapp.png"

with open(LOGO_PATH, "rb") as f:
    logo_b64 = "data:image/png;base64," + base64.b64encode(f.read()).decode("utf-8")

with open(QR_PATH, "rb") as f:
    qr_b64 = "data:image/png;base64," + base64.b64encode(f.read()).decode("utf-8")

def get_front():
    return f"""
    <div class="half-box front-box">
      <!-- Header -->
      <div class="h-header">
        <div class="brand-left">
          <img src="{logo_b64}" class="logo-img" alt="Zeasy">
          <div>
            <div class="brand-name">Zeasy</div>
            <div class="brand-sub">Caja Registradora & Soundbox Inteligente</div>
          </div>
        </div>
        <div class="brand-right">
          <span class="badge-tag">🔒 CERO CUSTODIA • 100% SEGURO</span>
          <div class="corp-name">Ciem Tech Solutions C.A • RIF: J-50849495-4</div>
        </div>
      </div>

      <!-- Hero Banner -->
      <div class="hero-box">
        <div class="hero-h1">¡Dile adiós a las <span>capturas falsas</span> y a las <span>colas</span> en tu negocio!</div>
        <div class="hero-p">Tu teléfono Android cobra por ti: Proyecta los QR de tus bancos, canta cada Pago Móvil con voz y campana al instante, y cuadra tu caja con la tasa oficial BCV.</div>
      </div>

      <!-- 3 Pilares -->
      <div class="pillars-container">
        <div class="p-card">
          <div class="p-head">
            <span class="p-emoji">🗣️</span>
            <strong>Soundbox con Voz y Campana</strong>
          </div>
          <p>Al caer el pago suena la campana <em>"¡Ka-ching!"</em> y una voz canta: <strong>"¡Pago de 450 Bs en Banesco!"</strong>. ¡Verificación en 2 segundos!</p>
        </div>

        <div class="p-card">
          <div class="p-head">
            <span class="p-emoji">⚡</span>
            <strong>Todos tus QR en Pantalla</strong>
          </div>
          <p>Muestra los QR de <strong>Banesco, BDV, Mercantil, BNC, Bancamiga</strong> en pantalla gigante o cobra por WhatsApp con un solo clic.</p>
        </div>

        <div class="p-card">
          <div class="p-head">
            <span class="p-emoji">💵</span>
            <strong>Tasa Oficial BCV y Arqueo</strong>
          </div>
          <p>Sincronización en tiempo real con la tasa del BCV. Arqueos de caja en VES/USD y reportes diarios en Excel y PDF auditables con tu banco.</p>
        </div>
      </div>

      <!-- Oferta / CTA -->
      <div class="cta-row">
        <div class="cta-left">
          <div class="cta-header-line">
            <span class="cta-pill">⭐ Oferta Especial</span>
            <span class="cta-h2">¡Pruébalo Gratis durante 7 Días!</span>
          </div>
          <div class="cta-sub">Disfruta de todas las funciones PRO sin pagar nada. Luego solo <strong>$2.99 USD / mes</strong> en Bolívares por Pago Móvil. ¡Sin comisiones por ventas!</div>
        </div>
        <div class="cta-right">
          <div class="qr-wrap">
            <img src="{qr_b64}" alt="QR WhatsApp">
          </div>
          <div class="cta-contact">
            <span class="c-sc">Escanea para activar:</span>
            <strong>+58 424-2603112</strong>
          </div>
        </div>
      </div>
    </div>
    """

def get_back():
    return f"""
    <div class="half-box back-box">
      <!-- Header -->
      <div class="h-header">
        <div class="brand-left">
          <img src="{logo_b64}" class="logo-img" alt="Zeasy" style="width: 44px; height: 44px;">
          <div>
            <div class="brand-name" style="font-size: 22px;">Zeasy • Guía de Mostrador</div>
            <div class="brand-sub">Instructivo de Puesta en Marcha Rápida</div>
          </div>
        </div>
        <div class="brand-right">
          <span class="badge-tag tag-amber">⚡ 3 REGLAS DE ORO OBLIGATORIAS</span>
          <div class="corp-name">Manual Operativo para Dueños y Cajeros</div>
        </div>
      </div>

      <!-- 3 Puntos Críticos -->
      <div class="rules-container">
        <div class="r-card">
          <div class="r-head r-head-red">
            <span class="r-num r-num-red">1</span>
            <strong>QRs de Bancos: ¡SIN MONTO!</strong>
          </div>
          <p class="r-text">Descarga los códigos QR de tus bancos siempre <strong>sin monto predefinido</strong> (monto abierto o en blanco).</p>
          <div class="r-why r-why-red"><strong>⚠️ ¿Por qué?</strong> Si le colocas monto al QR, quedará fijo y cobrará lo mismo a cada cliente.</div>
        </div>

        <div class="r-card">
          <div class="r-head r-head-blue">
            <span class="r-num r-num-blue">2</span>
            <strong>Notificaciones de Bancos Activas</strong>
          </div>
          <p class="r-text">Activa en tu celular los <strong>permisos de notificaciones push, alertas y banners</strong> de las apps de tus bancos.</p>
          <div class="r-why r-why-blue"><strong>🔔 ¿Por qué?</strong> Zeasy escucha las notificaciones del banco para cantar el pago al segundo.</div>
        </div>

        <div class="r-card">
          <div class="r-head r-head-green">
            <span class="r-num r-num-green">3</span>
            <strong>Mismo Número Celular y Bancos</strong>
          </div>
          <p class="r-text">El teléfono donde uses Zeasy debe tener el <strong>mismo número de teléfono afiliado a Pago Móvil</strong> en tus bancos.</p>
          <div class="r-why r-why-green"><strong>📱 ¿Por qué?</strong> Las confirmaciones bancarias llegan únicamente al equipo con la línea asociada.</div>
        </div>
      </div>

      <!-- 4 Pasos -->
      <div class="steps-container">
        <div class="s-card">
          <div class="s-badge">1</div>
          <div class="s-info">
            <strong>Configura tu Comercio</strong>
            <span>Ingresa tu negocio, teléfono y activa tus bancos subiendo sus QR sin monto.</span>
          </div>
        </div>

        <div class="s-card">
          <div class="s-badge">2</div>
          <div class="s-info">
            <strong>Cobra en Pantalla</strong>
            <span>Toca el banco elegido y proyecta el QR en grande para que el cliente lo escanee.</span>
          </div>
        </div>

        <div class="s-card">
          <div class="s-badge">3</div>
          <div class="s-info">
            <strong>Confirmación Sonora</strong>
            <span>El Soundbox suena <em>"¡Ka-ching!"</em> y canta el monto. ¡Entrega sin pedir captura!</span>
          </div>
        </div>

        <div class="s-card">
          <div class="s-badge">4</div>
          <div class="s-info">
            <strong>Cierre con Tasa BCV</strong>
            <span>Revisa el total cobrado en Bs/USD a tasa oficial BCV y descarga el reporte en Excel/PDF.</span>
          </div>
        </div>
      </div>

      <!-- Batería y Soporte -->
      <div class="support-footer">
        <div class="batt-info">
          <strong>⚡ Ajuste Vital de Batería (Xiaomi/Samsung/Motorola):</strong><br>
          Ajustes de celular > Aplicaciones > Zeasy > Batería > Selecciona <strong>"Sin Restricciones"</strong> para no dormir la app con pantalla apagada.
        </div>
        <div class="contact-info">
          <span style="font-size: 10px; color: #475569;">Atención & Soporte Técnico:</span>
          <strong>💬 WhatsApp: +58 424-2603112</strong>
          <span style="font-size: 9px; color: #64748B;">Ciem Tech Solutions C.A • RIF: J-50849495-4</span>
        </div>
      </div>
    </div>
    """

cut_bar = """
<div class="cut-bar">
  <div class="cut-label">✂ - - - - - - - - - - - - - - - - - - - - - - - CORTAR POR LA LÍNEA (MEDIA CARTA) - - - - - - - - - - - - - - - - - - - - - - - ✂</div>
</div>
"""

html = f"""<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <title>Zeasy - Media Carta Optimizado</title>
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
      font-family: 'Outfit', 'Inter', -apple-system, sans-serif;
      background-color: #F8FAF9;
      color: #273434;
      -webkit-print-color-adjust: exact;
      print-color-adjust: exact;
    }}
    .sheet {{
      width: 8.5in;
      height: 11in;
      max-height: 11in;
      padding: 0.18in 0.3in;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      page-break-after: always;
      position: relative;
      overflow: hidden;
    }}
    .sheet:last-child {{
      page-break-after: avoid;
    }}

    .cut-bar {{
      height: 0.16in;
      display: flex;
      align-items: center;
      justify-content: center;
      position: relative;
      margin: 0;
    }}
    .cut-bar::before {{
      content: "";
      position: absolute;
      left: 0;
      right: 0;
      top: 50%;
      border-top: 1.5px dashed #94A3B8;
      z-index: 1;
    }}
    .cut-label {{
      position: relative;
      z-index: 2;
      background: #F8FAF9;
      padding: 0 16px;
      font-size: 8.5px;
      font-weight: 800;
      color: #64748B;
      letter-spacing: 1px;
      text-transform: uppercase;
    }}

    /* Mitad de Hoja */
    .half-box {{
      height: 5.22in;
      max-height: 5.22in;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      background: #FFFFFF;
      border: 1.5px solid #CCDCD9;
      border-radius: 12px;
      padding: 14px 18px;
      box-shadow: 0 2px 6px rgba(0,0,0,0.03);
    }}

    /* Header */
    .h-header {{
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: 8px;
      border-bottom: 1.5px solid #E2E8E7;
    }}
    .brand-left {{
      display: flex;
      align-items: center;
      gap: 12px;
    }}
    .logo-img {{
      width: 48px;
      height: 48px;
      object-fit: contain;
      border-radius: 10px;
      background: #FFFFFF;
      padding: 2px;
      border: 1.5px solid #BCD9D6;
    }}
    .brand-name {{
      font-size: 26px;
      font-weight: 900;
      color: #3D6B69;
      line-height: 1;
    }}
    .brand-sub {{
      font-size: 11px;
      font-weight: 600;
      color: #647473;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      margin-top: 2px;
    }}
    .brand-right {{
      text-align: right;
    }}
    .badge-tag {{
      display: inline-block;
      padding: 4px 12px;
      border-radius: 16px;
      font-size: 10.5px;
      font-weight: 800;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      background: #E6F0EE;
      color: #246A48;
      border: 1px solid #B7E8D0;
    }}
    .tag-amber {{
      background: #FEF3C7;
      color: #92400E;
      border-color: #FCD34D;
    }}
    .corp-name {{
      font-size: 10px;
      color: #647473;
      margin-top: 3px;
    }}

    /* Hero */
    .hero-box {{
      background: linear-gradient(135deg, #EBF5F3 0%, #D8EDE7 100%);
      border: 1.5px solid #B4D5D1;
      border-radius: 10px;
      padding: 12px 18px;
      text-align: center;
      margin: 4px 0;
    }}
    .hero-h1 {{
      font-size: 20px;
      font-weight: 900;
      color: #17302E;
      line-height: 1.25;
    }}
    .hero-h1 span {{
      color: #3D6B69;
    }}
    .hero-p {{
      font-size: 12px;
      color: #3B4E4D;
      margin-top: 5px;
      line-height: 1.38;
      font-weight: 500;
    }}

    /* 3 Pilares */
    .pillars-container {{
      display: grid;
      grid-template-columns: 1fr 1fr 1fr;
      gap: 10px;
      margin: 4px 0;
    }}
    .p-card {{
      background: #F8FAF9;
      border: 1.5px solid #D5E2E0;
      border-radius: 9px;
      padding: 10px 12px;
    }}
    .p-head {{
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 6px;
    }}
    .p-head strong {{
      font-size: 12.5px;
      font-weight: 800;
      color: #1F3837;
      line-height: 1.2;
    }}
    .p-emoji {{
      font-size: 18px;
    }}
    .p-card p {{
      font-size: 11px;
      color: #445655;
      line-height: 1.38;
    }}

    /* CTA Row */
    .cta-row {{
      background: linear-gradient(135deg, #1C3332 0%, #294A48 100%);
      border-radius: 11px;
      padding: 11px 16px;
      color: #FFFFFF;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 14px;
      margin-top: 4px;
    }}
    .cta-left {{
      flex: 1;
    }}
    .cta-header-line {{
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 3px;
    }}
    .cta-pill {{
      background: #FCD34D;
      color: #78350F;
      font-size: 9.5px;
      font-weight: 900;
      text-transform: uppercase;
      padding: 2.5px 8px;
      border-radius: 8px;
      display: inline-block;
    }}
    .cta-h2 {{
      font-size: 17px;
      font-weight: 900;
      color: #FFFFFF;
      line-height: 1.2;
    }}
    .cta-sub {{
      font-size: 11.2px;
      color: #D1E5E3;
      line-height: 1.35;
    }}
    .cta-right {{
      display: flex;
      align-items: center;
      gap: 12px;
    }}
    .qr-wrap {{
      width: 66px;
      height: 66px;
      background: #FFFFFF;
      border-radius: 8px;
      padding: 4px;
      flex-shrink: 0;
      box-shadow: 0 2px 8px rgba(0,0,0,0.3);
    }}
    .qr-wrap img {{
      width: 100%;
      height: 100%;
      object-fit: contain;
    }}
    .cta-contact {{
      text-align: right;
    }}
    .c-sc {{
      display: block;
      color: #D1E5E3;
      font-size: 9px;
      text-transform: uppercase;
    }}
    .cta-contact strong {{
      font-size: 14px;
      color: #FCD34D;
      display: block;
      white-space: nowrap;
      margin: 1px 0;
    }}
    .c-url {{
      display: block;
      color: #B4D5D1;
      font-size: 9.5px;
    }}

    /* ESTILOS REVERSO */
    .rules-container {{
      display: grid;
      grid-template-columns: 1fr 1fr 1fr;
      gap: 10px;
      margin: 4px 0;
    }}
    .r-card {{
      background: #FFFBEB;
      border: 1.5px solid #FCD34D;
      border-radius: 9px;
      padding: 10px 11px;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
    }}
    .r-head {{
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 11.8px;
      margin-bottom: 5px;
    }}
    .r-head-red strong {{ color: #991B1B; }}
    .r-head-blue strong {{ color: #1E40AF; }}
    .r-head-green strong {{ color: #065F46; }}
    .r-num {{
      width: 22px;
      height: 22px;
      border-radius: 50%;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: 11px;
      font-weight: 900;
      flex-shrink: 0;
    }}
    .r-num-red {{ background: #FEE2E2; color: #DC2626; }}
    .r-num-blue {{ background: #DBEAFE; color: #2563EB; }}
    .r-num-green {{ background: #D1FAE5; color: #059669; }}
    .r-text {{
      font-size: 10.8px;
      color: #334155;
      line-height: 1.38;
      margin-bottom: 6px;
    }}
    .r-why {{
      font-size: 10px;
      line-height: 1.3;
      padding: 5px 7px;
      border-radius: 5px;
      font-weight: 600;
    }}
    .r-why-red {{ background: #FEF2F2; color: #991B1B; border-left: 3px solid #EF4444; }}
    .r-why-blue {{ background: #EFF6FF; color: #1E40AF; border-left: 3px solid #3B82F6; }}
    .r-why-green {{ background: #ECFDF5; color: #065F46; border-left: 3px solid #10B981; }}

    /* 4 Pasos */
    .steps-container {{
      display: grid;
      grid-template-columns: 1fr 1fr 1fr 1fr;
      gap: 8px;
      margin: 4px 0;
    }}
    .s-card {{
      background: #F8FAF9;
      border: 1.5px solid #D5E2E0;
      border-radius: 8px;
      padding: 8px 9px;
      display: flex;
      gap: 7px;
      align-items: flex-start;
    }}
    .s-badge {{
      background: #3D6B69;
      color: #FFFFFF;
      width: 24px;
      height: 24px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 12px;
      font-weight: 900;
      flex-shrink: 0;
    }}
    .s-info strong {{
      display: block;
      font-size: 11px;
      font-weight: 800;
      color: #1F3837;
      line-height: 1.2;
      margin-bottom: 2px;
    }}
    .s-info span {{
      display: block;
      font-size: 9.8px;
      color: #4A5B5A;
      line-height: 1.32;
    }}

    /* Footer soporte */
    .support-footer {{
      display: flex;
      justify-content: space-between;
      align-items: center;
      background: #EFF6FF;
      border: 1.5px solid #BFDBFE;
      border-radius: 9px;
      padding: 9px 15px;
      gap: 14px;
      margin-top: 4px;
    }}
    .batt-info {{
      font-size: 10.5px;
      color: #1E40AF;
      line-height: 1.38;
      flex: 1;
    }}
    .contact-info {{
      text-align: right;
      font-size: 10.8px;
      color: #1E3A8A;
      white-space: nowrap;
      display: flex;
      flex-direction: column;
      align-items: flex-end;
    }}
    .contact-info strong {{
      font-size: 13.5px;
      color: #1E3A8A;
      margin: 1px 0;
    }}
  </style>
</head>
<body>

  <!-- PÁGINA 1: FRENTE -->
  <div class="sheet">
    {get_front()}
    {cut_bar}
    {get_front()}
  </div>

  <!-- PÁGINA 2: REVERSO -->
  <div class="sheet">
    {get_back()}
    {cut_bar}
    {get_back()}
  </div>

</body>
</html>
"""

output_html = "/home/ildemaro-carrasco/.gemini/antigravity-ide/brain/4752b171-ccd5-4c30-912d-1f3e06227368/scratch/media_carta_optimizado.html"
with open(output_html, "w", encoding="utf-8") as f:
    f.write(html)

print("HTML optimizado con tipografía grande generado exitosamente.")
