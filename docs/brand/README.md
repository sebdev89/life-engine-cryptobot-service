# Marca CryptoBot (borrador)

Borrador para decisión de Sebastián. SVG a mano, sin fuentes remotas ni librerías.

| Archivo | Uso |
|---|---|
| `cryptobot-mark.svg` (256×256, hexágono + check "proof") | Avatar de Arena, favicon, sello en el video |
| `cryptobot-mark-alt.svg` (256×256, nodo con dos anillos) | Alternativa a la anterior; elegir UNA |
| `cryptobot-wordmark-dark.svg` (960×240) | Cabecera de README, portada/cierre del video, slides oscuros |
| `cryptobot-wordmark-light.svg` (960×240) | Formularios o páginas sobre fondo claro |

Para exportar PNG del avatar: `google-chrome --headless=new --force-device-scale-factor=2 --screenshot=out.png --window-size=256,256 file://$PWD/cryptobot-mark.svg`.

## Colores

| Rol | Dark | Light |
|---|---|---|
| Fondo | `#0b0f17` | `#ffffff` |
| Texto / trazo secundario | `#e6edf3` | `#0b0f17` |
| Acento | `#5eead4` | `#0d9488` (más oscuro, para contraste sobre blanco) |
| Subtítulo | `#8b98a9` | `#5b6675` |

Misma familia que `docs/architecture/trusted-agent-execution.svg`.

## Tipografía

Wordmark: sans del sistema, peso 800 (`Inter, 'Segoe UI', Helvetica, Arial, sans-serif`). Subtítulo y datos: mono del sistema (`ui-monospace, 'SF Mono', Menlo, Consolas, monospace`). El texto del wordmark es texto SVG, no curvas: si se necesita idéntico en todas partes, exportar a PNG.
