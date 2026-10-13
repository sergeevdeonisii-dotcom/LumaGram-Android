param(
    [string]$Aapt2='',
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/surface210')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$ledgerPath=Join-Path $PSScriptRoot 'lunagram210-feature-ledger.json'
$ledger=Get-Content -LiteralPath $ledgerPath -Raw | ConvertFrom-Json
$expected=@{text=7;reading=3;links=6;workspace=8;media=6}
if($ledger.schemaVersion -ne 1 -or $ledger.features.Count -ne 30 -or $ledger.version -ne '2.1.0' -or $ledger.versionCode -ne 71909){throw 'Feature ledger identity/count mismatch'}
if(@($ledger.features.id|Sort-Object -Unique).Count -ne 30){throw 'Feature IDs must be distinct'}
if($ledger.evidenceLimits.nativeDeviceVisualTested -or $ledger.evidenceLimits.physicalPlayerClipboardTested){throw 'No device evidence was supplied to this source test'}
foreach($module in $expected.Keys){if(@($ledger.features|Where-Object module -eq $module).Count -ne $expected[$module]){throw "Wrong module count: $module"}}
$hashes=@{};$hashes[$ledgerPath]=(Get-FileHash -LiteralPath $ledgerPath -Algorithm SHA256).Hash
$sourceCache=@{}
foreach($feature in $ledger.features){
    if(!$expected.ContainsKey($feature.module) -or !$feature.title -or !$feature.entry -or !$feature.limits -or $feature.evidence.Count -lt 2){throw "Incomplete feature entry: $($feature.id)"}
    foreach($item in $feature.evidence){
        if(!$item.path.StartsWith('TMessagesProj/src/main/java/org/telegram/') -or $item.path.Contains('..')){throw 'Unsafe evidence path'}
        $path=Join-Path $repo $item.path
        if(!$sourceCache.ContainsKey($path)){$sourceCache[$path]=Get-Content -LiteralPath $path -Raw;$hashes[$path]=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
        if(!$item.token -or !$sourceCache[$path].Contains($item.token)){throw "Source evidence missing for $($feature.id): $($item.token)"}
    }
}
$newNames=@('lunagram_text_tools.xml','lunagram_link_tools.xml','lunagram_workspace.xml','lunagram_labels.xml','lunagram_media_tools.xml')
$variants=@{};$compiled=@();$stringCount=0
foreach($qualifier in @('values','values-ru')){
    $names=@{};$allFiles=@()
    foreach($module in @('TMessagesProj','TMessagesProj_AppStandalone')){
        $directory=Join-Path $repo "$module/src/main/res/$qualifier"
        if(Test-Path -LiteralPath $directory){$allFiles+=@(Get-ChildItem -LiteralPath $directory -File -Filter '*.xml')}
    }
    $newStrings=@{}
    foreach($file in $allFiles){
        [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
        foreach($node in @($xml.resources.ChildNodes)){
            if($node.NodeType -ne [System.Xml.XmlNodeType]::Element -or !$node.HasAttribute('name')){continue}
            $key=$node.GetAttribute('name')
            if(!$names.ContainsKey($key)){$names[$key]=[Collections.Generic.List[string]]::new()}
            $names[$key].Add($file.FullName)
            if($file.Name -in $newNames -and $file.FullName.Contains('TMessagesProj'+[IO.Path]::DirectorySeparatorChar)){
                if($node.LocalName -ne 'string' -or $key -notmatch '^(Lut|Lul|Lunagram)[A-Za-z0-9_]+$' -or [string]::IsNullOrWhiteSpace($node.InnerText)){throw "Invalid native string: $key"}
                if($newStrings.ContainsKey($key)){throw "Duplicate new resource: $key"}
                $newStrings[$key]=$node.InnerText
            }
        }
    }
    foreach($key in $newStrings.Keys){if($names[$key].Count -ne 1){throw "New resource namespace collision: $qualifier/$key"}}
    foreach($name in $newNames){
        $path=Join-Path $repo "TMessagesProj/src/main/res/$qualifier/$name"
        if(!(Test-Path -LiteralPath $path -PathType Leaf)){throw "Missing localized file: $path"}
        $compiled+=$path;$hashes[$path]=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    }
    $variants[$qualifier]=$newStrings
}
if(Compare-Object @($variants['values'].Keys|Sort-Object) @($variants['values-ru'].Keys|Sort-Object)){throw 'New EN/RU keys differ'}
foreach($key in $variants['values'].Keys){
    $english=[regex]::Matches($variants['values'][$key],'(?<!%)%(?:[1-9][0-9]*\$)?[sdfoxX]')|ForEach-Object Value|Sort-Object
    $russian=[regex]::Matches($variants['values-ru'][$key],'(?<!%)%(?:[1-9][0-9]*\$)?[sdfoxX]')|ForEach-Object Value|Sort-Object
    if([string]::Join('|',@($english)) -ne [string]::Join('|',@($russian))){throw "Localized format arguments differ: $key"}
}
$pluginPath=Join-Path $repo 'buildSrc/src/main/kotlin/org/telegram/plugin/TelegramBuildAppPlugin.kt'
$plugin=Get-Content -LiteralPath $pluginPath -Raw;$hashes[$pluginPath]=(Get-FileHash -LiteralPath $pluginPath -Algorithm SHA256).Hash
if([regex]::Matches($plugin,'include\("strings\.xml", "lunagram_\*\.xml"\)').Count -ne 2 -or [regex]::Matches($plugin,'include\("values-\*/strings\.xml", "values-\*/lunagram_\*\.xml"\)').Count -ne 2){throw 'Telegram TL asset generator must include both module and app new English/localized XMLs'}
if($Aapt2){
    if(!(Test-Path -LiteralPath $Aapt2 -PathType Leaf)){throw 'Requested aapt2 does not exist'}
    foreach($path in $compiled){& $Aapt2 compile $path -o $run;if($LASTEXITCODE -ne 0){throw "Native resource compile failed: $path"}}
    Write-Output 'PASS actual aapt2 compile of all 10 new localized XML files (not APK linking or TL assets)'
}else{Write-Output 'SKIP native aapt2 compile: supply -Aapt2; XML/namespace/format/plugin contracts still checked'}
foreach($path in $hashes.Keys){if($hashes[$path] -ne (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash){throw "Input changed during check: $path"}}
$hashes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'source-hashes.json') -Encoding UTF8
Write-Output "PASS 30 source-backed features (7/3/6/8/6), $($variants['values'].Count) EN/RU keys, no namespace collisions, format parity, four TL generator includes. No device proof. Artifacts: $run"
