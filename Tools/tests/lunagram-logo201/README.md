# Isolated Android Lunagram GLES probe

This tiny test APK compiles the **actual snapshot** of production `LunagramLogoMesh.java`, packages unchanged `vertex2.glsl` and `lunagram_logo.glsl`, then draws all BASE/DISC/PLANE geometry with Android GLES20. It does not launch Telegram, authenticate an account, request permissions, or access the network.

Four square viewports show light/front, light/tilted, dark/front and dark/tilted. Projection, eye distance, rotation order, full alpha and normal part draw order match the ordinary production GLIconRenderer/Icon3D path. This verifies isolated geometry/shader execution, **not** the complete Icon3D buffer/material integration, texture lifecycle, interactive subscription UI, or physical-device GPU behavior.

Build without Gradle or ADB:

```powershell
& Tools/tests/run-lunagram-logo201-device-probe.ps1 -CompileOnly
& Tools/tests/run-lunagram-logo201-device-probe.ps1
```

`-CompileOnly` does not require the new fragment shader to exist. A full build snapshots the current shader when the script runs, generates/reuses a dedicated test signing key, verifies the APK identity/signature and absence of permissions, and reports its unique APK path. No emulator/ADB command is executed by the script.

The operator can run these separately, substituting the returned APK path:

```powershell
$adb = 'D:/CodexTools/Lunagram-Android-SDK/platform-tools/adb.exe'
& $adb -s emulator-5580 install -r 'RETURNED_APK_PATH'
& $adb -s emulator-5580 shell am start -S -n org.lunagram.test.logo201/.LogoProbeActivity --ef yaw 32 --ef pitch 14
& $adb -s emulator-5580 shell run-as org.lunagram.test.logo201 cat files/gles-report.json
```

Wait for `passed=true`, successful shader compile/program link/draw, `glError=0`, four foreground-pixel counts, and the visible **CAPTURE READY** footer. Failure logs use `LunagramLogo201Probe: PROBE_FAILED`; the UI footer shows the actual error. The JSON includes GL vendor/renderer/version, compile/link logs, source shader hashes and copied build input hashes.

For a binary-safe screenshot on Windows (operator only):

```powershell
& cmd.exe /d /c '"D:\CodexTools\Lunagram-Android-SDK\platform-tools\adb.exe" -s emulator-5580 exec-out screencap -p > "D:\CodexBuildCache\lunagram-logo201-native.png"'
```

Cleanup, when the operator no longer needs this test app:

```powershell
& $adb -s emulator-5580 uninstall org.lunagram.test.logo201
```
