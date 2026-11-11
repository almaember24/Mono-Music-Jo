# Prism's icons

Every icon in the app is drawn here, one SVG file per icon, named like its Kotlin name (`Home.svg` is
`PrismIcons.Home`). The build turns these files into `PrismIconData` (`generatePrismIcons` in `app/build.gradle.kts`),
so the app always shows exactly what's drawn here. Open them in any vector editor or browser.

## The grid

- 24 × 24 units, with the drawing kept inside 3 to 21 (2 to 22 for the widest shapes, strokes included).
- Lines are 1.5 units with round ends and round joins (the root's `stroke-width`). An element may set its own
  `stroke-width` (only `SearchBold` does).
- Shapes are circles, straight lines and arcs. Corners use one radius: 2 for large shapes, 1 to 1.5 for small ones.
- Play, pause, skip and the dots of a menu are filled; everything else is a line.

## What the build reads

Only `<path d>`, `<circle cx cy r>` and `<rect x y width height rx>`, plus:

| Where | Attribute | Meaning |
| --- | --- | --- |
| element | `class="f"` | Filled, no line. |
| element | `class="o"` | A line that the icon's selected twin fills. |
| root | `data-filled="tint"` | The icon has a selected twin (`NameFilled`) whose `o` shapes get a soft tint (tabs, toggles). |
| root | `data-filled="solid"` | The same, filled solid (a liked heart, a saved bookmark). |
| root | `data-mirror="true"` | Flipped in right-to-left languages (arrows, lists, volume). Play and skip never are. |

Anything else (another element, a transform, a gradient) fails the build with the file's name, rather than being
quietly dropped. The `<style>` line in each file only makes `class="f"` show filled in a browser.

## Looking at them

`IconRenderTest` draws every icon at 20, 24 and 32 dp in each weight (Thin, Regular, Bold), light and dark, onto
`app/build/icon-previews/`.
