# Real Android measurement probe

The runner extracts the exact `FeatureCell`, `PeriodChoiceCell` and `HeaderStatusCell` nested classes from the current subscription activity at each invocation. It uses cached Robolectric 4.14.1, Android API 35 framework classes and the cached Windows native text runtime. No dependency download or Gradle build occurs. Java heap limits are 256 MiB for compilation and 512 MiB for each sequential runtime.

The matrix measures 72 rows across 240/280/360 dp, font scales 1/1.3/2 and LTR/RTL. It checks full-width short and long rows, content-height growth, unclipped title/price text, non-ellipsized last characters, mirrored gravity and nonoverlapping radio geometry. Android `getLineMax` is used for visible advances: `getLineWidth` additionally counts trailing wrapping spaces.

An additional copy matrix reads all 34 paid feature titles and descriptions from the actual EN/RU XML through the production resource mappings. It measures 544 rows at 240/360 dp, font scales 1/2 and LTR/RTL. The geometry preview includes the actual portrait-camera feature copy; it does not substitute a short mock description for the new capability limitations.

`FrameLayout`, `LinearLayout`, `TextView`, `Canvas`, `Bitmap`, font shaping, line breaking and measurement are real Android/Robolectric implementations. Telegram theme colors/drawables, dp conversion, radio decoration, RecyclerView layout parameters and factory collaborators are modeled. This does **not** verify the entire RecyclerView, surrounding fragment, app resources, device GPU, complete subscription screen, or phone rendering.

The header matrix also measures 72 combinations of actual EN/RU resource copy, public/personal compact status, widths 240/280/360 dp, font scales 1/1.3/2 and LTR/RTL. The header's own 40 dp total horizontal inset is reserved. Both reused and newly created cells keep the same natural height for checking/active/free states, without truncation; a longer failure warning is allowed to expand. Invisible sizing labels participate in layout but are excluded from accessibility. The header PNG compares pending, active and failed states at normal and enlarged text sizes; it is not a full fragment or navigation-transition capture.

Three separately compiled generated-source negative controls must fail their actual geometry assertions: intrinsic feature width, a fixed-height duration row and removal of measured compact-header reservations. No production file is modified. PNGs are local geometry-only renderings of the measured cells; they are always saved under `Tools/tests/.runs/subscription-android204`, even when compilation output is redirected to another approved temporary directory.

Example (existing cache paths can be overridden):

```powershell
$env:RUNNER_TEMP = 'D:/CodexBuildCache'
Tools/tests/run-subscription-android204-regressions.ps1 `
  -JavaHome 'D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1' `
  -OutputRoot 'D:/CodexBuildCache/Lunagram-subscription-android204-tests'
```

If a framework or dependency is absent, the runner fails explicitly rather than downloading or substituting a modeled Android measurement result.
