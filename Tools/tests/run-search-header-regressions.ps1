param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.runs/search-header')
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function Extract-Method([string]$source, [string]$signature) {
    $start = $source.IndexOf($signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production method: $signature" }
    $open = $source.IndexOf('{', $start)
    $depth = 1
    for ($i = $open + 1; $i -lt $source.Length; $i++) {
        if ($source[$i] -eq '{') { $depth++ }
        if ($source[$i] -eq '}') { $depth-- }
        if ($depth -eq 0) { return $source.Substring($start, $i - $start + 1) }
    }
    throw "Unclosed production method: $signature"
}
$base = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui'
$chat = [IO.File]::ReadAllText((Join-Path $base 'ChatActivity.java'))
$item = [IO.File]::ReadAllText((Join-Path $base 'ActionBar/ActionBarMenuItem.java'))
$menu = [IO.File]::ReadAllText((Join-Path $base 'ActionBar/ActionBarMenu.java'))
$chatMethods = @('private void updateLiquidGlassHeaderLayout(boolean enabled)',
    'private void updateLiquidHeaderForSearch(boolean expanded)',
    'private void finishLiquidHeaderSearch()') | ForEach-Object { Extract-Method $chat $_ }
$menuMethods = @('public void setSearchOnly(boolean searchOnly)',
    'protected boolean drawChild(Canvas canvas, View child, long drawingTime)',
    'private void updateExclusiveAccessibility()',
    'protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)',
    'protected void onLayout(boolean changed, int l, int t, int r, int b)',
    'public int getItemsWidth()') | ForEach-Object { Extract-Method $menu $_ }
if ($chat.Contains('restoreLiquidHeaderAfterSearch')) { throw 'Timer-based header restoration returned.' }
if (!(Extract-Method $chat 'public void onSearchCollapseFinished()').Contains('finishLiquidHeaderSearch();')) {
    throw 'Actual ChatActivity listener must connect completion to compact-header restoration.'
}
if (!(Extract-Method $chat 'public void onFragmentDestroy()').Contains('liquidHeaderSearchActive = false;')) {
    throw 'Destroyed fragment must clear its pending search state.'
}
$harness = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'search-header/SearchHeaderRegression.java.template'))
$harness = $harness.Replace('/* CHAT_METHODS */', ($chatMethods -join "`n"))
$harness = $harness.Replace('/* MENU_METHODS */', ($menuMethods -join "`n"))
$harness = $harness.Replace('/* TOGGLE_METHOD */', (Extract-Method $item 'public boolean toggleSearch(boolean openKeyboard)'))
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$file = Join-Path $run 'SearchHeaderRegression.java'
[IO.File]::WriteAllText($file, $harness, [Text.UTF8Encoding]::new($false))
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac' } else { 'javac' }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java' } else { 'java' }
& $javac -encoding UTF-8 -d $run $file
if ($LASTEXITCODE -ne 0) { throw 'Search header regression compilation failed.' }
& $java -cp $run SearchHeaderRegression
if ($LASTEXITCODE -ne 0) { throw 'Search header regressions failed.' }
# Negative controls must be rejected by the same runtime tests.
$mutations = @{
    'legacy-avatar' = @('avatarContainer.setExternalAvatarMode(true);', 'avatarContainer.setExternalAvatarMode(!liquidHeaderSearchActive);')
    'stale-callback' = @('cancelled || searchContainerAnimator != animation || searchContainer.getTag() != null', 'false')
    'search-actions' = @('if (searchOnly && child instanceof ActionBarMenuItem) return false;', 'if (false) return false;')
    'reduced-motion-order' = @('final AnimatorSet closingAnimator = searchContainerAnimator;', 'final AnimatorSet closingAnimator = searchContainerAnimator; closingAnimator.start();')
    'cancelled-completion' = @('cancelled || searchContainerAnimator != animation', 'searchContainerAnimator != animation')
    'replaced-animation-owner' = @('searchContainerAnimator != animation || searchContainer.getTag() != null', 'searchContainer.getTag() != null')
}
foreach ($name in $mutations.Keys) {
    $pair = $mutations[$name]
    if (!$harness.Contains($pair[0])) { throw "Negative control target missing: $name" }
    [IO.File]::WriteAllText($file, $harness.Replace($pair[0], $pair[1]), [Text.UTF8Encoding]::new($false))
    & $javac -encoding UTF-8 -d $run $file
    if ($LASTEXITCODE -ne 0) { throw "Negative control did not compile: $name" }
    $negative = & $java -cp $run SearchHeaderRegression 2>&1
    if ($LASTEXITCODE -eq 0) { throw "Negative control escaped: $name" }
    Write-Output "Rejected negative control: $name"
}
[IO.File]::WriteAllText($file, $harness, [Text.UTF8Encoding]::new($false))
Write-Output 'Production methods exercised with platform fixtures, not device rendering.'
