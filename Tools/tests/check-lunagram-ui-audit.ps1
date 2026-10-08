param([string]$SourceRevision = '')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function Source([string]$path) {
    if ($SourceRevision) {
        $text = (& git -C $repo show ($SourceRevision + ':' + $path)) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "Cannot read comparison source: $path" }
        return $text
    }
    return [IO.File]::ReadAllText((Join-Path $repo $path))
}
function Check([bool]$condition, [string]$description) {
    if (!$condition) { throw $description }
    Write-Output "PASS: $description"
}
$ui = 'TMessagesProj/src/main/java/org/telegram/ui/'
$search = Source ($ui + 'BlackHoleSearchActivity.java')
Check ($search.Contains('TextDetailCell.Factory.of(n + 1, name, path)')) 'Search puts destination below title, not in its horizontal space'
Check ($search.Contains('catalog.get(item.id - 1).destination')) 'Two-line search rows preserve destination IDs'
Check ($search -match 'createView\(Context context\)\s*\{\s*openedSearch = false;' -and $search.Contains('actionBar.openSearchField(query, true)')) 'Recreated search view restores its query'
Check ($search.Contains('screen.setCurrentAccount(currentAccount)') -and $search.Contains('destination == 4 && !LumaBuildPolicy.allowsBuiltInUpdates()') -and $search.Contains('destination == 101 && !LumaBuildPolicy.allowsPrivacyTools()')) 'Search keeps account routing and edition gates'

$tools = Source ($ui + 'BlackHoleToolsActivity.java')
Check ($tools.Contains('TextDetailCell.Factory.of(200 + n, e.text,') -and $tools.Contains('row.object = e;')) 'Journal separates preview/date and preserves the selected entry'
Check ($tools.Contains('LumaDialogInput.create(getParentActivity(),') -and $tools.Contains('InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES')) 'Notes use the themed multiline field'
Check ($tools.Contains('InputFilter.LengthFilter(BlackHoleNotes.MAX_LENGTH)') -and $tools.Contains('field.setMinLines(3); field.setMaxLines(8);') -and $tools.Contains('setView(LumaDialogInput.wrap(field))')) 'Notes retain length/line limits and get external dialog margins'
Check ($tools.Contains('if (!currentOwner() || needsUnlock() || BlackHoleVault.blocks(currentAccount, did)) return;') -and $tools.Contains('showDialog(new AlertDialog.Builder')) 'Notes retain privacy guard and fragment-managed dialog lifecycle'

$advanced = Source ($ui + 'ExperimentalFeaturesActivity.java')
Check ([regex]::Matches($advanced, 'LumaDialogInput\.create\(\s*getParentActivity\(\), InputType.TYPE_CLASS_NUMBER, false, resourceProvider\)').Count -eq 2) 'Both local profile numeric inputs use the active theme'
Check ([regex]::Matches($advanced, 'setView\(LumaDialogInput.wrap\(editText\)\)').Count -eq 2) 'Both numeric dialogs have symmetric external margins'
Check ($advanced.Contains('dialog.setOnShowListener(') -and $advanced.Contains('AndroidUtilities.showKeyboard(editText)') -and $advanced.Contains('EditorInfo.IME_ACTION_DONE') -and $advanced.Contains('dialog.getButton(AlertDialog.BUTTON_POSITIVE)')) 'Numeric dialogs focus on show and route keyboard Done to the same save button'
Check ($advanced -match 'TextDetailCell.Factory.of\(\s*ROW_EMERGENCY_CHAT,' -and $advanced -match 'TextDetailCell.Factory.of\(ROW_ACCOUNT_EXPORT,') 'Connection rows place long descriptions on the second line'
$typing = Source ($ui + 'TextAnimationSettingsActivity.java')
Check ($typing -match 'TextDetailCell.Factory.of\(\s*ROW_AUTO_STYLE,') 'Automatic style name no longer squeezes its setting title'

$input = Source ($ui + 'Components/LumaDialogInput.java')
foreach ($color in @('dialogTextBlack', 'dialogTextHint', 'dialogInputField', 'dialogInputFieldActivated')) {
    Check ($input.Contains("Theme.getColor(Theme.key_$color, resourcesProvider)")) "Dialog input resolves $color through its theme provider"
}
Check ($input.Contains('field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider))') -and $input.Contains('Theme.createEditTextDrawable(context,')) 'Cursor and underline no longer inherit the Activity system theme'
Check ($input.Contains('LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT') -and $input.Contains('field.setSingleLine(!multiline)')) 'Shared input retains RTL and single/multiline layouts'
Check ($input.Contains('Gravity.TOP, 24, 0, 24, 8') -and $input.Contains('EditorInfo.IME_FLAG_NO_EXTRACT_UI')) 'Dialog margins are symmetric and keyboard does not replace the dialog with an extract screen'
Write-Output 'Source-contract checks only; Android compilation and on-device visual verification are separate.'
