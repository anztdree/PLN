# Logo PLN Admin untuk laporan Excel — lingkaran navy + petir kuning
# (geometri sama dengan logo vektor di PDF supaya konsisten)
from PIL import Image, ImageDraw

S = 256
img = Image.new('RGB', (S, S), 'white')
d = ImageDraw.Draw(img)
cx, cy, r = 128, 128, 104
navy = (13, 27, 46)      # #0D1B2E
yellow = (255, 210, 63)  # #FFD23F

d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=navy)

# petir dari path SVG 24x24: M13 2 L4.5 13.5 H11 L9.5 22 L19 9.8 H12.4 Z
pts24 = [(13, 2), (4.5, 13.5), (11, 13.5), (9.5, 22), (19, 9.8), (12.4, 9.8)]
s = r * 1.55 / 20
pts = [(cx + (x - 11.75) * s, cy + (y - 12) * s) for x, y in pts24]
d.polygon(pts, fill=yellow)

img.save('logo-pln.jpg', quality=88)
print('OK logo-pln.jpg')
