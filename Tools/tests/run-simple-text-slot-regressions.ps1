param([string]$JavaHome = $env:JAVA_HOME, [switch]$Baseline)
$ErrorActionPreference = 'Stop'
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$path = 'TMessagesProj/src/main/java/org/telegram/ui/ActionBar/SimpleTextView.java'
if ($Baseline) { $source = (& git -C $repo show "HEAD:$path") -join "`n" }
else { $source = [System.IO.File]::ReadAllText((Join-Path $repo $path)) }
$signature = 'public void replaceTextWithDrawable(Drawable drawable, String replacedText)'
$start = $source.IndexOf($signature, [StringComparison]::Ordinal)
if ($start -lt 0) { throw 'Missing production placeholder setter.' }
$open = $source.IndexOf('{', $start); $depth = 1; $method = $null
for ($i = $open + 1; $i -lt $source.Length; $i++) {
    if ($source[$i] -eq '{') { $depth++ }
    if ($source[$i] -eq '}') { $depth-- }
    if ($depth -eq 0) { $method = $source.Substring($start, $i - $start + 1); break }
}
if ($null -eq $method) { throw 'Unclosed production placeholder setter.' }
# Exact production setter, with the immediate/deferred layout contract modeled.
# This demonstrates ordering/ownership, not Android text rasterization.
$harness = @'
public final class SimpleTextSlotRegressionTest {
    static int assertions;
    static void check(boolean ok,String label){assertions++;if(!ok)throw new AssertionError(label);}
    static final class Drawable { Object callback;void setCallback(Object value){callback=value;} }
    static final class TextUtils { static boolean equals(CharSequence a,CharSequence b){return a==b||(a!=null&&b!=null&&a.toString().contentEquals(b));} }
    static final class Slot {
        Drawable replacedDrawable;String replacedText,layoutPlaceholder;
        boolean wasLayout;int rebuilds;boolean requested;
        void invalidate(){}
        boolean recreateLayoutMaybe(){if(wasLayout){layoutPlaceholder=replacedText;rebuilds++;}else requested=true;return true;}
        void layout(){wasLayout=true;requested=false;layoutPlaceholder=replacedText;rebuilds++;}
        // PRODUCTION_METHOD
    }
    public static void main(String[] args){
        Slot s=new Slot();Drawable d=new Drawable();
        s.replaceTextWithDrawable(d,"**oo**");
        check(s.requested&&d.callback==s,"unlaid-out slot requests layout and owns the drawable");
        s.layout();check("**oo**".equals(s.layoutPlaceholder),"first deferred layout sees the placeholder");
        s.replaceTextWithDrawable(null,null);
        check(s.layoutPlaceholder==null&&d.callback==null,"removing an inline drawable immediately removes its old layout placeholder");
        s.replaceTextWithDrawable(d,"**oo**");
        check("**oo**".equals(s.layoutPlaceholder),"repeated mode switch cannot rebuild with a null placeholder");
        s.replaceTextWithDrawable(d,"new-token");
        check("new-token".equals(s.layoutPlaceholder)&&d.callback==s,"same drawable with a new token updates layout while retaining callback");
        int count=s.rebuilds;s.replaceTextWithDrawable(d,new String("new-token"));
        check(count==s.rebuilds,"same content and same drawable remain a no-op");
        Drawable next=new Drawable();s.replaceTextWithDrawable(next,"second-token");
        check(d.callback==null&&next.callback==s,"changed drawable releases only its old owner");
        check("second-token".equals(s.layoutPlaceholder),"new drawable/token are published before layout");
        s.replaceTextWithDrawable(null,"future-token");
        check(next.callback==null&&"future-token".equals(s.layoutPlaceholder),"null drawable can still update slot metadata");
        s.replaceTextWithDrawable(null,null);
        check(s.layoutPlaceholder==null,"same null drawable cannot suppress placeholder cleanup");
        System.out.println("PASS: "+assertions+" SimpleTextView slot assertions (verbatim setter; modeled layout, no Android rendering).");
    }
}
'@
$harness = $harness.Replace('// PRODUCTION_METHOD', $method)
$run = Join-Path $PSScriptRoot ('.runs/' + [guid]::NewGuid().ToString() + '/simple-text-slot')
New-Item -ItemType Directory -Path (Join-Path $run 'classes') | Out-Null
$sourceFile = Join-Path $run 'SimpleTextSlotRegressionTest.java'
[System.IO.File]::WriteAllText($sourceFile, $harness, [System.Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx96m -encoding UTF-8 -d (Join-Path $run 'classes') $sourceFile
if ($LASTEXITCODE -ne 0) { throw 'SimpleTextView slot regression compilation failed.' }
& (Join-Path $JavaHome 'bin/java.exe') -Xmx32m -cp (Join-Path $run 'classes') SimpleTextSlotRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'SimpleTextView slot regressions failed.' }
Write-Output "Test artifacts: $run"
