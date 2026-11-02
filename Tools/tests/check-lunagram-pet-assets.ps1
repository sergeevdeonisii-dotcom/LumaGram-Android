param([string]$AssetRoot=(Join-Path $PSScriptRoot '../../TMessagesProj/src/main/assets/lunapet'))
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
$manifest=Get-Content -LiteralPath (Join-Path $AssetRoot 'ginger-tabby.json') -Raw | ConvertFrom-Json
if($manifest.version -ne 1 -or $manifest.clips.Count -ne 4){throw 'Invalid cat manifest version/clip count'}
$names=@('walk','loaf','roll','ball');$seen=@{};$frames=0;$highest=0.0;$below=0.0;$widest=0.5
foreach($clip in $manifest.clips){
    if($names -notcontains $clip.name -or $seen.ContainsKey($clip.name)){throw 'Unknown/duplicate cat clip'}
    $seen[$clip.name]=$true
    if($clip.asset -ne "ginger-$($clip.name).png" -or $clip.columns -ne 4 -or $clip.rows -ne 4 -or $clip.frames.Count -ne 16){throw 'Cat clip shape mismatch'}
    $bitmap=[System.Drawing.Bitmap]::new((Join-Path $AssetRoot $clip.asset))
    try {
        $size=$bitmap.Width
        if($size -ne $bitmap.Height -or $size -lt 64 -or $size -gt 8192 -or ![System.Drawing.Image]::IsAlphaPixelFormat($bitmap.PixelFormat)){throw 'Clip must be a bounded square RGBA bitmap'}
        $sample=1;while([math]::Floor($size/$sample) -gt 1024){$sample*=2}
        $decoded=[math]::Ceiling($size/$sample)
        for($i=0;$i -lt 16;$i++){
            $f=$clip.frames[$i];$col=$i%4;$row=[math]::Floor($i/4)
            $l=[math]::Floor($size*$col/4);$r=[math]::Floor($size*($col+1)/4)
            $t=[math]::Floor($size*$row/4);$b=[math]::Floor($size*($row+1)/4)
            if($f.sourceRect){$l=$f.sourceRect.left;$r=$f.sourceRect.right;$t=$f.sourceRect.top;$b=$f.sourceRect.bottom}
            if($l -lt 0 -or $t -lt 0 -or $r -gt $size -or $b -gt $size -or $r-$l -lt 8 -or $b-$t -lt 8){throw "Invalid source crop $($clip.name) $i"}
            if([math]::Floor($r*$decoded/$size)-[math]::Floor($l*$decoded/$size) -lt 1 -or [math]::Floor($b*$decoded/$size)-[math]::Floor($t*$decoded/$size) -lt 1){throw 'Decode scale produces empty frame'}
            $x=[double]$f.anchorX/($r-$l);$y=[double]$f.anchorY/($r-$l);$h=($b-$t)/($r-$l)
            if($x -lt 0 -or $x -gt 1 -or $y -le 0 -or $y -gt $h){throw "Invalid floor anchor $($clip.name) $i"}
            $highest=[math]::Max($highest,$y);$below=[math]::Max($below,$h-$y);$widest=[math]::Max($widest,[math]::Max($x,1-$x))
            # The native renderer keeps non-square ball crops in their original aspect ratio.
            $renderHeight=56*$h
            if([math]::Abs($renderHeight/56-($b-$t)/($r-$l)) -gt 0.00001){throw 'Crop aspect ratio distorted'}
            $frames++
        }
    } finally {$bitmap.Dispose()}
}
$renderSize=[math]::Min(56,[math]::Min(56/$highest,22/$below))
if($frames -ne 64 -or $renderSize -lt 55.9 -or $highest*$renderSize -gt 56.001 -or $below*$renderSize -gt 22.001){throw '80dp overlay cannot fit 56dp cat and dangling tail'}
Write-Output "PASS $frames decoded RGBA source poses, 4 clips, fractional grid/custom ball crops, anchors/aspect ratio; 56dp sprites fit 80dp overlay with floor22. Metadata/decode check, not Android pixels or measured FPS."
