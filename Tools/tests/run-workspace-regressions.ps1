param([string]$JavaHome=$env:JAVA_HOME,
    [string]$JsonJar='D:/CodexBuildCache/gradle-user-caches-20261006/modules-2/files-2.1/org.json/json/20240303/ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a/json-20240303.jar',
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/workspace'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(!(Test-Path -LiteralPath $JsonJar -PathType Leaf)){throw 'Cached real JSON parser required; no network fallback.'}
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes=Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources=@(Get-ChildItem (Join-Path $PSScriptRoot 'workspace') -File -Filter '*.java' | ForEach-Object FullName)
$sources+=@('LunagramWorkspaceModel','LunagramWorkspaceStore','LunagramFeatureCatalog','BlackHoleSealedData' | ForEach-Object {Join-Path $repo "TMessagesProj/src/main/java/org/telegram/messenger/$_.java"})
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -cp $JsonJar -d $classes $sources
if($LASTEXITCODE -ne 0){throw 'Workspace host compilation failed.'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp "$classes;$JsonJar" org.telegram.messenger.WorkspaceTest $repo
if($LASTEXITCODE -ne 0){throw 'Workspace regressions failed.'}
$ui=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramWorkspaceActivity.java'))
function Get-WorkspaceMethod([string]$Name){
    $start=$ui.IndexOf($Name)
    if($start -lt 0){throw "Missing method: $Name"}
    $open=$ui.IndexOf('{',$start);$depth=1;$end=$open+1
    while($depth -gt 0 -and $end -lt $ui.Length){if($ui[$end] -eq '{'){$depth++}elseif($ui[$end] -eq '}'){$depth--};$end++}
    if($depth -ne 0){throw 'Unbalanced method extraction'}
    return $ui.Substring($start,$end-$start)
}
$methods=(Get-WorkspaceMethod 'private void navigate(Runnable action)')+"`n"+(Get-WorkspaceMethod 'private boolean closeWorkspace()')
$probe=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'workspace/WorkspaceNavigationProbe.java.template')).Replace('/*METHODS*/',$methods)
$probePath=Join-Path $run 'WorkspaceNavigationProbe.java'
[IO.File]::WriteAllText($probePath,$probe,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -cp "$classes;$JsonJar" -d $classes $probePath
if($LASTEXITCODE -ne 0){throw 'Navigation probe must compile.'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp "$classes;$JsonJar" org.telegram.messenger.WorkspaceNavigationProbe
if($LASTEXITCODE -ne 0){throw 'Navigation probe failed.'}
$navigationControls=@(
    @{Name='navigation-session';From='|| ((LunagramWorkspaceActivity) fragment).session != session';To='';Failure='different session preserved'},
    @{Name='navigation-owner';From='if (session.isCurrent()) action.run();';To='action.run();';Failure='owner rechecked after closing navigation frames'}
)
foreach($control in $navigationControls){
    $mutant=Join-Path $run $control.Name
    New-Item -ItemType Directory -Path $mutant -Force | Out-Null
    if(!$probe.Contains($control.From)){throw 'Navigation mutation anchor missing'}
    $path=Join-Path $mutant 'WorkspaceNavigationProbe.java'
    [IO.File]::WriteAllText($path,$probe.Replace($control.From,$control.To),[Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -cp "$classes;$JsonJar" -d $mutant $path
    if($LASTEXITCODE -ne 0){throw 'Navigation negative control must compile.'}
    $result=& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp "$mutant;$classes;$JsonJar" org.telegram.messenger.WorkspaceNavigationProbe 2>&1
    if($LASTEXITCODE -eq 0 -or !($result -join "`n").Contains($control.Failure)){throw "Navigation negative failed for wrong reason: $result"}
}
$negativeControls=@(
    @{Name='epoch';Class='LunagramWorkspaceStore';From='|| epoch != LunagramSubscriptionClient.accountEpoch(account)';To='';Failure='same owner relogin rejected'},
    @{Name='paid';Class='LunagramWorkspaceStore';From='if (mutate && !session.canMutate())';To='if (false)';Failure='expired mutation denied'},
    @{Name='vault';Class='LunagramWorkspaceStore';From='|| BlackHoleVault.blocks(account, dialogId)';To='';Failure='vault gate'},
    @{Name='corrupt';Class='LunagramWorkspaceStore';From='LunagramWorkspaceModel.Document previous = load(session); // Never overwrite unreadable/corrupt data.';To='LunagramWorkspaceModel.Document previous = new LunagramWorkspaceModel.Document(session.dialogId, java.util.Collections.emptyList());';Failure='unreadable does not overwrite'},
    @{Name='trailing';Class='LunagramWorkspaceModel';From='|| tokenizer.nextClean() != 0';To='';Failure='trailing data rejected'}
)
foreach($control in $negativeControls){
    $mutant=Join-Path $run $control.Name
    New-Item -ItemType Directory -Path $mutant -Force | Out-Null
    $original=[IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/java/org/telegram/messenger/$($control.Class).java"))
    if(!$original.Contains($control.From)){throw "Mutation anchor missing: $($control.Name)"}
    $mutated=Join-Path $mutant "$($control.Class).java"
    [IO.File]::WriteAllText($mutated,$original.Replace($control.From,$control.To),[Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -cp "$classes;$JsonJar" -d $mutant $mutated
    if($LASTEXITCODE -ne 0){throw "Negative control must compile: $($control.Name)"}
    $result=& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp "$mutant;$classes;$JsonJar" org.telegram.messenger.WorkspaceTest $repo 2>&1
    if($LASTEXITCODE -eq 0 -or !($result -join "`n").Contains($control.Failure)){throw "Negative control failed for wrong reason: $($control.Name): $result"}
}
$en=[xml][IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/res/values/lunagram_workspace.xml'))
$ru=[xml][IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/res/values-ru/lunagram_workspace.xml'))
$enNames=@($en.resources.string | ForEach-Object name | Sort-Object)
$ruNames=@($ru.resources.string | ForEach-Object name | Sort-Object)
if((Compare-Object $enNames $ruNames) -or ($enNames | Select-Object -Unique).Count -ne $enNames.Count){throw 'Workspace locale parity/duplicates failed.'}
$ui=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramWorkspaceActivity.java'))
foreach($match in [regex]::Matches($ui,'R\.string\.(LunagramWorkspace\w+)')){if($match.Groups[1].Value -notin $enNames){throw "Missing workspace localization: $($match.Value)"}}
Write-Output "PASS 5 compiled storage/model + 2 navigation negative controls; EN/RU $($enNames.Count) keys. Artifacts: $run"
