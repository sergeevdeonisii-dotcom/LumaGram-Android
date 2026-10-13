param([string]$JavaHome=$env:JAVA_HOME,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/subscription-layout204'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$allowed=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.runs'))+[IO.Path]::DirectorySeparatorChar
$output=[IO.Path]::GetFullPath($OutputRoot)
$inRunnerTemp=$env:RUNNER_TEMP -and $output.StartsWith([IO.Path]::GetFullPath($env:RUNNER_TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)
if (!$output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -and !$inRunnerTemp) { throw 'Layout test output must stay in Tools/tests/.runs or configured runner temporary storage.' }
$screen=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LunagramSubscriptionActivity.java'))
function Body([string]$source,[string]$signature) {
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal)
    if($start -lt 0){throw "Missing production declaration: $signature"}
    $open=$source.IndexOf('{',$start); $depth=1
    for($end=$open+1;$end -lt $source.Length -and $depth -gt 0;$end++){
        if($source[$end] -eq '{'){$depth++}; if($source[$end] -eq '}'){$depth--}
    }
    if($depth -ne 0){throw "Unbalanced production declaration: $signature"}
    return $source.Substring($start,$end-$start)
}
function Require([bool]$condition,[string]$reason) { if(!$condition){throw $reason} }
function ValidateSource([string]$source) {
    Require ($source.Contains('termCell.setTopPadding(16);') -and $source.Contains('termCell.setBottomPadding(16);')) 'A standalone subscription footer section must have symmetric 16dp vertical content insets.'
    $feature=Body $source 'public static final class FeatureCell extends FrameLayout'
    $choice=Body $source 'public static final class PeriodChoiceCell extends FrameLayout'
    $dialog=Body $source 'private void showPeriodDialog()'
    Require ($feature.Contains('setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))')) 'Feature rows must override this RecyclerView manager WRAP_CONTENT width default.'
    foreach($cell in @($feature,$choice)){
        $measure=Body $cell 'protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)'
        Require ($measure.Contains('MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY)')) 'Cells must enforce the offered width, not intrinsic text width.'
        Require ($measure.Contains('MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)')) 'Cell height must not be fixed or clipped to parent AT_MOST.'
        Require ($cell -notmatch 'setSingleLine\(true\)|setMaxLines\(|setLines\(|setEllipsize\(') 'Scalable row text may not be single-line, line-capped or ellipsized.'
    }
    Require ($feature.Contains('setMinimumHeight(AndroidUtilities.dp(choice ? 64 : 76))')) 'Feature minimum height must distinguish compact choices, without fixing total height.'
    Require ($feature.Contains('title.setTextSize(16)') -and $feature.Contains('description.setTextSize(14)')) 'Feature title/detail must use scalable 16/14 sp defaults.'
    Require ($feature.Contains('addView(text, LayoutHelper.createFrame(-1, -2, Gravity.TOP,')) 'Feature text needs bounded width and content height.'
    Require ($choice.Contains('setMinimumHeight(AndroidUtilities.dp(64))')) 'Duration row must keep the 64dp minimum touch target.'
    Require ($choice.Contains('text.setOrientation(LinearLayout.VERTICAL)')) 'Duration title and price must occupy separate lines.'
    foreach($name in @('titleView','detailView')){
        Require ($choice.Contains("$name.setSingleLine(false)")) 'Duration text must explicitly wrap.'
        Require ($choice.Contains("$name.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)")) 'Both duration labels need locale-aware gravity.'
    }
    Require ($choice.Contains('titleView.setTextSize(16)') -and $choice.Contains('detailView.setTextSize(14)')) 'Duration text must use scalable 16/14 sp defaults.'
    Require ($choice.Contains('new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)') -and $choice.Contains('addView(text, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP,')) 'Duration text needs bounded width and auto height.'
    Require ($choice.Contains('LocaleController.isRTL ? 21 : 54') -and $choice.Contains('LocaleController.isRTL ? 54 : 21')) 'Duration text must reserve mirrored radio gutters.'
    Require ($choice.Contains('radioButton = new RadioButton(context)') -and $choice.Contains('radioButton.setChecked(checked, false)')) 'Use native Telegram radio control and selected state.'
    Require ($choice.Contains('setContentDescription(title + ". " + detail)')) 'Accessible row must announce both term and total.'
    Require ($dialog.Contains('for (Period period : LunagramSubscriptionCatalog.PERIODS)') -and $dialog.Contains('new PeriodChoiceCell(')) 'Duration dialog must use structured rows for every catalog term.'
    Require ($dialog.Contains('periodTitle(period), periodPrice(selectedPlan, period), period == selectedPeriod')) 'Duration dialog must separate term and total price.'
    Require ($dialog -notmatch 'createSingleChoiceDialog|String\[\] options|totalPrice\(') 'Old one-line term-plus-price dialog must not return.'
    Require ($dialog.Contains('options.addView(cell, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))')) 'Dialog row must fill available width and wrap height.'
    $click=Body $source 'private void onItemClick(UItem item, View view, int position, float x, float y)'
    Require ($click -match 'item.id == ROW_SELECT_PERIOD\)\s*\{\s*showPeriodDialog\(\);') 'Duration row must open the structured choice dialog.'
}
ValidateSource $screen
$feature=Body $screen 'public static final class FeatureCell extends FrameLayout'
$choice=Body $screen 'public static final class PeriodChoiceCell extends FrameLayout'
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'subscription-layout204/SubscriptionLayout204Probe.java.template'))
$fixture=$template.Replace('/* ACTUAL_FEATURE_MEASURE */',(Body $feature 'protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)'))
$fixture=$fixture.Replace('/* ACTUAL_PERIOD_MEASURE */',(Body $choice 'protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)'))
$fixture=$fixture.Replace('/* ACTUAL_PERIOD_ACCESSIBILITY */',(Body $choice 'public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info)'))
foreach($pair in @(
    @('/* ACTUAL_DIALOG */','private void showPeriodDialog()'),
    @('/* ACTUAL_PERIOD_TITLE */','private String periodTitle(Period period)'),
    @('/* ACTUAL_PERIOD_PRICE */','private String periodPrice(Plan plan, Period period)'))){
    $fixture=$fixture.Replace($pair[0],(Body $screen $pair[1]))
}
function JavaString([string]$value){return '"'+$value.Replace('\','\\').Replace('"','\"').Replace("`r",'\r').Replace("`n",'\n')+'"'}
$localized=@{}
foreach($locale in @('values','values-ru')){
    $strings=[IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/res/$locale/strings.xml"))
    $localized[$locale]=@{}
    foreach($key in @('LunagramSubscriptionPeriod1','LunagramSubscriptionPeriod3','LunagramSubscriptionPeriod6','LunagramSubscriptionPeriod12','LunagramSubscriptionPeriodPrice')){
        $matches=[regex]::Matches($strings,'<string name="'+$key+'">(.*?)</string>')
        Require ($matches.Count -eq 1) "Expected exactly one $locale resource: $key"
        $value=[System.Net.WebUtility]::HtmlDecode($matches[0].Groups[1].Value)
        $localized[$locale][$key]=$value
    }
    $price=$localized[$locale]['LunagramSubscriptionPeriodPrice']
    Require ($price.Contains('%1$d') -and $price.Contains('%2$s')) 'Price resource must preserve both numeric totals.'
    Require (!$price.Contains('%3$')) 'Price detail must not repeat duration into a long single line.'
    Require ($price.Contains($(if($locale -eq 'values'){'total'}else{'всего'}))) 'Price detail must explicitly identify the whole-term total.'
}
$resources=''
foreach($key in $localized['values'].Keys|Sort-Object){
    $resources+='if (id == R.string.'+$key+') return LocaleController.ru ? '+(JavaString $localized['values-ru'][$key])+' : '+(JavaString $localized['values'][$key])+";`n"
}
$fixture=$fixture.Replace('/* ACTUAL_LOCALIZED_RESOURCES */',$resources)
$run=Join-Path $output ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force|Out-Null
$probe=Join-Path $run 'SubscriptionLayout204Probe.java'
[IO.File]::WriteAllText($probe,$fixture,[Text.UTF8Encoding]::new($false))
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix); $java=Join-Path $JavaHome ('bin/java'+$suffix)
& $javac -J-Xmx128m -encoding UTF-8 -d $run $probe (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramSubscriptionCatalog.java')
if($LASTEXITCODE -ne 0){throw 'Extracted subscription layout method compilation failed.'}
& $java -Xmx128m -cp $run SubscriptionLayout204Probe
if($LASTEXITCODE -ne 0){throw 'Subscription layout host behavior failed.'}
# Mutation controls operate only on in-memory/generated test copies, never app source.
$sourceMutations=@(
    @{Name='flush-footer-top';From='termCell.setTopPadding(16);';To='termCell.setTopPadding(0);'},
    @{Name='manager-wrap-width';From='setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));';To='setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));'},
    @{Name='missing-exact-width';From='MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY)';To='widthMeasureSpec'},
    @{Name='fixed-text-height';From='addView(text, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP,';To='addView(text, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP,'},
    @{Name='single-line-price';From='detailView.setSingleLine(false);';To='detailView.setSingleLine(true);'},
    @{Name='unbounded-text-width';From='addView(text, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP,';To='addView(text, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP,'},
    @{Name='legacy-dialog';From='periodTitle(period), periodPrice(selectedPlan, period), period == selectedPeriod';To='periodTitle(period) + " · " + totalPrice(selectedPlan, period), "", period == selectedPeriod'}
)
foreach($mutation in $sourceMutations){
    Require ($screen.Contains($mutation.From)) "Missing source negative-control seam: $($mutation.Name)"
    $failure=$null
    try{ValidateSource ($screen.Replace($mutation.From,$mutation.To))}catch{$failure=$_.Exception.Message}
    Require ($null -ne $failure) "Source negative control unexpectedly passed: $($mutation.Name)"
    [IO.File]::WriteAllText((Join-Path $run ($mutation.Name+'-expected-failure.txt')),$failure,[Text.UTF8Encoding]::new($false))
}
$behaviorMutations=@(
    @{Name='intrinsic-width-forwarded';From='MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY)';To='widthMeasureSpec'},
    @{Name='fixed-height-forwarded';From='MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)';To='heightMeasureSpec'},
    @{Name='stale-dialog-selection';From='if (client == null || !client.isAccountCurrent() || isPaused || isFinished) return;';To='if (false) return;'},
    @{Name='wrong-accessible-checked';From='info.setChecked(radioButton.isChecked());';To='info.setChecked(false);'}
)
foreach($mutation in $behaviorMutations){
    Require ($fixture.Contains($mutation.From)) "Missing behavior negative-control seam: $($mutation.Name)"
    $negative=Join-Path $run $mutation.Name
    New-Item -ItemType Directory -Path $negative -Force|Out-Null
    $negativeProbe=Join-Path $negative 'SubscriptionLayout204Probe.java'
    [IO.File]::WriteAllText($negativeProbe,$fixture.Replace($mutation.From,$mutation.To),[Text.UTF8Encoding]::new($false))
    & $javac -J-Xmx128m -encoding UTF-8 -cp $run -d $negative $negativeProbe
    if($LASTEXITCODE -ne 0){throw "Behavior negative control must compile: $($mutation.Name)"}
    $negativeOutput=& $java -Xmx128m -cp ($negative+[IO.Path]::PathSeparator+$run) SubscriptionLayout204Probe 2>&1
    if($LASTEXITCODE -eq 0){throw "Behavior negative control unexpectedly passed: $($mutation.Name)"}
    [IO.File]::WriteAllLines((Join-Path $negative 'expected-failure.txt'),[string[]]$negativeOutput,[Text.UTF8Encoding]::new($false))
}
Write-Output 'PASS: 7 source and 4 compiled behavior negative controls reject flush footer text, width/overflow errors, clipped heights and stale dialog state.'
Write-Output 'Scope: source contracts and extracted methods with modeled Views; native screenshot/font-scale/RTL rendering remains unverified.'
Write-Output "Test artifacts: $run"
