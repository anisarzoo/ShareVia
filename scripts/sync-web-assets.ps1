param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$sourceDir = Join-Path $Root "web"
$files = @(
    "index.html",
    "style.css",
    "app.js",
    "peerjs.min.js",
    "qrcode.min.js",
    "html5-qrcode.min.js",
    "manifest.webmanifest",
    "sw.js",
    "icon128.png"
)

$targets = @(
    (Join-Path $Root "android\app\src\main\assets")
    (Join-Path $Root "ios\P2PShare\WebAssets")
    (Join-Path $Root "windows\P2PShare.Windows\Assets\Web")
)

if (!(Test-Path $sourceDir)) {
    throw "Web source folder not found: $sourceDir"
}

foreach ($target in $targets) {
    if (!(Test-Path $target)) {
        New-Item -ItemType Directory -Force -Path $target | Out-Null
    }

    foreach ($file in $files) {
        $sourceFile = Join-Path $sourceDir $file

        if (Test-Path $sourceFile) {
            Copy-Item -LiteralPath $sourceFile -Destination (Join-Path $target $file) -Force
        }
    }

    Write-Output "Synced web bundle -> $target"
}
