# Applied After layout refinement

Base / Before: `6d9927f9ee9ed29772a9c015e95661715617c0ec`.
After candidate: `bfb3b12fa4857cfaa6a781248afec805518ed34b` from `refactor/6-ui-option`. **User approved application; merged into the product branch as d0688e1.** Before6d/b34 and its existing release/APK/video remain preserved.

- Expanded player: phase label and article actions share a row; the transcript gains45px in the1600×1100 fixture.
- Narrow player: segment/status information shares a row under centered playback; the transcript gains51px at900×1100, dark/font1.3.
- Practice dialog: smaller outer bounds, retaining the existing scrolling content, controls and return action.
- Settings: names and current values align in one row on wide windows; compact windows retain stacked text.

No playback, state-machine, permission, voice, storage, exposure or vehicle policy changes. Blue/Asta, text scale,76dp targets and centered play/previous/next segment controls remain. No next-article timer or legacy ten-second seek is introduced.

Before/After debug and renderer-test APK builds passed. The same `PreparedProductScreensTest#optionalUiComparisonFrames` fixture ran once per variant at wide/light and narrow/dark/font1.3: all four comparison runs passed. These are synthetic Compose renderer captures on the assigned Pleos AVD, not actual Auth, vehicle service, news or speech success. No new full regression/release/lint run was made for this optional patch.

Workspace comparison: `docs/product/design/verification/ui-option-20261115/index.html`, with eight original PNGs, exact metrics/SHA and logs. Before/After APKs are separately preserved in `development/.integration-snapshots/ui-option-20261115/`. Existing product APK/video/files are untouched.

The earlier unselected comparison is historical. The current authorized step packages this applied After as a separate signed release and actual recording, with focused impact checks. It does not replace the preserved Before release or change product/vehicle/voice policies. Sites impact and actual-versus-fixture evidence are recorded by the integration owner; no automatic deployment is implied.

Impeccable/Unslop refinement reuses the saved design evidence. Unslop by Damir Sokolovsky ([source](https://heydamir.design/unslop)), CC BY4.0.
