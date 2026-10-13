param(
    [string]$JavaHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1' }),
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.runs')
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$testRoot = Join-Path $PSScriptRoot 'lunagram20-swipe'
$run = Join-Path $OutputRoot ('lunagram20-swipe-' + [guid]::NewGuid().ToString())
$javaSuffix = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $javaSuffix)
$java = Join-Path $JavaHome ('bin/java' + $javaSuffix)
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
function Extract-Method([string]$Source,[string]$Signature) {
    $start = $Source.IndexOf($Signature,[StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production method $Signature" }
    $open = $Source.IndexOf('{',$start); $depth = 1
    for ($end=$open+1; $end -lt $Source.Length -and $depth -gt 0; $end++) {
        if ($Source[$end] -eq '{') { $depth++ }; if ($Source[$end] -eq '}') { $depth-- }
    }
    if ($depth -ne 0) { throw "Unbalanced production method $Signature" }
    $Source.Substring($start,$end-$start)
}
$source = Get-Content -LiteralPath (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java') -Raw
$template = Get-Content -LiteralPath (Join-Path $testRoot 'LunagramSwipeProbe.java.template') -Raw
$probe = $template.Replace('@@TOUCH@@',(Extract-Method $source 'private void processTouchEvent(MotionEvent e)')).Replace('@@POLICY@@',(Extract-Method $source 'private int[] lunagramSwipeActions(MessageObject message)')).Replace('@@ACTION@@',(Extract-Method $source 'private void runLunagramSwipeAction(MessageObject message, int action)'))
$probe = $probe.Replace('@@PREPARE@@',(Extract-Method $source 'private void beginLunagramSwipeMenu(float y, MessageObject message)'))
$swipeRegion = $source.Substring($source.IndexOf('private boolean lunagramSwipeTouchConsumed;'))
$probe = $probe.Replace('@@INTERCEPT@@',(Extract-Method $swipeRegion 'public boolean onInterceptTouchEvent(MotionEvent e)'))
$touch = Extract-Method $swipeRegion 'public boolean onTouchEvent(MotionEvent e)'
# Run the actual claimed-stream prefix, with the unrelated stock pull-down implementation left to Android.
$prefix = $touch.Substring(0,$touch.IndexOf('if (e.getAction() == MotionEvent.ACTION_DOWN)')) + 'return super.onTouchEvent(e); }'
$probe = $probe.Replace('@@OWNED_TOUCH@@',$prefix).Replace('@@DRAW@@',(Extract-Method $swipeRegion 'protected void dispatchDraw(Canvas canvas)'))
$probe = $probe.Replace('ChatActivity.this','LunagramSwipeProbe.this')
$probeFile = Join-Path $run 'LunagramSwipeProbe.java'
[IO.File]::WriteAllText($probeFile,$probe,[Text.UTF8Encoding]::new($false))
$sources = @(Get-ChildItem -LiteralPath (Join-Path $testRoot 'fixtures') -Filter '*.java' -Recurse | ForEach-Object FullName)
$messenger = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger'
$sources += @(Join-Path $messenger 'LunagramSwipeGesture.java'; Join-Path $messenger 'LunagramExperienceSettings.java'; Join-Path $messenger 'LumaAccountData.java'; $probeFile)
$sources += Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/LunagramSwipeMenu.java'
$sources += Join-Path $messenger 'LunagramFeatureCatalog.java'
& $javac -J-Xmx128m -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Extracted swipe production methods failed host compilation' }
& $java -Xmx128m -cp $classes LunagramSwipeProbe
if ($LASTEXITCODE -ne 0) { throw 'Swipe production behavior checks failed' }
$mutations = @(
    @('flick-default', 'LunagramSwipeGesture.java', 'if (menuActive) return actions[index];', 'if (true) return actions[index];', 'Flick always highlights Reply regardless of drift/order'),
    @('hold-threshold', 'LunagramSwipeGesture.java', 'MENU_HOLD_MS = 240;', 'MENU_HOLD_MS = 0;', 'Menu waits full stable hold'),
    @('early-menu', 'LunagramSwipeMenu.java', '!gesture.menuActive() || gesture.selected()', 'gesture.selected()', 'No full menu during flick')
)
foreach ($mutation in $mutations) {
    $original = @($sources | Where-Object { [IO.Path]::GetFileName($_) -eq $mutation[1] })[0]
    $body = [IO.File]::ReadAllText($original)
    if (!$body.Contains($mutation[2])) { throw "Missing swipe negative-control anchor: $($mutation[0])" }
    $mutantRoot = Join-Path $run $mutation[0]
    New-Item -ItemType Directory -Path $mutantRoot -Force | Out-Null
    $mutant = Join-Path $mutantRoot $mutation[1]
    [IO.File]::WriteAllText($mutant, $body.Replace($mutation[2], $mutation[3]), [Text.UTF8Encoding]::new($false))
    $mutantSources = @($sources | Where-Object { $_ -ne $original }) + @($mutant)
    & $javac -J-Xmx128m -encoding UTF-8 -d $mutantRoot @mutantSources
    if ($LASTEXITCODE -ne 0) { throw "Swipe negative control failed to compile: $($mutation[0])" }
    $result = & $java -Xmx128m -cp $mutantRoot LunagramSwipeProbe 2>&1
    if ($LASTEXITCODE -eq 0 -or !($result | Out-String).Contains($mutation[4])) { throw "Swipe negative control missed its intended fault: $($mutation[0]): $result" }
    Write-Output "PASS negative control $($mutation[0])"
}
Write-Output "Results: $run"
