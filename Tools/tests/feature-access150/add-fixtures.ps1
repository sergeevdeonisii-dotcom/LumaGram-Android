function Add-FeatureAccessFixtures([string[]]$Sources, [string]$Repo) {
    # Kept outside common fixture discovery: only suites compiling guarded models opt in.
    $result = @($Sources)
    $names = @($Sources | ForEach-Object { [IO.Path]::GetFileName($_) })
    if ('LunagramFeatureCatalog.java' -notin $names) {
        $result += Join-Path $Repo 'TMessagesProj/src/main/java/org/telegram/messenger/LunagramFeatureCatalog.java'
    }
    if ('LunagramSubscriptionAccess.java' -notin $names) {
        $result += Join-Path $PSScriptRoot 'LunagramSubscriptionAccess.java'
    }
    if ('LumaExportSession.java' -in $names -and 'LunagramSubscriptionClient.java' -notin $names) {
        $result += Join-Path $PSScriptRoot 'LunagramSubscriptionClient.java'
    }
    return $result
}
