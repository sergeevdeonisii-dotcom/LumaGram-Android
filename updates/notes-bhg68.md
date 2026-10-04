# BlackHoleGram 12.10.6-bhg.68

Removes only the Black Hole adaptive icon's supplied `monochrome` layer. It now exposes its existing full-color background and foreground without the separate palette-tintable ring drawing. The photographic artwork, alias, current icon selection, other icon variants and application features are unchanged. Source/resource checks and the APK publication guard explicitly verify that both packaged launcher configurations lack a monochrome element and that the adaptive foreground/background remain present.

This disables the application's own themed-icon provision; it cannot force every launcher or OEM firmware to ignore user-selected icon recoloring. Android 16 QPR2 and later can automatically generate themed icons even when the application supplies no monochrome layer: https://developer.android.com/develop/ui/compose/system/icon_design_adaptive . Actual Samsung One UI behavior must be confirmed on the user's phone, which is not connected here. This is not represented as a universal firmware opt-out or a verified Samsung screenshot.

Version: `12.10.6-bhg.68`, code `71479`, package `org.luma.liquid.web`. Build, signature verification and updater publication are pending. No phone settings, launcher data or cloud configuration are changed.
