param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/lunagram20-localization'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$keys=@([IO.File]::ReadAllLines((Join-Path $PSScriptRoot 'lunagram20-experience/localization-keys.txt')) | Where-Object {$_})
if($keys.Count -ne 135 -or @($keys | Sort-Object -Unique).Count -ne $keys.Count){throw 'The reviewed2.0 localization key list must contain exactly135 unique entries.'}
$plugin=[IO.File]::ReadAllText((Join-Path $repo 'buildSrc/src/main/kotlin/org/telegram/plugin/TelegramBuildAppPlugin.kt'))
if($plugin -notmatch '(?s)stringsXml\.from\(\s*telegramModule\.fileTree\("src/main/res/values"\)\s*\{\s*include\("strings.xml"\)' -or
    $plugin -notmatch '(?s)localizationFiles\.from\(\s*telegramModule\.fileTree\("src/main/res"\)\s*\{\s*include\("values-\*/strings.xml"\)' -or
    !$plugin.Contains('TelegramStringsTask::assetsOutputDir')){throw 'Localization probe input selection must match the real app generator plugin.'}
$paths=@();$sets=@();$family='^Lunagram(?:Stereo|Voice|EffectsOff$|Experience|Recording|Swipe|SaveToSelf$|Pet|Sounds|Sound|Send|Atmosphere|Streamer|Reminder|Hidden|Feed|Video|Meme|Welcome20|Subscription(?:RotateLogo$|ActiveUntil$|FreeTier$|PersonalTier$|LegacyTier$|CurrentPlan$|Info(?:Glass|Formatting|Typing|Undo|Round|Number|Level|Verification|Nft|Notes|Profiles|Transfer|Ghost|Deleted|Edits|Emergency|Vault|Journal|Export|Pdf|Local)$))'
foreach($locale in @('values','values-ru')){
    $path=Join-Path $repo ('TMessagesProj/src/main/res/'+$locale+'/strings.xml');$paths+=$path
    $xml=[xml][IO.File]::ReadAllText($path)
    $selected=@($xml.resources.string | Where-Object {$_.name -match $family})
    $names=@($selected | ForEach-Object name | Sort-Object)
    if(Compare-Object ($keys|Sort-Object) $names){throw "Missing/extra2.0 key in actual generator input: $locale"}
    foreach($key in $keys){$entries=@($selected|Where-Object {$_.name -eq $key});if($entries.Count -ne 1 -or [string]::IsNullOrWhiteSpace($entries[0].InnerText)){throw "Duplicate/empty resource $locale/$key"}}
    $sets+=,@($names)
    foreach($suffix in @('audio','experience','feed','video','welcome20')){
        if(Test-Path -LiteralPath (Join-Path $repo ('TMessagesProj/src/main/res/'+$locale+'/lunagram_'+$suffix+'.xml'))){throw 'Do not reintroduce standalone2.0 strings ignored by LocaleController.'}
    }
}
if(Compare-Object $sets[0] $sets[1]){throw 'EN/RU2.0 localization key parity failed.'}
function Body([string]$Source,[string]$Signature){
    $start=$Source.IndexOf($Signature,[StringComparison]::Ordinal);if($start -lt 0){throw "Missing lookup method $Signature"}
    $open=$Source.IndexOf('{',$start);$depth=1
    for($end=$open+1;$end -lt $Source.Length -and $depth -gt 0;$end++){if($Source[$end] -eq '{'){$depth++};if($Source[$end] -eq '}'){$depth--}}
    if($depth -ne 0){throw 'Unbalanced lookup method'};$Source.Substring($open+1,$end-$open-2)
}
$localization=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/utils/localization/Localization.java'))
$controller=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LocaleController.java'))
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'startup100/StartupLocalization15Probe.java.template'))
$template=$template.Replace('StartupLocalization15Probe','ExperienceLocalization20Probe')
$keySource=($keys|ForEach-Object {'"'+$_+'"'}) -join ','
$template=[regex]::Replace($template,'(?s)private static final String\[\] KEYS = \{.*?\};',('private static final String[] KEYS = {'+$keySource+'};'))
foreach($mapping in @(@('ACTUAL_RES_ID_BODY','public String getByResId('),@('ACTUAL_RES_NAME_BODY','public String getByResName('),@('ACTUAL_RES_NAME_OR_ID_BODY','public String getByResNameOrResId('),@('ACTUAL_GET_BODY','public String get(int resNameHash)'))){$template=$template.Replace('/* '+$mapping[0]+' */',(Body $localization $mapping[1]))}
$template=$template.Replace('/* ACTUAL_GET_STRING_BODY */',(Body $controller 'private String getStringV2('))
$file=Join-Path $run 'ExperienceLocalization20Probe.java';[IO.File]::WriteAllText($file,$template,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d $run $file
if($LASTEXITCODE -ne 0){throw 'Actual localization lookup bodies failed host compile.'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $run ExperienceLocalization20Probe @paths
if($LASTEXITCODE -ne 0){throw 'Actual getStringV2 lookup or standalone-only negative control failed.'}
Write-Output 'PASS all135 EN/RU2.0 keys: exact generator inputs, parity, duplicates, real lookup bodies, missing-asset negative controls. Not a Gradle asset/device render test.'
