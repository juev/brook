# Screenshots

The screenshots in the README come from the rendered screen, not from a mock-up. The temporary
workflow `.github/workflows/flat-navigation-proof.yml` runs `FeedNavigationTest` on an emulator and
publishes them as the `flat-navigation-device-proof` artifact, under `evidence/screenshots/`.

| README file | Artifact | What it shows |
|---|---|---|
| `compact-toolbar-light.png` | `compact-toolbar-light.png` | The compact top bar: mode button, title trigger, bulk read, overflow |
| `compact-picker-light.png` | `compact-picker-light.png` | The title-triggered flat feed picker |
| `compact-toolbar-dark.png` | `compact-toolbar-dark.png` | The same bar in the dark theme |
| `compact-picker-dark.png` | `compact-picker-dark.png` | The picker in the dark theme |
| `large-layout.png` | `large-layout.png` | The permanent flat pane beside the list on a large window |
| — | `large-font-duplicate-titles.png` | The picker at a 2x font scale with two feeds of the same title |
| — | `mode-menu.png` | The mode menu opened by a long press, active mode marked |
| — | `long-list.png` | The picker after scrolling a 40-feed list |

`drawer.png` and `tablet.png` show the removed hierarchical tree. The README no longer references
them; they are replaced by the files above once the proof workflow has produced them.

`list.png` and `list-dark.png` predate the one-button top bar. Retake or drop them together with the
files above; do not use them for the new navigation.
