param(
    [string]$JavaHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1' }),
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.runs/typography')
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$production = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramTypography.java'
$viewProduction = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/LunagramTypographyViews.java'
$fixtures = Join-Path $PSScriptRoot 'typography'
$sources = @($production, $viewProduction) + @(Get-ChildItem -LiteralPath $fixtures -Recurse -Filter '*.java' | ForEach-Object FullName)
$integrationFiles = @{}
foreach ($entry in @(
    @('fragment', 'org/telegram/ui/ActionBar/BaseFragment.java'),
    @('recycler', 'org/telegram/ui/Components/RecyclerListView.java'),
    @('theme', 'org/telegram/ui/ActionBar/Theme.java'),
    @('utilities', 'org/telegram/messenger/AndroidUtilities.java'),
    @('artwork', 'org/telegram/ui/Components/Paint/PaintTypeface.java'),
    @('application', 'org/telegram/messenger/ApplicationLoader.java'),
    @('settings', 'org/telegram/ui/LunagramTypographyActivity.java')
)) { $integrationFiles[$entry[0]] = Join-Path $repo ('TMessagesProj/src/main/java/' + $entry[1]) }
$hashes = @{}
foreach ($source in ($sources + @($integrationFiles.Values))) { $hashes[$source] = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash }
$run = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (!(Test-Path -LiteralPath $javac) -or !(Test-Path -LiteralPath $java)) { throw "JDK not found: $JavaHome" }
& $javac -J-Xmx128m -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Production typography helper compilation failed' }
foreach ($api in @(27, 28, 35)) {
    $cases = @()
    foreach ($family in 0..4) {
        foreach ($weight in 0..2) { $cases += ,@($family, $weight, 'persisted') }
    }
    $cases += ,@(0, 0, 'wrong-types')
    $cases += ,@(0, 0, 'wrong-range')
    $cases += ,@(2, 1, 'early-context')
    foreach ($case in $cases) {
        $result = & $java -Xmx64m -cp $classes org.telegram.messenger.TypographyProbe $api $case[0] $case[1] $case[2] 2>&1
        if ($LASTEXITCODE -ne 0) { throw "Typography behavior failed for API $api / ${case}: $result" }
        Write-Output $result
    }
}
foreach ($case in @(@(0, 0), @(2, 1), @(3, 2))) {
    $result = & $java -Xmx64m -cp $classes org.telegram.messenger.TypographyViewsProbe $case[0] $case[1] 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Typography view behavior failed for ${case}: $result" }
    Write-Output $result
}
$helper = [IO.File]::ReadAllText($production)
foreach ($mutation in @(
    @('live-remap', '        revision++;', '        activeFamily = family; activeWeight = weight; cache.clear(); revision++;', 'restart notice tracks selected versus startup pair', 0, 0),
    @('lost-bold-role', 'Math.max(role, selected)', 'selected', 'explicit bold italic retained', 2, 1),
    @('artwork-remap', 'isCode(source) || !isSystemFace(source) && !roles.containsKey(source)', 'false', 'unregistered asset is initially left untouched', 2, 1),
    @('unvalidated-family', 'nextFamily = clampFamily(nextFamily);', '/* unvalidated family */', 'invalid selections sanitize to default no-op', 0, 0),
    @('uncached-weight', 'if (cache.containsKey(source)) return cache.get(source);', '/* missing cache */', 'cached face avoids weighted Typeface recreation', 2, 1),
    @('lost-medium-role', 'roles.put(source, 500);', 'roles.put(source, 400);', 'system medium preserves 500 role after prior untouched cache', 2, 0),
    @('stale-asset-cache', 'if (!registered) cache.remove(source);', '/* stale untouched asset cache */', 'medium asset keeps semantic emphasis despite NORMAL asset style', 2, 1)
)) {
    if (!$helper.Contains($mutation[1])) { throw "Missing mutation anchor: $($mutation[0])" }
    $folder = Join-Path $run $mutation[0]
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $mutatedSource = Join-Path $folder 'LunagramTypography.java'
    [IO.File]::WriteAllText($mutatedSource, $helper.Replace($mutation[1], $mutation[2]), [Text.UTF8Encoding]::new($false))
    $mutationSources = @($mutatedSource) + @($sources | Where-Object { $_ -ne $production })
    & $javac -J-Xmx128m -encoding UTF-8 -d $folder @mutationSources
    if ($LASTEXITCODE -ne 0) { throw "Negative control must compile: $($mutation[0])" }
    $result = & $java -Xmx64m -cp $folder org.telegram.messenger.TypographyProbe 28 $mutation[4] $mutation[5] 'persisted' 2>&1
    $output = $result | Out-String
    if ($LASTEXITCODE -eq 0 -or !$output.Contains('AssertionError: ' + $mutation[3])) {
        throw "Negative control missed intended defect: $($mutation[0]) / $output"
    }
    Write-Output "PASS negative control: $($mutation[0])"
}
$viewHelper = [IO.File]::ReadAllText($viewProduction)
foreach ($mutation in @(
    @('password-remap', '!(text.getTransformationMethod() instanceof PasswordTransformationMethod)', 'true', 'password transformation excludes label'),
    @('detach-leak', 'onViewDetachedFromWindow(View view) { finish(); }', 'onViewDetachedFromWindow(View view) { }', 'detach cancels continuation and listener')
)) {
    if (!$viewHelper.Contains($mutation[1])) { throw "Missing view mutation anchor: $($mutation[0])" }
    $folder = Join-Path $run $mutation[0]
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $mutatedSource = Join-Path $folder 'LunagramTypographyViews.java'
    [IO.File]::WriteAllText($mutatedSource, $viewHelper.Replace($mutation[1], $mutation[2]), [Text.UTF8Encoding]::new($false))
    $mutationSources = @($mutatedSource) + @($sources | Where-Object { $_ -ne $viewProduction })
    & $javac -J-Xmx128m -encoding UTF-8 -d $folder @mutationSources
    if ($LASTEXITCODE -ne 0) { throw "View negative control must compile: $($mutation[0])" }
    $result = & $java -Xmx64m -cp $folder org.telegram.messenger.TypographyViewsProbe 2 1 2>&1
    $output = $result | Out-String
    if ($LASTEXITCODE -eq 0 -or !$output.Contains('AssertionError: ' + $mutation[3])) {
        throw "View negative control missed intended defect: $($mutation[0]) / $output"
    }
    Write-Output "PASS negative control: $($mutation[0])"
}
$presentationHelpers = $helper + "`n" + $viewHelper
if ($presentationHelpers -match '(?m)^import\s+org\.telegram\.(tgnet|messenger\.(MessageObject|MessagesController|SendMessagesHelper))' -or
        $presentationHelpers -match '\b(?:TLRPC|ConnectionsManager|sendRequest|sendMessage)\b|\.entities\s*(?:=|\.|\[)|messageOwner\s*\.') {
    throw 'Typography must remain local presentation without message-entity or network dependencies'
}
if ($presentationHelpers -match '\.(?:setText|append|setSpan|replace|delete|insert|getEditableText)\s*\(|java\.(?:io|nio)\.|FileOutputStream|Files\.write') {
    throw 'Typography helpers must not edit text, entities or source files'
}
if ($viewHelper -match 'OnGlobalLayoutListener|OnPreDrawListener|addOnGlobalLayoutListener|addOnPreDrawListener') {
    throw 'Typography must not install global-layout or pre-draw traversal watchers'
}
foreach ($token in @('child instanceof WebView', 'child instanceof TextPaintView', 'child instanceof EditTextOutline',
        'ignored.containsKey(child)', 'text.getTransformationMethod() instanceof PasswordTransformationMethod',
        'view.removeCallbacks(this)', 'view.removeOnAttachStateChangeListener(this)', 'pending.remove(view)')) {
    if (!$viewHelper.Contains($token)) { throw "Typography view boundary missing: $token" }
}
function Production-Method([string]$source, [string]$signature) {
    $start = $source.IndexOf($signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production method: $signature" }
    $open = $source.IndexOf('{', $start)
    $depth = 1
    for ($end = $open + 1; $end -lt $source.Length -and $depth -gt 0; $end++) {
        if ($source[$end] -eq '{') { $depth++ }
        if ($source[$end] -eq '}') { $depth-- }
    }
    if ($depth -ne 0) { throw "Unbalanced production method: $signature" }
    return $source.Substring($start, $end - $start)
}
$integration = @{}
foreach ($key in $integrationFiles.Keys) { $integration[$key] = [IO.File]::ReadAllText($integrationFiles[$key]) }
$resume = Production-Method $integration.fragment 'public void onResume()'
foreach ($target in @('fragmentView', 'actionBar')) {
    if (!$resume.Contains("LunagramTypographyViews.apply($target)")) { throw "BaseFragment resume typography hook missing: $target" }
}
if (!(Production-Method $integration.recycler 'public void onChildAttachedToWindow(View child)').Contains('LunagramTypographyViews.apply(child)')) {
    throw 'RecyclerListView must apply typography when native rows attach'
}
if (!(Production-Method $integration.utilities 'public static Typeface bold()').Contains('LunagramTypography.resolveSystemMedium(Typeface.create(null, 500, false))') -or
        !(Production-Method $integration.utilities 'public static Typeface getTypeface(String assetPath)').Contains('LunagramTypography.resolveAsset(assetPath, getTypefaceForArtwork(assetPath))')) {
    throw 'AndroidUtilities native system-medium and asset roles must route through typography'
}
$artworkLoader = Production-Method $integration.utilities 'public static Typeface getTypefaceForArtwork(String assetPath)'
if ($artworkLoader.Contains('LunagramTypography.') -or !$artworkLoader.Contains('return typefaceCache.get(assetPath);') -or
        $integration.artwork.Contains('AndroidUtilities.getTypeface(') -or $integration.artwork.Contains('AndroidUtilities.bold(')) {
    throw 'Media editor assets must use original cached faces without app typography remapping'
}
foreach ($font in @('ROBOTO_MEDIUM', 'ROBOTO_ITALIC', 'ROBOTO_CONDENSED', 'ROBOTO_MONO', 'MW_BOLD')) {
    if ($integration.artwork -notmatch ('(?m)^\s*public static final PaintTypeface ' + $font + '\s*=.*AndroidUtilities\.getTypefaceForArtwork\(')) {
        throw "Media editor builtin must load an unmodified artwork face: $font"
    }
}
if (!$integration.application.Contains('LunagramTypographyViews.install(this)') -or
        !$integration.settings.Contains('LunagramTypographyViews.ignore(preview)')) {
    throw 'Application lifecycle installation and settings preview exclusion must stay wired'
}
foreach ($paint in @('chat_msgTextPaint', 'chat_replyTextPaint', 'chat_namePaint', 'dialogs_messagePaint[a]', 'profile_aboutTextPaint')) {
    if (!$integration.theme.Contains("$paint = org.telegram.messenger.LunagramTypography.textPaint(")) {
        throw "Native Theme paint must use typography factory: $paint"
    }
}
foreach ($paint in @('chat_msgTextCodePaint', 'chat_msgTextCode2Paint', 'chat_msgTextCode3Paint')) {
    if (!$integration.theme.Contains("$paint.setTypeface(Typeface.MONOSPACE)")) { throw "Native code paint must retain monospace: $paint" }
}
foreach ($source in $hashes.Keys) {
    if ($hashes[$source] -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash) {
        throw "Source changed during typography tests: $source"
    }
}
Write-Output 'PASS source boundaries: lifecycle/row hooks, native Theme paints, raw artwork assets, protected editors/passwords, no global-layout/pre-draw watchers, no entity/network/text/file editing'
Write-Output "Test artifacts: $run"
