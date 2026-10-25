import base64

LOGO_PATH = "/home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/web/app/static/images/zeasy-logo.png"
QR_PATH = "/home/ildemaro-carrasco/Ciem/cobrix-pay/zeasy-app/web/app/static/images/qr-whatsapp.png"

with open(LOGO_PATH, "rb") as f:
    logo_b64 = "data:image/png;base64," + base64.b64encode(f.read()).decode("utf-8")

with open(QR_PATH, "rb") as f:
    qr_b64 = "data:image/png;base64," + base64.b64encode(f.read()).decode("utf-8")

def get_quarter_front():
    return f"""
    <div class="q-card q-front">
      <!-- Header -->
      <div class="q-head">
        <div class="q-brand">
          <img src="{logo_b64}" class="q-logo" alt="Zeasy">
          <div>
            <div class="q-title">Zeasy</div>
            <div class="q-sub">Soundbox & Caja Registradora</div>
          </div>
        </div>
        <span class="q-badge">🔒 100% CERO CUSTODIA</span>
      </div>

      <!-- Hero -->
      <div class="q-hero">
        <div class="q-h1">¡Dile adiós a las <span>capturas falsas</span> y a las <span>colas</span>!</div>
        <div class="q-p">Tu celular Android cobra por ti: Canta cada Pago Móvil en voz alta al instante y cuadra tu caja con el BCV.</div>
      </div>

      <!-- 3 Pilares -->
      <div class="q-pillars">
        <div class="q-pil">
          <span class="q-ic">🗣️</span>
          <div>
            <strong>Soundbox con Voz y Campana:</strong>
            <span>Suena <em>"¡Ka-ching!"</em> y canta el monto y banco. ¡Verificación en 2 seg!</span>
          </div>
        </div>
        <div class="q-pil">
          <span class="q-ic">⚡</span>
          <div>
            <strong>Todos tus QR en Pantalla:</strong>
            <span>Banesco, BDV, Mercantil, BNC, Bancamiga o cobro por WhatsApp en 1 clic.</span>
          </div>
        </div>
        <div class="q-pil">
          <span class="q-ic">💵</span>
          <div>
            <strong>Tasa Oficial BCV y Arqueo:</strong>
            <span>Cierre de caja en Bs/USD y reportes diarios en Excel/PDF auditables.</span>
          </div>
        </div>
      </div>

      <!-- Promo / CTA -->
      <div class="q-cta">
        <div class="q-cta-txt">
          <span class="q-tag">⭐ 7 Días de Prueba Gratis</span>
          <div class="q-cta-h">¡Pruébalo con todo PRO!</div>
          <div class="q-cta-p">Luego solo <strong>$2.99 USD / mes</strong> en Bolívares por Pago Móvil. Sin comisiones.</div>
        </div>
        <div class="q-cta-qr">
          <div class="q-qr-wrap">
            <img src="{qr_b64}" alt="QR">
          </div>
          <div class="q-ws">
            <span>WhatsApp:</span>
            <strong>+58 424-2603112</strong>
          </div>
        </div>
      </div>
    </div>
    """

def get_quarter_back():
    return f"""
    <div class="q-card q-back">
      <!-- Header -->
      <div class="q-head">
        <div class="q-brand">
          <img src="{logo_b64}" class="q-logo" alt="Zeasy" style="width: 30px; height: 30px;">
          <div>
            <div class="q-title" style="font-size: 16px;">Zeasy • Instructivo</div>
            <div class="q-sub">Guía de Inicio Rápido (3 Minutos)</div>
          </div>
        </div>
        <span class="q-badge q-badge-amber">⚡ 3 Reglas de Oro</span>
      </div>

      <!-- 3 Puntos Críticos -->
      <div class="q-rules">
        <div class="q-rule r-red">
          <div class="qr-head">
            <span class="qr-n n-red">1</span>
            <strong>QRs de Bancos: ¡SIN MONTO!</strong>
          </div>
          <p>Descárgalos siempre <strong>sin monto configurado</strong> (monto abierto). Si le pones monto, siempre cobrará lo mismo a cada cliente.</p>
        </div>

        <div class="q-rule r-blue">
          <div class="qr-head">
            <span class="qr-n n-blue">2</span>
            <strong>Notificaciones de Bancos Activas</strong>
          </div>
          <p>Activa las alertas push de tus apps bancarias en Android. Zeasy las escucha para cantar el pago al segundo.</p>
        </div>

        <div class="q-rule r-green">
          <div class="qr-head">
            <span class="qr-n n-green">3</span>
            <strong>Mismo Número Celular y Bancos</strong>
          </div>
          <p>El teléfono con Zeasy debe tener el mismo número afiliado a Pago Móvil en tus bancos para recibir los pagos.</p>
        </div>
      </div>

      <!-- 4 Pasos -->
      <div class="q-steps-grid">
        <div class="qs-item">
          <span class="qs-num">1</span>
          <div>
            <strong>Configura</strong>
            <span>Sube tus QR sin monto.</span>
          </div>
        </div>
        <div class="qs-item">
          <span class="qs-num">2</span>
          <div>
            <strong>Cobra</strong>
            <span>Proyecta el QR en grande.</span>
          </div>
        </div>
        <div class="qs-item">
          <span class="qs-num">3</span>
          <div>
            <strong>Confirma</strong>
            <span>Escucha el monto cantado.</span>
          </div>
        </div>
        <div class="qs-item">
          <span class="qs-num">4</span>
          <div>
            <strong>Cierra</strong>
            <span>Arqueo a tasa BCV.</span>
          </div>
        </div>
      </div>

      <!-- Batería y Soporte -->
      <div class="q-footer-box">
        <div class="q-batt">
          <strong>⚡ Batería Android:</strong> Ajustes > Aplicaciones > Zeasy > Batería > <strong>"Sin Restricciones"</strong>.
        </div>
        <div class="q-tech">
          <span style="color: #475569;">WhatsApp Soporte:</span>
          <strong>+58 424-2603112</strong>
          <span style="font-size: 7.5px; color: #64748B;">Ciem Tech Solutions C.A</span>
        </div>
      </div>
    </div>
    """

html = f"""<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <title>Zeasy - Folletos 4 por Hoja (Cuarto de Carta)</title>
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
      line-height: 1.25;
      -webkit-print-color-adjust: exact;
      print-color-adjust: exact;
    }}
    .sheet {{
      width: 8.5in;
      height: 11in;
      max-height: 11in;
      position: relative;
      page-break-after: always;
      overflow: hidden;
      background: #F8FAF9;
    }}
    .sheet:last-child {{
      page-break-after: avoid;
    }}

    /* Grid 2x2 */
    .grid-4 {{
      width: 100%;
      height: 100%;
      display: grid;
      grid-template-columns: 4.25in 4.25in;
      grid-template-rows: 5.5in 5.5in;
    }}

    /* Líneas de Corte */
    .h-cut {{
      position: absolute;
      left: 0;
      right: 0;
      top: 5.5in;
      height: 0;
      border-top: 1.5px dashed #94A3B8;
      z-index: 10;
    }}
    .v-cut {{
      position: absolute;
      top: 0;
      bottom: 0;
      left: 4.25in;
      width: 0;
      border-left: 1.5px dashed #94A3B8;
      z-index: 10;
    }}
    .cut-badge-center {{
      position: absolute;
      top: calc(5.5in - 9px);
      left: calc(4.25in - 60px);
      width: 120px;
      height: 18px;
      background: #F8FAF9;
      border: 1px solid #CBD5E1;
      border-radius: 9px;
      font-size: 7.5px;
      font-weight: 800;
      color: #64748B;
      text-align: center;
      line-height: 16px;
      letter-spacing: 0.5px;
      z-index: 20;
      text-transform: uppercase;
    }}

    /* Cuadrante Individual */
    .quadrant {{
      width: 4.25in;
      height: 5.5in;
      padding: 0.12in 0.15in;
      display: flex;
      align-items: center;
      justify-content: center;
    }}

    .q-card {{
      width: 100%;
      height: 100%;
      background: #FFFFFF;
      border: 1.5px solid #CCDCD9;
      border-radius: 10px;
      padding: 11px 13px;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      box-shadow: 0 1px 4px rgba(0,0,0,0.03);
    }}

    /* Header Cuadrante */
    .q-head {{
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: 6px;
      border-bottom: 1.5px solid #E2E8E7;
    }}
    .q-brand {{
      display: flex;
      align-items: center;
      gap: 8px;
    }}
    .q-logo {{
      width: 32px;
      height: 32px;
      object-fit: contain;
      border-radius: 7px;
      background: #FFFFFF;
      padding: 2px;
      border: 1px solid #BCD9D6;
    }}
    .q-title {{
      font-size: 18px;
      font-weight: 900;
      color: #3D6B69;
      line-height: 1;
    }}
    .q-sub {{
      font-size: 8.5px;
      font-weight: 600;
      color: #647473;
      text-transform: uppercase;
      letter-spacing: 0.3px;
      margin-top: 1px;
    }}
    .q-badge {{
      background: #E6F0EE;
      color: #246A48;
      border: 1px solid #B7E8D0;
      padding: 2.5px 8px;
      border-radius: 12px;
      font-size: 8.5px;
      font-weight: 800;
      text-transform: uppercase;
    }}
    .q-badge-amber {{
      background: #FEF3C7;
      color: #92400E;
      border-color: #FCD34D;
    }}

    /* Hero */
    .q-hero {{
      background: linear-gradient(135deg, #EBF5F3 0%, #D8EDE7 100%);
      border: 1.2px solid #B4D5D1;
      border-radius: 8px;
      padding: 7px 10px;
      text-align: center;
      margin: 4px 0;
    }}
    .q-h1 {{
      font-size: 13.5px;
      font-weight: 900;
      color: #17302E;
      line-height: 1.22;
    }}
    .q-h1 span {{
      color: #3D6B69;
    }}
    .q-p {{
      font-size: 9px;
      color: #3B4E4D;
      margin-top: 3px;
      line-height: 1.32;
    }}

    /* 3 Pilares en Cuarto */
    .q-pillars {{
      display: flex;
      flex-direction: column;
      gap: 5px;
      margin: 3px 0;
    }}
    .q-pil {{
      background: #F8FAF9;
      border: 1px solid #DCE6E4;
      border-radius: 7px;
      padding: 6px 8px;
      display: flex;
      align-items: center;
      gap: 7px;
    }}
    .q-ic {{
      font-size: 15px;
      flex-shrink: 0;
    }}
    .q-pil strong {{
      font-size: 9.8px;
      color: #1F3837;
      display: block;
      line-height: 1.2;
    }}
    .q-pil span {{
      font-size: 8.5px;
      color: #4A5B5A;
      line-height: 1.25;
      display: block;
    }}

    /* Promo Cuarto */
    .q-cta {{
      background: linear-gradient(135deg, #1C3332 0%, #294A48 100%);
      border-radius: 8px;
      padding: 7px 10px;
      color: #FFFFFF;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
      margin-top: 3px;
    }}
    .q-cta-txt {{
      flex: 1;
    }}
    .q-tag {{
      background: #FCD34D;
      color: #78350F;
      font-size: 8px;
      font-weight: 900;
      text-transform: uppercase;
      padding: 1.5px 6px;
      border-radius: 6px;
      display: inline-block;
    }}
    .q-cta-h {{
      font-size: 11.5px;
      font-weight: 900;
      color: #FFFFFF;
      line-height: 1.15;
      margin: 1px 0;
    }}
    .q-cta-p {{
      font-size: 8.5px;
      color: #D1E5E3;
      line-height: 1.25;
    }}
    .q-cta-qr {{
      display: flex;
      align-items: center;
      gap: 7px;
    }}
    .q-qr-wrap {{
      width: 48px;
      height: 48px;
      background: #FFFFFF;
      border-radius: 6px;
      padding: 3px;
      flex-shrink: 0;
    }}
    .q-qr-wrap img {{
      width: 100%;
      height: 100%;
      object-fit: contain;
    }}
    .q-ws {{
      text-align: right;
    }}
    .q-ws span {{
      font-size: 7.5px;
      color: #D1E5E3;
      display: block;
    }}
    .q-ws strong {{
      font-size: 10.5px;
      color: #FCD34D;
      display: block;
      white-space: nowrap;
    }}

    /* REVERSO EN CUARTO */
    .q-rules {{
      display: flex;
      flex-direction: column;
      gap: 5px;
      margin: 3px 0;
    }}
    .q-rule {{
      border-radius: 7px;
      padding: 6px 8px;
      border: 1px solid transparent;
    }}
    .r-red {{ background: #FEF2F2; border-color: #FECACA; }}
    .r-blue {{ background: #EFF6FF; border-color: #BFDBFE; }}
    .r-green {{ background: #ECFDF5; border-color: #A7F3D0; }}
    .qr-head {{
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 2px;
    }}
    .qr-head strong {{
      font-size: 9.8px;
    }}
    .r-red strong {{ color: #991B1B; }}
    .r-blue strong {{ color: #1E40AF; }}
    .r-green strong {{ color: #065F46; }}
    .qr-n {{
      width: 16px;
      height: 16px;
      border-radius: 50%;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: 8.5px;
      font-weight: 900;
      color: #FFFFFF;
      flex-shrink: 0;
    }}
    .n-red {{ background: #DC2626; }}
    .n-blue {{ background: #2563EB; }}
    .n-green {{ background: #059669; }}
    .q-rule p {{
      font-size: 8.4px;
      color: #334155;
      line-height: 1.28;
      padding-left: 22px;
    }}

    /* 4 Pasos en Cuarto */
    .q-steps-grid {{
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 5px;
      margin: 3px 0;
    }}
    .qs-item {{
      background: #F8FAF9;
      border: 1px solid #DCE6E4;
      border-radius: 6px;
      padding: 5px 7px;
      display: flex;
      align-items: center;
      gap: 6px;
    }}
    .qs-num {{
      background: #17302E;
      color: #FFFFFF !important;
      width: 20px;
      height: 20px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 10.5px;
      font-weight: 900;
      line-height: 1;
      flex-shrink: 0;
      text-align: center;
    }}
    .qs-item strong {{
      display: block;
      font-size: 8.8px;
      color: #1F3837;
      line-height: 1.15;
    }}
    .qs-item span {{
      display: block;
      font-size: 7.8px;
      color: #556665;
      line-height: 1.2;
    }}

    /* Footer reverso en cuarto */
    .q-footer-box {{
      background: #EFF6FF;
      border: 1.5px solid #BFDBFE;
      border-radius: 7px;
      padding: 6px 9px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 7px;
      margin-top: 3px;
    }}
    .q-batt {{
      font-size: 8.2px;
      color: #1E40AF;
      line-height: 1.25;
      flex: 1;
    }}
    .q-tech {{
      text-align: right;
      font-size: 8.2px;
      color: #1E3A8A;
      white-space: nowrap;
      display: flex;
      flex-direction: column;
      align-items: flex-end;
    }}
    .q-tech strong {{
      font-size: 10px;
      color: #1E3A8A;
    }}
  </style>
</head>
<body>

  <!-- PÁGINA 1: FRENTE (4 CUADRANTES PUBLICIDAD) -->
  <div class="sheet">
    <div class="h-cut"></div>
    <div class="v-cut"></div>
    <div class="cut-badge-center">✂ 4 POR HOJA ✂</div>
    
    <div class="grid-4">
      <div class="quadrant">{get_quarter_front()}</div>
      <div class="quadrant">{get_quarter_front()}</div>
      <div class="quadrant">{get_quarter_front()}</div>
      <div class="quadrant">{get_quarter_front()}</div>
    </div>
  </div>

  <!-- PÁGINA 2: REVERSO (4 CUADRANTES INSTRUCTIVO) -->
  <div class="sheet">
    <div class="h-cut"></div>
    <div class="v-cut"></div>
    <div class="cut-badge-center">✂ 4 POR HOJA ✂</div>

    <div class="grid-4">
      <div class="quadrant">{get_quarter_back()}</div>
      <div class="quadrant">{get_quarter_back()}</div>
      <div class="quadrant">{get_quarter_back()}</div>
      <div class="quadrant">{get_quarter_back()}</div>
    </div>
  </div>

</body>
</html>
"""

output_html = "/home/ildemaro-carrasco/.gemini/antigravity-ide/brain/4752b171-ccd5-4c30-912d-1f3e06227368/scratch/cuarto_carta.html"
with open(output_html, "w", encoding="utf-8") as f:
    f.write(html)

print("HTML de 4 por hoja (cuarto de carta) v2 generado exitosamente.")
