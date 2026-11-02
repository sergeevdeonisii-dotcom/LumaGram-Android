param([string]$JavaHome=$env:JAVA_HOME,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/public-updater-install-routing'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$allowed=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.runs'))+[IO.Path]::DirectorySeparatorChar
$output=[IO.Path]::GetFullPath($OutputRoot)
if (!$output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)) { throw 'Test output must stay in Tools/tests/.runs.' }
$javac=Join-Path $JavaHome 'bin/javac.exe';$java=Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $javac)) { throw 'Pass existing JDK17 -JavaHome.' }
$source=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj_AppStandalone/src/main/java/org/telegram/ui/Components/UpdateLayout.java'))
$start=$source.IndexOf('updateLayout.setOnClickListener(v -> {',[StringComparison]::Ordinal)
if ($start -lt 0) { throw 'Actual updater banner click handler unavailable.' }
$open=$source.IndexOf('{',$start);$depth=1
for ($end=$open+1;$end -lt $source.Length -and $depth -gt 0;$end++) {
    if ($source[$end] -eq '{') { $depth++ };if ($source[$end] -eq '}') { $depth-- }
}
if ($depth -ne 0) { throw 'Unclosed updater banner click handler.' }
$body=$source.Substring($open+1,$end-$open-2)
if (!$body.Contains('LumaUpdaterController.getInstance().install(activity);') -or $source.Contains('AndroidUtilities.openForView(')) {
    throw 'All banner installation must go through controller verification, never directly open cached APK.'
}
$template=@'
import java.io.File;
public final class PublicUpdaterInstallRoutingProbe {
    static int checks;
    static void check(boolean ok,String message) { checks++;if(!ok)throw new AssertionError(message); }
    static final class MediaActionDrawable { static final int ICON_DOWNLOAD=1,ICON_CANCEL=2,ICON_UPDATE=3; }
    static final class Icon { int value;int getIcon(){return value;} }
    static final class ApplicationLoader {
        static final ApplicationLoader applicationLoaderInstance=new ApplicationLoader();
        int downloads,cancels,cacheReads;
        void downloadUpdate(){downloads++;}
        void cancelDownloadingUpdate(){cancels++;}
        File getDownloadedUpdateFile(){cacheReads++;return new File("untrusted-cache.apk");}
    }
    static final class LumaUpdaterController {
        static final LumaUpdaterController instance=new LumaUpdaterController();
        static LumaUpdaterController getInstance(){return instance;}
        int installs;Object owner;boolean result=true;
        boolean install(Object activity){installs++;owner=activity;return result;}
    }
    static final class AndroidUtilities {
        static boolean openForView(File file,String name,String mime,Object activity,Object provider,boolean external) {
            throw new AssertionError("Direct APK installation bypasses verifier");
        }
    }
    final Icon updateLayoutIcon=new Icon();final Object activity=new Object();
    int currentAccount=3,refreshes;
    void updateAppUpdateViews(int account,boolean animated){check(account==3&&animated,"unchanged banner refresh");refreshes++;}
    void click(){/* ACTUAL_CLICK */}
    public static void main(String[] args) {
        PublicUpdaterInstallRoutingProbe view=new PublicUpdaterInstallRoutingProbe();
        ApplicationLoader app=ApplicationLoader.applicationLoaderInstance;
        LumaUpdaterController controller=LumaUpdaterController.getInstance();
        view.updateLayoutIcon.value=MediaActionDrawable.ICON_DOWNLOAD;view.click();
        check(app.downloads==1&&app.cancels==0&&controller.installs==0,"download route unchanged");
        view.updateLayoutIcon.value=MediaActionDrawable.ICON_CANCEL;view.click();
        check(app.downloads==1&&app.cancels==1&&controller.installs==0,"cancel route unchanged");
        check(view.refreshes==2,"download and cancel refresh banner");
        view.updateLayoutIcon.value=MediaActionDrawable.ICON_UPDATE;view.click();
        check(controller.installs==1&&controller.owner==view.activity,"install delegates owning Activity to verified controller");
        check(app.cacheReads==0,"install does not inspect or directly expose persisted APK path");
        check(view.refreshes==2,"verification remains asynchronous and controller-owned");
        controller.result=false;view.click();
        check(controller.installs==2&&app.cacheReads==0,"denied install never falls back to raw cache opening");
        check(app.downloads==1&&app.cancels==1,"install does not silently download or cancel another attempt");
        System.out.println("PASS updater banner routing: "+checks+" actual-extracted handler checks; modeled controller, not device proof.");
    }
}
'@
$run=Join-Path $output ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$probe=Join-Path $run 'PublicUpdaterInstallRoutingProbe.java'
[IO.File]::WriteAllText($probe,$template.Replace('/* ACTUAL_CLICK */',$body),[Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $run $probe
if ($LASTEXITCODE -ne 0) { throw 'Actual banner handler compilation failed.' }
& $java -Xmx64m -cp $run PublicUpdaterInstallRoutingProbe
if ($LASTEXITCODE -ne 0) { throw 'Verified banner install routing regression.' }
# Independent negative control: substituting the former raw-cache path must fail.
$unsafe=$body.Replace('LumaUpdaterController.getInstance().install(activity);',
    'AndroidUtilities.openForView(ApplicationLoader.applicationLoaderInstance.getDownloadedUpdateFile(), "Luma.apk", "application/vnd.android.package-archive", activity, null, false);')
$negative=Join-Path $run 'negative'
New-Item -ItemType Directory -Path $negative | Out-Null
$negativeProbe=Join-Path $negative 'PublicUpdaterInstallRoutingProbe.java'
[IO.File]::WriteAllText($negativeProbe,$template.Replace('/* ACTUAL_CLICK */',$unsafe),[Text.UTF8Encoding]::new($false))
& $javac -J-Xmx128m -encoding UTF-8 -d $negative $negativeProbe
if ($LASTEXITCODE -ne 0) { throw 'Negative control must compile.' }
$negativeOutput=(& $java -Xmx64m -cp $negative PublicUpdaterInstallRoutingProbe 2>&1 | Out-String)
if ($LASTEXITCODE -eq 0 -or !$negativeOutput.Contains('Direct APK installation bypasses verifier')) { throw 'Unsafe direct-cache negative control was not detected.' }
Write-Output 'PASS negative control rejects the former direct APK opening path.'
Write-Output "Artifacts: $run"
exit 0
