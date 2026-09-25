# Jahia App Store icons

The icons of the Formidable modules on the Jahia App Store, one per module, in one family. They
are uploaded on each module's entry of the store (the `icon` of the entry, shown in **Modules
and Extensions > Available modules** in Jahia); the entries currently show the generic "Forms"
tiles. Nothing in the modules reads these files.

| Module | Tile | Glyph |
|---|---|---|
| formidable-elements | ![](formidable-elements.png) | a form: its label, its field (blue), its button |
| formidable-engine | ![](formidable-engine.png) | a gear, its hub blue |
| formidable-extended-inputs | ![](formidable-extended-inputs.png) | three sliders |
| formidable-jexperience-engine | ![](formidable-jexperience-engine.png) | an activity pulse |

## The family

- The palette of Formidable's content-type icons (`fmdb:form`, `fmdb:fieldList`,
  `fmdb:formReference`, `fmdb:actionList`…) — slate `#1F2937`, one accent in Jahia blue
  `#00A0E3` — turned into a store tile: the 125 × 125 rounded square (radius 22) is the slate,
  the strokes are white, the accent stays blue.
- The glyph: drawn on a 24-unit grid like every Formidable content-type icon (Lucide
  conventions: round caps and joins, no fill), scaled 4.2 times and centred, with strokes of 2.4
  (3 for the blue accent) rather than 2: the modules list of Jahia shows the tile at about 40
  pixels, where a thinner or smaller glyph blurs away. Hand-drawn SVG, no icon library.

## Regenerating a PNG from its SVG

The SVG is the source; the PNG is what the store takes.

```bash
cd icons/store
convert -background none -density 384 formidable-elements.svg -resize 125x125 formidable-elements.png
```
