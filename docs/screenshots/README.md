# Navigation screenshots

These are actual Compose/emulator captures of synthetic fixtures, not mock-ups or screenshots of a
private account. The toolbar fixture intentionally has an empty timeline. Older article-reader,
search and subscription-management pictures in this directory are separate historical captures.

The single-row toolbar and flat navigation captures were inspected from the successful
[verification run](https://github.com/juev/brook/actions/runs/41975232522), source head
`6330df913088a8fffc9f8eeda67736b70afd7036`. The temporary proof workflow has been removed after
retaining its GREEN → intended RED → byte-for-byte restore → GREEN evidence.

| File | What it shows |
|---|---|
| `compact-toolbar-light.png` | One compact toolbar row: current-mode icon, selected-scope picker, bulk read and overflow; no duplicate heading/count |
| `compact-picker-light.png` | A flat, title-triggered feed sheet with subscription counts |
| `compact-toolbar-dark.png` | The same compact toolbar in the dark theme |
| `compact-picker-dark.png` | The same flat picker in the dark theme |
| `large-layout.png` | A genuinely wide window with the permanent flat pane and list/detail regions |
| `mode-menu.png` | The three-mode menu opened by long press, with the active mode marked |

The large-font and long-list scenario is being verified separately using the emulator's real system
font setting, rather than an outer composition-only density override. Do not reuse the earlier
`large-font-duplicate-titles.png` proof capture as evidence of system-level scaling.

Canonical CI runs `.github/scripts/run-device-tests.sh` through one Bash invocation. It executes
the ordinary compact instrumented suites and a separately resized wide suite, retains their XML,
and checks the eight expected PNGs. The `android-test-reports` artifact is uploaded on both
success and failure; captures are under `device-evidence/compact/screenshots/` and
`device-evidence/large/screenshots/`. Retain the exact run/source SHA when refreshing these files.

`drawer.png`, `tablet.png`, `list.png` and `list-dark.png` predate this navigation and are not used
for the redesigned toolbar or feed picker. Rendered checks are not a substitute for hands-on
TalkBack or physical-device usability verification.
