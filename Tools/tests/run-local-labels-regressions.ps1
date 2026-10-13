param(
    [string]$JavaHome=$(if($env:JAVA_HOME){$env:JAVA_HOME}else{'D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1'}),
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/local-labels')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix);$java=Join-Path $JavaHome ('bin/java'+$suffix)
$base=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger'
$model=Join-Path $base 'LunagramLocalLabelsModel.java';$store=Join-Path $base 'LunagramLocalLabels.java'
$sources=@($model,(Join-Path $base 'LunagramFeatureCatalog.java'))+@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'local-labels') -Filter '*.java' | ForEach-Object FullName)
$hashes=@{};foreach($inputPath in ($sources+@($store))){$hashes[$inputPath]=(Get-FileHash -LiteralPath $inputPath -Algorithm SHA256).Hash}
function Run-Label([string]$source,[string]$negative=''){
    & $javac -J-Xmx128m -encoding UTF-8 -d $run @sources $source
    if($LASTEXITCODE -ne 0){throw 'Local labels compile failed'}
    $result=& $java -Xmx128m -cp $run org.telegram.messenger.LocalLabelsTest 2>&1
    if($negative){
        if($LASTEXITCODE -eq 0 -or !($result|Out-String).Contains($negative)){throw "Negative missed: $negative / $result"}
        Write-Output "PASS negative: $negative"
    }else{if($LASTEXITCODE -ne 0){throw "Local labels failed: $result"};Write-Output $result}
}
Run-Label $store
$original=Get-Content -LiteralPath $store -Raw
$mutationPath=Join-Path $run 'LunagramLocalLabels.java'
foreach($mutation in @(
    @('account!=UserConfig.selectedAccount','false','selected account rejected'),
    @('epoch!=LunagramSubscriptionClient.accountEpoch(account)','false','epoch changed rejected'),
    @('owner!=UserConfig.getInstance(account).getClientUserId()','false','owner replacement rejected'),
    @('SharedConfig.appLocked','false','passcode fails closed'),
    @('LunagramSubscriptionAccess.canUse(account,LunagramFeatureCatalog.Feature.LOCAL_WORKSPACE)','true','expiry does not remove saved data'),
    @('model=LunagramLocalLabelsModel.decode(BlackHolePrivateData.read(session.account,KEY,null));','model=LunagramLocalLabelsModel.decode(null);','store creates own alias'),
    @('return owner == session.owner && epoch == session.epoch;','return epoch == session.epoch;','cache isolated from replacement owner'),
    @('return owner == session.owner && epoch == session.epoch;','return owner == session.owner;','cache isolated from replacement epoch'),
    @('if (account >= 0 && account < CACHE.length) CACHE[account] = null;','if (false) CACHE[account] = null;','logout clears cached data only'),
    @('CACHE[session.account] = null;','/* faulty invalidation */','set invalidates cached name'),
    @('Snapshot snapshot = CACHE[session.account];','Snapshot snapshot = null;','typing subtitle reuses immutable snapshot without decryption')
)){
    if(!$original.Contains($mutation[0])){throw "Missing mutation: $($mutation[0])"}
    [IO.File]::WriteAllText($mutationPath,$original.Replace($mutation[0],$mutation[1]),[Text.UTF8Encoding]::new($false))
    Run-Label $mutationPath $mutation[2]
}
Run-Label $store
$keys=@()
foreach($variant in @('values','values-ru')){
    [xml]$xml=Get-Content -LiteralPath (Join-Path $repo "TMessagesProj/src/main/res/$variant/lunagram_labels.xml") -Raw
    $found=@($xml.resources.string | ForEach-Object name | Sort-Object)
    if($keys.Count -and (Compare-Object $keys $found)){throw 'Label keys differ EN/RU'}
    if(($found|Select-Object -Unique).Count -ne $found.Count){throw 'Duplicate label key'}
    $keys=$found
}
$proxy=Get-Content -LiteralPath (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java') -Raw
if(!$proxy.Contains('LunagramAliasSheet.showProxy(') -or !$proxy.Contains('if (updateLabel()) valueTextView.setText(proxyAddress()')){throw 'Proxy label reachable/real identity hooks missing'}
$logout=Get-Content -LiteralPath (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java') -Raw
if(!$logout.Contains('LunagramLocalLabels.clearAccountCache(currentAccount);')){throw 'Logout must clear local label cache under its write barrier'}
if($original -match 'sendRequest\(|SharedConfig\.currentProxy\s*=|saveProxyList\(|\.getLink\(|FileLog\.'){throw 'Local label store must not connect/export/log'}
foreach($inputPath in $hashes.Keys){if($hashes[$inputPath] -ne (Get-FileHash -LiteralPath $inputPath -Algorithm SHA256).Hash){throw "Source changed during labels test: $inputPath"}}
$hashes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'source-hashes.json') -Encoding UTF8
Write-Output "PASS labels source hooks and $($keys.Count) EN/RU keys; no Android screenshot/device claim. Artifacts: $run"
