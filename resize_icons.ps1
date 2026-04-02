Add-Type -AssemblyName System.Drawing

function Resize-Image {
    param(
        [string]$SourcePath,
        [string]$TargetPath,
        [int]$Width,
        [int]$Height
    )
    $srcImg = [System.Drawing.Image]::FromFile($SourcePath)
    $destImg = New-Object System.Drawing.Bitmap($Width, $Height)
    $graphics = [System.Drawing.Graphics]::FromImage($destImg)
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.DrawImage($srcImg, 0, 0, $Width, $Height)
    $destImg.Save($TargetPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose()
    $destImg.Dispose()
    $srcImg.Dispose()
}

$favicon = ".\favicon.png"
$androidIcon = ".\android_icon.png"

# Web Icons
Resize-Image -SourcePath $favicon -TargetPath ".\web\icon16.png" -Width 16 -Height 16
Resize-Image -SourcePath $favicon -TargetPath ".\web\icon48.png" -Width 48 -Height 48
Resize-Image -SourcePath $favicon -TargetPath ".\web\icon128.png" -Width 128 -Height 128
Resize-Image -SourcePath $favicon -TargetPath ".\web\icon512.png" -Width 512 -Height 512
Copy-Item $favicon ".\web\favicon.png" -Force

# Extension Icons
Resize-Image -SourcePath $favicon -TargetPath ".\extension\icon16.png" -Width 16 -Height 16
Resize-Image -SourcePath $favicon -TargetPath ".\extension\icon48.png" -Width 48 -Height 48
Resize-Image -SourcePath $favicon -TargetPath ".\extension\icon128.png" -Width 128 -Height 128

# Android Icons
# mdpi = 48, hdpi = 72, xhdpi = 96, xxhdpi = 144, xxxhdpi = 192
$mipmapSizes = @{
    "mipmap-mdpi" = 48;
    "mipmap-hdpi" = 72;
    "mipmap-xhdpi" = 96;
    "mipmap-xxhdpi" = 144;
    "mipmap-xxxhdpi" = 192
}

foreach ($folder in $mipmapSizes.Keys) {
    $size = $mipmapSizes[$folder]
    $dir = ".\android\app\src\main\res\$folder"
    if (Test-Path $dir) {
        Resize-Image -SourcePath $androidIcon -TargetPath "$dir\ic_launcher.png" -Width $size -Height $size
        Resize-Image -SourcePath $androidIcon -TargetPath "$dir\ic_launcher_round.png" -Width $size -Height $size
    }
}
Write-Host "Icons generated successfully."
