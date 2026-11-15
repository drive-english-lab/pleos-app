# Product UI port

2026-11-15 · issue6 · local branch `refactor/6-product-ui`.

Base: `1811d7ee25ed4b45618317f3821c79de0e607f76`. The previous debug UI branch is preserved at `11d5dd8`. This branch changes the actual product Compose renderers; it does not merge the old sample Application, service, database or preview state.

- One transcript, with visible article questions/practice and an article menu for information, format, listening-only and sentence assistance.
- Real matching script/manifest segment tracking, manual scroll suspension and explicit return. Long segments start near the top. Missing segment data stays ordinary text.
- Bottom-center play/pause and previous/next **segment** controls. Legacy time-only media has disabled segment arrows; no ten-second seeking is reintroduced.
- Selected news/history navigation, expandable settings with current draft values, explicit article return from the practice modal, scrolling record notices and wrapping question actions.

All existing UiIntent origins, owner/epoch checks, parked restrictions, selection, practice input, recording, persistence, deletion and end policies remain owned by the product code. No PracticeScreen, Controller, Host, data/domain, media service, permission or real voice implementation is copied from the old UI.

## Verification

Run the normal build from this repository:

```sh
source scripts/dev-env.sh
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug
```

`ProductScreensTest` and `PreparedProductScreensTest` are synthetic renderer/typed-intent fixtures. The optional runner avoids initializing the production Auth/SDK/media graph while inspecting these renderers. It must not be used to claim actual MainActivity, vehicle, news, STT/TTS or Gleo success. The default runner is unchanged for all integration tests.

```sh
./gradlew :app:assembleDebugAndroidTest -PrendererOnlyTests=true
adb -s emulator-5554 install -r -t --user 10 app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t --user 10 app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument --user 10 -w -r -e class io.github.driveenglishlab.pleos.PreparedProductScreensTest,io.github.driveenglishlab.pleos.ProductScreensTest io.github.driveenglishlab.pleos.test/io.github.driveenglishlab.pleos.RendererTestRunner
```

Only use the assigned UI AVD after checking its identity/user. Captures contain fixture text. Final results and remaining conditions are recorded in the workspace's004 review/handoff and product-integration verification. No Sites deployment or remote push is included in this local UI port.

First handoff: debug/release and lint completed (0 errors,38 warnings); the final selected-navigation blue token change passed `:app:compileDebugKotlin`. The17-case renderer run is being retained separately. Its early results include modal/feedback, capture-confirmation states, narrow/dark/large-text transport alignment and restrictions. It also found a duplicate settings label selector (corrected to the checkbox role; that selector-only correction awaits the combined test build) and AVD ActivityScenario startup failures. Do not call this all17 passing. The center-follow test affected by ActivityScenario startup still requires execution. The production Application runner also hit startup ANR on the UI AVD; the isolated renderer runner is explicitly separate evidence.

## Integration

Product follow-up `216f9ecaadcddef4900c0b4a4209ccb30273c5a2` changes33 paths. The UI renderer files do not overlap those paths. README append integration needs to preserve both reports. The product owner integrates this UI commit into that newer product code and verifies the combined actual app before filming. No product WIP is committed or overwritten here.

Design refinement reused the saved Lazyweb/UI UX Pro Max/Taste evidence and applied Impeccable/Unslop. Unslop by Damir Sokolovsky ([source](https://heydamir.design/unslop)), CC BY4.0.
