import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

w, h = 1024, 500
banner = Image.new('RGBA', (w, h), (0, 0, 0, 255))

# Gradiente de fondo verde salvia profundo
for y in range(h):
    factor_y = y / h
    for x in range(w):
        factor_x = x / w
        r = int(16 + 10 * factor_x + 6 * factor_y)
        g = int(34 + 18 * factor_x + 10 * factor_y)
        b = int(32 + 16 * factor_x + 8 * factor_y)
        banner.putpixel((x, y), (r, g, b, 255))

draw = ImageDraw.Draw(banner)

# Fuentes del sistema
font_tag = ImageFont.truetype('/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf', 13)
font_brand = ImageFont.truetype('/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf', 58)
font_title = ImageFont.truetype('/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf', 24)
font_sub = ImageFont.truetype('/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf', 16)
font_bullet = ImageFont.truetype('/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf', 14)

# 1. Tag superior
tag_text = 'PUNTO DE VENTA & SOUNDBOX INTELIGENTE'
t_bbox = font_tag.getbbox(tag_text)
tw = t_bbox[2] - t_bbox[0] + 28
th = 28
draw.rounded_rectangle([60, 42, 60 + tw, 42 + th], radius=14, fill=(35, 68, 65), outline=(78, 135, 130), width=1)
draw.text((60 + 14, 42 + 6), tag_text, fill=(188, 230, 224), font=font_tag)

# 2. Logo e Identidad
logo_path = 'web/app/static/images/zeasy-logo.png'
if os.path.exists(logo_path):
    logo = Image.open(logo_path).convert('RGBA')
    logo = logo.resize((96, 96), Image.Resampling.LANCZOS)
    banner.paste(logo, (60, 88), logo)

draw.text((172, 92), 'Zeasy', fill=(255, 255, 255), font=font_brand)
draw.text((174, 156), 'Caja Registradora & Cobro Móvil', fill=(195, 225, 220), font=font_title)

# 3. Subtítulo explicativo
draw.text((60, 206), 'Confirma tus cobros de Pago Móvil al instante con campana y voz.', fill=(230, 242, 240), font=font_sub)
draw.text((60, 232), 'Evita estafas con capturas falsas y agiliza la atención en tu mostrador.', fill=(165, 198, 194), font=font_sub)

# 4. Badges con Iconos de Check Vectoriales Perfectos (Sin caracteres rotos)
features = [
    ('Cero Custodia de Dinero', 60, 288),
    ('Detección por Voz en Tiempo Real', 285, 288),
    ('Tasa Oficial del BCV al Día', 60, 342),
    ('Cierres y Arqueo de Jornada', 285, 342),
    ('QR Multibanco (Banesco, BDV, BNC) & Binance', 60, 396),
]

for text, fx, fy in features:
    bbox = font_bullet.getbbox(text)
    bw = bbox[2] - bbox[0] + 48
    bh = 38
    # Fondo con borde elegante
    draw.rounded_rectangle([fx, fy, fx + bw, fy + bh], radius=10, fill=(26, 52, 50), outline=(58, 104, 99), width=1)
    
    # Check vectorial verde brillante
    cx, cy = fx + 12, fy + 10
    draw.ellipse([cx, cy, cx + 18, cy + 18], fill=(36, 120, 100), outline=(85, 195, 165), width=1)
    draw.line([(cx + 4, cy + 9), (cx + 8, cy + 13), (cx + 14, cy + 5)], fill=(255, 255, 255), width=2)
    
    # Texto
    draw.text((fx + 38, fy + 10), text, fill=(240, 250, 248), font=font_bullet)

# 5. Teléfonos a la derecha
soundbox_p = 'web/app/static/images/app-guide/guia-5-ajustes-soundbox.png'
cobrar_p = 'web/app/static/images/app-guide/guia-1-cobrar.png'

# Teléfono trasero (Ajustes Soundbox)
if os.path.exists(soundbox_p):
    img_back = Image.open(soundbox_p).convert('RGBA')
    bw = 195
    bh = int(bw * (img_back.size[1] / img_back.size[0]))
    img_back_resized = img_back.resize((bw, bh), Image.Resampling.LANCZOS)
    cropped_back = img_back_resized.crop((0, 0, bw, min(430, bh)))
    
    # Sombra
    s_back = Image.new('RGBA', (bw + 30, 430 + 30), (0, 0, 0, 0))
    ImageDraw.Draw(s_back).rounded_rectangle([15, 15, bw + 15, 430 + 15], radius=20, fill=(0, 0, 0, 130))
    s_back = s_back.filter(ImageFilter.GaussianBlur(12))
    banner.paste(s_back, (810, 35), s_back)
    
    # Marco y contenido
    m_back = Image.new('L', (bw, 430), 0)
    ImageDraw.Draw(m_back).rounded_rectangle([0, 0, bw, 430], radius=20, fill=255)
    banner.paste(cropped_back, (825, 50), m_back)
    draw.rounded_rectangle([825, 50, 825 + bw, 50 + 430], radius=20, outline=(60, 100, 95), width=2)

# Teléfono frontal (Cobro Principal)
if os.path.exists(cobrar_p):
    img_front = Image.open(cobrar_p).convert('RGBA')
    fw = 225
    fh = int(fw * (img_front.size[1] / img_front.size[0]))
    img_front_resized = img_front.resize((fw, fh), Image.Resampling.LANCZOS)
    cropped_front = img_front_resized.crop((0, 0, fw, min(460, fh)))
    
    # Sombra intensa frontal
    s_front = Image.new('RGBA', (fw + 40, 460 + 40), (0, 0, 0, 0))
    ImageDraw.Draw(s_front).rounded_rectangle([20, 20, fw + 20, 460 + 20], radius=22, fill=(0, 0, 0, 170))
    s_front = s_front.filter(ImageFilter.GaussianBlur(16))
    banner.paste(s_front, (640, 20), s_front)
    
    # Máscara y contenido
    m_front = Image.new('L', (fw, 460), 0)
    ImageDraw.Draw(m_front).rounded_rectangle([0, 0, fw, 460], radius=22, fill=255)
    banner.paste(cropped_front, (660, 40), m_front)
    
    # Marco moderno del smartphone
    draw.rounded_rectangle([660, 40, 660 + fw, 40 + 460], radius=22, outline=(95, 155, 148), width=3)

output_path = 'google_play_assets/grafico_funciones_1024x500.png'
banner.convert('RGB').save(output_path, 'PNG')
print(f'Banner guardado en {output_path}')
