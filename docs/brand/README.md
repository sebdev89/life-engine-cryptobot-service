# Marca CryptoBot

**Decisión (2026-10-02): marca A.** El producto se llama **CryptoBot**; *Proof of Value* es el concepto
central y va como tagline: **CryptoBot — Proof of Value for Autonomous Agents**. La marca B (nodo con
dos anillos, `cryptobot-mark-alt.svg`) quedó **descartada** y se quitó del repo (sigue en el historial de git).

SVG a mano, sin fuentes remotas ni librerías.

| Archivo | Tamaño | Uso |
|---|---|---|
| `cryptobot-mark.svg` | 256×256 | Marca A (hexágono + check "proof"): favicon, nav de la UI, sello en el video |
| `png/cryptobot-mark-512.png` | 512×512 PNG | **Avatar / logo del proyecto en Colosseum Arena** y en cualquier formulario que pida imagen cuadrada |
| `cryptobot-wordmark-dark.svg` · `png/cryptobot-wordmark-dark.png` | 960×240 · 1920×480 PNG | Cabecera del README, portada/cierre del video, slides oscuros |
| `cryptobot-wordmark-light.svg` · `png/cryptobot-wordmark-light.png` | 960×240 · 1920×480 PNG | Formularios o páginas sobre fondo claro |

Exportar PNG (Chrome headless; `--force-device-scale-factor=2` duplica el tamaño del SVG):

```bash
google-chrome --headless=new --hide-scrollbars --force-device-scale-factor=2 --default-background-color=00000000 \
  --screenshot=$PWD/png/cryptobot-mark-512.png --window-size=256,256 file://$PWD/cryptobot-mark.svg
google-chrome --headless=new --hide-scrollbars --force-device-scale-factor=2 \
  --screenshot=$PWD/png/cryptobot-wordmark-dark.png --window-size=960,240 file://$PWD/cryptobot-wordmark-dark.svg
```

Para otro tamaño cuadrado (p. ej. 1024×1024) usar `--force-device-scale-factor=4`.

## Colores

| Rol | Dark | Light |
|---|---|---|
| Fondo | `#0b0f17` | `#ffffff` |
| Texto / trazo secundario | `#e6edf3` | `#0b0f17` |
| Acento | `#5eead4` | `#0d9488` (más oscuro, para contraste sobre blanco) |
| Subtítulo | `#8b98a9` | `#5b6675` |

Misma familia que `docs/architecture/trusted-agent-execution.svg`.

## Tipografía

Wordmark: sans del sistema, peso 800 (`Inter, 'Segoe UI', Helvetica, Arial, sans-serif`). Tagline y datos: mono del sistema
(`ui-monospace, 'SF Mono', Menlo, Consolas, monospace`). El texto del wordmark es texto SVG, no curvas: si se necesita
idéntico en todas partes, usar los PNG de `png/`.
