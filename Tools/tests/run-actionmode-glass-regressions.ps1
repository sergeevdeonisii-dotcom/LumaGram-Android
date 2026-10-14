param(
    [string]$JavaHome='D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1',
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/actionmode-glass')
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBar.java'))
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
function Method([string]$body,[string]$signature){
    $start=$body.IndexOf($signature,[StringComparison]::Ordinal);if($start -lt 0){throw "Missing production branch $signature"};$open=$body.IndexOf('{',$start);$depth=1
    for($end=$open+1;$end -lt $body.Length -and $depth -gt 0;$end++){if($body[$end] -eq '{'){$depth++};if($body[$end] -eq '}'){$depth--}}
    if($depth -ne 0){throw 'Unbalanced production branch'};$body.Substring($start,$end-$start)
}
$glassStart=$source.IndexOf('public boolean doNotDrawGlassMenu;')
if($glassStart -lt 0){throw 'Missing ActionBar glass draw region'}
$draw=Method $source.Substring($glassStart) 'protected void dispatchDraw(Canvas canvas)'
$prefixStart=$draw.IndexOf('{')+1;$prefixEnd=$draw.IndexOf('if (glassDrawable != null')
if($prefixEnd -le $prefixStart){throw 'Missing ActionBar geometry prefix'}
$prefix=$draw.Substring($prefixStart,$prefixEnd-$prefixStart)
$branch=Method $draw 'if (glassDrawableMenu != null && menuWidth > 0 && !glassOnlyBack && !doNotDrawGlassMenu)'
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'actionmode-glass/ActionModeGlassProbe.java.template')).Replace('@@WIDTH_METHOD@@',(Method $source 'public void checkMenuItemsWidth()')).Replace('@@DRAW_PREFIX@@',$prefix).Replace('@@DRAW_BRANCH@@',$branch)
function Run-Probe([string]$name,[string]$body,[string]$expected=''){
    $folder=Join-Path $run $name;New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $file=Join-Path $folder 'ActionModeGlassProbe.java';[IO.File]::WriteAllText($file,$body,[Text.UTF8Encoding]::new($false))
    $suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
    & (Join-Path $JavaHome ('bin/javac'+$suffix)) -J-Xmx96m -encoding UTF-8 -d $folder $file
    if($LASTEXITCODE -ne 0){throw "Action-mode glass compilation failed: $name"}
    $result=& (Join-Path $JavaHome ('bin/java'+$suffix)) -Xmx96m -cp $folder ActionModeGlassProbe 2>&1
    $code=$LASTEXITCODE;$output=$result|Out-String
    if($expected){if($code -eq 0 -or !$output.Contains('AssertionError: '+$expected)){throw "Negative control missed intended defect: $name $output"};Write-Output "PASS negative control $name"}
    elseif($code -ne 0){throw "Action-mode glass regression failed: $output"}else{Write-Output $output.Trim()}
}
Run-Probe 'production' $template
foreach($mutation in @(
    @('hidden-avatar-anchor','final ActionBarMenu glassMenu = actionModeVisible ? actionMode : menu;','final ActionBarMenu glassMenu = menu;','selection glass uses active action group not hidden avatar'),
    @('normal-avatar-regression','final ActionBarMenu glassMenu = actionModeVisible ? actionMode : menu;','final ActionBarMenu glassMenu = actionMode;','normal avatar circle remains centered and unchanged'),
    @('mixed-y-anchor','glassMenu != null ? glassMenu.getExclusiveItemCenterYInParent()','menu != null ? menu.getExclusiveItemCenterYInParent()','both exclusive coordinates come from active menu')
)){
    if(!$template.Contains($mutation[1])){throw "Missing action-mode mutation anchor $($mutation[0])"}
    Run-Probe $mutation[0] ($template.Replace($mutation[1],$mutation[2])) $mutation[3]
}
Write-Output "Results: $run"
