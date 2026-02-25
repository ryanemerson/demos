#!/usr/bin/env python3
"""
Architecture diagram: SPIFFE/SPIRE + Keycloak + Quarkus on Kubernetes

Requirements:
    pip install Pillow

Usage:
    python3 docs/draw_arch.py

Output is written to /tmp/spiffe_architecture.jpg.
Copy it into the docs directory with:
    cp /tmp/spiffe_architecture.jpg docs/architecture.jpg
"""
from PIL import Image, ImageDraw, ImageFont
import math

W, H = 1440, 860
img = Image.new('RGB', (W, H), '#f0f4f8')
draw = ImageDraw.Draw(img)

# ── Fonts ──────────────────────────────────────────────────────────────────
def load_font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except Exception:
        return ImageFont.load_default()

SANS   = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
BOLD   = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'

f10  = load_font(SANS, 10)
f11  = load_font(SANS, 11)
f12  = load_font(SANS, 12)
f13  = load_font(BOLD, 13)
f14  = load_font(BOLD, 14)
f16  = load_font(BOLD, 16)
f20  = load_font(BOLD, 20)
f24  = load_font(BOLD, 24)

# ── Palette ────────────────────────────────────────────────────────────────
C_CLUSTER_BG     = '#dce8f7'
C_CLUSTER_BORDER = '#1565C0'

C_NS_BG     = '#fff8e1'
C_NS_BORDER = '#F57F17'

C_COMP_BG     = '#ffffff'
C_COMP_BORDER = '#546E7A'

C_SIDECAR_BG     = '#e8f5e9'
C_SIDECAR_BORDER = '#2E7D32'

C_ARROW  = '#37474F'
C_STEP   = '#B71C1C'
C_LABEL  = '#1a1a1a'

# ── Helpers ────────────────────────────────────────────────────────────────
def rrect(d, box, fill, outline, radius=8, width=2):
    x0, y0, x1, y1 = box
    d.rounded_rectangle([x0, y0, x1, y1], radius=radius,
                        fill=fill, outline=outline, width=width)

def centred(d, text, cx, cy, font, fill='#1a1a1a'):
    d.text((cx, cy), text, fill=fill, font=font, anchor='mm')

def multiline_centred(d, lines, cx, top_y, font, fill='#1a1a1a', spacing=16):
    for i, line in enumerate(lines):
        d.text((cx, top_y + i * spacing), line, fill=fill, font=font, anchor='mm')

def arrow(d, x0, y0, x1, y1, color=C_ARROW, width=2, head=10, bidirectional=False):
    """Draw a straight arrow from (x0,y0) to (x1,y1)."""
    d.line([(x0, y0), (x1, y1)], fill=color, width=width)
    # arrowhead at (x1,y1)
    angle = math.atan2(y1 - y0, x1 - x0)
    for sign in [1, -1]:
        ax = x1 - head * math.cos(angle - sign * math.pi / 6)
        ay = y1 - head * math.sin(angle - sign * math.pi / 6)
        d.line([(x1, y1), (ax, ay)], fill=color, width=width)
    if bidirectional:
        angle2 = math.atan2(y0 - y1, x0 - x1)
        for sign in [1, -1]:
            ax = x0 - head * math.cos(angle2 - sign * math.pi / 6)
            ay = y0 - head * math.sin(angle2 - sign * math.pi / 6)
            d.line([(x0, y0), (ax, ay)], fill=color, width=width)

def arrow_label(d, text, cx, cy, font=None, color='#37474F', bg='#f0f4f8'):
    if font is None:
        font = f10
    bbox = d.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    pad = 3
    d.rectangle([cx - tw//2 - pad, cy - th//2 - pad,
                 cx + tw//2 + pad, cy + th//2 + pad], fill=bg)
    d.text((cx, cy), text, fill=color, font=font, anchor='mm')

# ══════════════════════════════════════════════════════════════════════════
# Layout constants
# ══════════════════════════════════════════════════════════════════════════
MARGIN = 28

# ── Kubernetes Cluster outer box ──────────────────────────────────────────
CLU = [MARGIN, 50, W - MARGIN, H - 30]
rrect(draw, CLU, C_CLUSTER_BG, C_CLUSTER_BORDER, radius=14, width=3)
draw.text((W // 2, 34), 'Kubernetes Cluster', fill=C_CLUSTER_BORDER,
          font=f20, anchor='mm')

# ══════════════════════════════════════════════════════════════════════════
# NAMESPACES  (x0, y0, x1, y1)
# ══════════════════════════════════════════════════════════════════════════

# ns:spire  — left column
NS_SPIRE = [50, 78, 370, 520]
rrect(draw, NS_SPIRE, C_NS_BG, C_NS_BORDER, radius=10, width=2)
draw.text((210, 92), 'ns: spire', fill=C_NS_BORDER, font=f14, anchor='mm')

# ns:keycloak — centre column
NS_KC = [400, 78, 730, 340]
rrect(draw, NS_KC, C_NS_BG, C_NS_BORDER, radius=10, width=2)
draw.text((565, 92), 'ns: keycloak', fill=C_NS_BORDER, font=f14, anchor='mm')

# ns:client — right column, top
NS_CLI = [760, 78, 1090, 302]
rrect(draw, NS_CLI, C_NS_BG, C_NS_BORDER, radius=10, width=2)
draw.text((925, 92), 'ns: client', fill=C_NS_BORDER, font=f14, anchor='mm')

# ns:server — right column, bottom
NS_SRV = [760, 330, 1090, 530]
rrect(draw, NS_SRV, C_NS_BG, C_NS_BORDER, radius=10, width=2)
draw.text((925, 344), 'ns: server', fill=C_NS_BORDER, font=f14, anchor='mm')

# ══════════════════════════════════════════════════════════════════════════
# COMPONENTS
# ══════════════════════════════════════════════════════════════════════════

# ── SPIRE Server pod ──────────────────────────────────────────────────────
SS = [68, 108, 352, 228]
rrect(draw, SS, C_COMP_BG, C_COMP_BORDER, radius=6, width=2)
centred(draw, 'SPIRE Server', 210, 132, f13)
centred(draw, '(StatefulSet)', 210, 152, f11, '#546E7A')
# oidc-discovery-provider sidecar
OIDC = [80, 168, 340, 220]
rrect(draw, OIDC, C_SIDECAR_BG, C_SIDECAR_BORDER, radius=4, width=1)
centred(draw, 'oidc-discovery-provider  (sidecar)', 210, 194, f11, '#2E7D32')

# SPIRE Server cx/cy for arrows
SS_CX = 210;  SS_MID_Y = (SS[1] + SS[3]) // 2
SS_BOT = SS[3];  SS_TOP = SS[1]

# ── SPIRE Agent pod ───────────────────────────────────────────────────────
SA = [68, 268, 352, 388]
rrect(draw, SA, C_COMP_BG, C_COMP_BORDER, radius=6, width=2)
centred(draw, 'SPIRE Agent', 210, 294, f13)
centred(draw, '(DaemonSet — per node)', 210, 314, f11, '#546E7A')
centred(draw, 'Workload Attestation', 210, 336, f10, '#78909C')
centred(draw, 'k8s_psat + k8s', 210, 350, f10, '#78909C')

SA_CX = 210;  SA_TOP = SA[1];  SA_BOT = SA[3]
SA_MID_Y = (SA[1] + SA[3]) // 2
SA_RIGHT = SA[2]

# ── Workload API socket note (hostPath) ───────────────────────────────────
WA_NOTE = [68, 408, 352, 450]
rrect(draw, WA_NOTE, '#fce4ec', '#C62828', radius=4, width=1)
centred(draw, 'Workload API Socket  (hostPath)', 210, 429, f10, '#C62828')

# ── Host node note ────────────────────────────────────────────────────────
draw.rectangle([68, 458, 352, 512], fill='#eceff1', outline='#90A4AE', width=1)
centred(draw, 'Host Node', 210, 475, f12, '#455A64')
centred(draw, '/run/spire/socket/agent.sock', 210, 495, f10, '#607D8B')

# ── Keycloak pod ─────────────────────────────────────────────────────────
KC_POD = [418, 108, 712, 328]
rrect(draw, KC_POD, C_COMP_BG, C_COMP_BORDER, radius=6, width=2)
centred(draw, 'Keycloak', 565, 134, f13)
centred(draw, '(StatefulSet)', 565, 154, f11, '#546E7A')
# spiffe-helper sidecar — anchored to bottom of KC_POD
KCSH = [430, 260, 700, 320]
rrect(draw, KCSH, C_SIDECAR_BG, C_SIDECAR_BORDER, radius=4, width=1)
centred(draw, 'spiffe-helper  (sidecar)', 565, 274, f11, '#2E7D32')
draw.text((445, 288), '• svid.pem / svid_key.pem / bundle.pem', fill='#37474F', font=f10)
draw.text((445, 302), '↺ Certificates auto-rotate via Workload API', fill='#2E7D32', font=f10)

KC_LEFT = KC_POD[0];  KC_RIGHT = KC_POD[2]
KC_MID_Y = (KC_POD[1] + KC_POD[3]) // 2

# ── Quarkus Client pod ────────────────────────────────────────────────────
CLI_POD = [778, 108, 1072, 290]
rrect(draw, CLI_POD, C_COMP_BG, C_COMP_BORDER, radius=6, width=2)
centred(draw, 'Quarkus Client', 925, 130, f13)
centred(draw, '(Deployment)', 925, 148, f11, '#546E7A')
draw.text((793, 164), '• SPIFFE Workload API request SVID JWT', fill='#37474F', font=f10)

# spiffe-helper sidecar — anchored to bottom of CLI_POD
CLIPY = [790, 222, 1060, 282]
rrect(draw, CLIPY, C_SIDECAR_BG, C_SIDECAR_BORDER, radius=4, width=1)
centred(draw, 'spiffe-helper  (sidecar)', 925, 236, f11, '#2E7D32')
draw.text((805, 250), '• svid.pem / svid_key.pem / bundle.pem', fill='#37474F', font=f10)
draw.text((805, 264), '↺ Certificates auto-rotate via Workload API', fill='#2E7D32', font=f10)

CLI_LEFT = CLI_POD[0];  CLI_BOT = CLI_POD[3];  CLI_MID_X = 925
CLI_TOP = CLI_POD[1];   CLI_MID_Y = (CLI_POD[1] + CLI_POD[3]) // 2

# ── Quarkus Server pod ────────────────────────────────────────────────────
SRV_POD = [778, 358, 1072, 520]
rrect(draw, SRV_POD, C_COMP_BG, C_COMP_BORDER, radius=6, width=2)
centred(draw, 'Quarkus Server', 925, 382, f13)
centred(draw, '(Deployment)', 925, 402, f11, '#546E7A')
# spiffe-helper sidecar — anchored to bottom of SRV_POD
SRVSH = [790, 452, 1060, 512]
rrect(draw, SRVSH, C_SIDECAR_BG, C_SIDECAR_BORDER, radius=4, width=1)
centred(draw, 'spiffe-helper  (sidecar)', 925, 466, f11, '#2E7D32')
draw.text((805, 480), '• svid.pem / svid_key.pem / bundle.pem', fill='#37474F', font=f10)
draw.text((805, 494), '↺ Certificates auto-rotate via Workload API', fill='#2E7D32', font=f10)

SRV_LEFT = SRV_POD[0];  SRV_TOP = SRV_POD[1];  SRV_MID_Y = (SRV_POD[1] + SRV_POD[3]) // 2
SRV_MID_X = 925

# ══════════════════════════════════════════════════════════════════════════
# ARROWS
# ══════════════════════════════════════════════════════════════════════════
NAVY   = '#0D47A1'
GREEN  = '#1B5E20'
ORANGE = '#E65100'
RED    = '#B71C1C'
PURPLE = '#4A148C'

# 1. SPIRE Agent ↔ SPIRE Server  (gRPC 8081)
arrow(draw, SA_CX, SA_TOP, SS_CX, SS_BOT,
      color=NAVY, width=2, bidirectional=True)
arrow_label(draw, 'gRPC :8081', SA_CX + 30, (SA_TOP + SS_BOT) // 2, f10, NAVY)

# Sidecar midpoints for arrow targets
KCSH_LEFT = KCSH[0];  KCSH_MID_Y = (KCSH[1] + KCSH[3]) // 2
CLIPY_LEFT = CLIPY[0]; CLIPY_MID_Y = (CLIPY[1] + CLIPY[3]) // 2
SRVSH_LEFT = SRVSH[0]; SRVSH_MID_Y = (SRVSH[1] + SRVSH[3]) // 2

# 2. SPIRE Agent → Keycloak spiffe-helper (Workload API)
arrow(draw, SA_RIGHT, SA_MID_Y, KCSH_LEFT, KCSH_MID_Y, color=GREEN, width=2)
arrow_label(draw, 'Workload API  (socket)',
            (SA_RIGHT + KCSH_LEFT) // 2, (SA_MID_Y + KCSH_MID_Y) // 2 - 14, f10, GREEN)

# 3. SPIRE Agent → Quarkus Client (Workload API) — route via a bend
bx = 385
draw.line([(SA_RIGHT, SA_MID_Y + 20), (bx, SA_MID_Y + 20)], fill=GREEN, width=2)
draw.line([(bx, SA_MID_Y + 20), (bx, CLIPY_MID_Y)], fill=GREEN, width=2)
arrow(draw, bx, CLIPY_MID_Y, CLIPY_LEFT, CLIPY_MID_Y, color=GREEN, width=2)
arrow_label(draw, 'Workload API  (socket)',
            (bx + CLIPY_LEFT) // 2, CLIPY_MID_Y - 14, f10, GREEN)

# 4. SPIRE Agent → Quarkus Server (Workload API)
arrow(draw, SA_RIGHT, SA_MID_Y + 40, SRVSH_LEFT, SRVSH_MID_Y,
      color=GREEN, width=2)
arrow_label(draw, 'Workload API  (socket)',
            (SA_RIGHT + SRVSH_LEFT) // 2 + 20, (SA_MID_Y + 40 + SRVSH_MID_Y) // 2 + 10,
            f10, GREEN)

# 5. Keycloak → SPIRE Server oidc-discovery-provider  (JWKS / bundle endpoint)
#    from KC_POD top → SPIRE Server right
KC_TOP_CX = 565
SS_RIGHT  = NS_SPIRE[2]
SS_MID_Y_ACTUAL = (SS[1] + SS[3]) // 2
arrow(draw, KC_TOP_CX, KC_POD[1],
      SS_RIGHT, SS_MID_Y_ACTUAL, color=ORANGE, width=2)
arrow_label(draw, 'JWKS / bundle endpoint  (:443)',
            (KC_TOP_CX + SS_RIGHT) // 2, (KC_POD[1] + SS_MID_Y_ACTUAL) // 2 - 12,
            f10, ORANGE)

# ── OAuth 2.0 flow arrows ─────────────────────────────────────────────────
# Step ①  Client → Keycloak: JWT-SVID exchange (token endpoint)
KC_MID_X = 565
arrow(draw, CLI_LEFT, CLI_MID_Y - 50,
      KC_RIGHT, KC_MID_Y - 50, color=RED, width=2)
arrow_label(draw, '① POST /token  (client_credentials + jwt-spiffe assertion)',
            (CLI_LEFT + KC_RIGHT) // 2, CLI_MID_Y - 70, f10, RED)

# Step ② Keycloak → Client: access token response (dashed — draw as dots)
for xi in range(KC_RIGHT + 4, CLI_LEFT - 4, 14):
    draw.line([(xi, KC_MID_Y - 20), (xi + 7, KC_MID_Y-20)], fill=RED, width=2)
arrow(draw, CLI_LEFT - 1, KC_MID_Y - 20, CLI_LEFT, KC_MID_Y - 20, color=RED, width=2)
arrow_label(draw, '② access_token  (JWT)',
            (CLI_LEFT + KC_RIGHT) // 2, KC_MID_Y, f10, RED)

# Step ③ Client → Server: mTLS (SPIFFE)  downward
arrow(draw, CLI_MID_X, CLI_BOT, SRV_MID_X, SRV_TOP, color=PURPLE, width=2)
arrow_label(draw, '③ mTLS  (SPIFFE X.509)', CLI_MID_X + 56, (CLI_BOT + SRV_TOP - 15) // 2,
            f10, PURPLE)

# ══════════════════════════════════════════════════════════════════════════
# LEGEND / workflow panel  (bottom strip)
# ══════════════════════════════════════════════════════════════════════════
LEG = [50, 548, 1100, 690]
rrect(draw, LEG, '#ffffff', '#B0BEC5', radius=8, width=1)
centred(draw, 'Authentication Flow', 575, 564, f14, '#263238')

steps = [
    ("①", NAVY,   "SPIRE Agent attests workloads and distributes X.509-SVIDs & JWT-SVIDs via the Workload API socket."),
    ("②", GREEN,  "spiffe-helper (sidecar) writes PEM files to a shared emptyDir volume for Keycloak, Client, and Server."),
    ("③", RED,    "Quarkus Client fetches a JWT-SVID via java-spiffe, POSTs it to Keycloak's token endpoint (client_credentials + jwt-spiffe assertion)."),
    ("④", ORANGE, "Keycloak validates the JWT-SVID by fetching the JWKS from the SPIRE oidc-discovery-provider and issues an OAuth 2.0 access token."),
    ("⑤", PURPLE, "Quarkus Client presents the access token to Quarkus Server over a mutually authenticated TLS connection using SPIFFE X.509-SVIDs."),
]
for i, (num, col, text) in enumerate(steps):
    y = 588 + i * 20
    draw.text((68, y), num, fill=col, font=f12, anchor='lm')
    draw.text((88, y), text, fill='#263238', font=f10, anchor='lm')

# ══════════════════════════════════════════════════════════════════════════
# Title bar
# ══════════════════════════════════════════════════════════════════════════
draw.rectangle([0, 0, W, 32], fill='#1565C0')
centred(draw, 'SPIFFE / SPIRE  +  Keycloak  +  Quarkus  —  Architecture Overview',
        W // 2, 16, f16, '#FFFFFF')

# ══════════════════════════════════════════════════════════════════════════
# Legend key
# ══════════════════════════════════════════════════════════════════════════
LEG2 = [1110, 548, 1410, 690]
rrect(draw, LEG2, '#ffffff', '#B0BEC5', radius=8, width=1)
centred(draw, 'Key', 1260, 564, f14, '#263238')

key_items = [
    (NAVY,   'SPIRE internal  (gRPC)'),
    (GREEN,  'Workload API  (socket)'),
    (ORANGE, 'OIDC / JWKS  (HTTPS)'),
    (RED,    'OAuth 2.0 token exchange'),
    (PURPLE, 'Application mTLS  (SPIFFE)'),
    (C_SIDECAR_BORDER, 'spiffe-helper  (sidecar)'),
]
for i, (col, label) in enumerate(key_items):
    y = 586 + i * 18
    draw.rectangle([1125, y - 5, 1155, y + 5], fill=col, outline=col)
    draw.text((1163, y), label, fill='#263238', font=f10, anchor='lm')

# ══════════════════════════════════════════════════════════════════════════
# Trust-domain badge
# ══════════════════════════════════════════════════════════════════════════
TD = [1120, 78, 1410, 140]
rrect(draw, TD, '#e3f2fd', '#1565C0', radius=6, width=2)
centred(draw, 'Trust Domain', 1265, 98, f13, '#1565C0')
centred(draw, 'spiffe://demo.example.com', 1265, 120, f12, '#0D47A1')

# SPIRE bundle endpoint badge
BE = [1120, 155, 1410, 215]
rrect(draw, BE, '#fff3e0', '#E65100', radius=6, width=2)
centred(draw, 'Bundle Endpoint', 1265, 175, f13, '#E65100')
centred(draw, 'spire-server.spire.svc:443', 1265, 195, f12, '#BF360C')

# JWT audience badge
JA = [1120, 230, 1410, 290]
rrect(draw, JA, '#fce4ec', '#880E4F', radius=6, width=2)
centred(draw, 'JWT Audience', 1265, 250, f13, '#880E4F')
centred(draw, 'https://keycloak…/realms/spiffe', 1265, 270, f11, '#880E4F')

# Client assertion type badge
CA = [1120, 305, 1410, 375]
rrect(draw, CA, '#f3e5f5', '#4A148C', radius=6, width=2)
centred(draw, 'Client Assertion Type', 1265, 322, f13, '#4A148C')
draw.text((1130, 338), 'urn:ietf:params:oauth:client-assertion-type:jwt-spiffe', fill='#4A148C', font=f9 if (f9 := load_font(SANS, 9)) else f10)

# ── Save ───────────────────────────────────────────────────────────────────
out = '/tmp/spiffe_architecture.jpg'
img.save(out, 'JPEG', quality=95)
print(f'Saved: {out}  ({W}x{H})')
