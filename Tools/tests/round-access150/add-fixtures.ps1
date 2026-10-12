# Camera-only regression dependency input; never included in the application.
$sources += (Join-Path $PSScriptRoot '../../../TMessagesProj/src/main/java/org/telegram/messenger/LunagramFeatureCatalog.java')
$sources += (Get-ChildItem (Join-Path $PSScriptRoot 'fixtures') -Recurse -Filter '*.java').FullName
