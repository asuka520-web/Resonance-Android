param(
    [string]$Python = 'python',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'dist'),
    [string]$AppName = 'Resonance-PC'
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & $Python -m unittest discover -s tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed; packaging stopped.' }
    & $Python -m PyInstaller --noconfirm --clean --onefile --windowed `
        --name $AppName --collect-submodules winrt `
        --distpath $OutputDirectory --workpath (Join-Path $PSScriptRoot 'build') `
        --specpath (Join-Path $PSScriptRoot 'build') app.py
    if ($LASTEXITCODE -ne 0) { throw 'Packaging failed.' }
}
finally { Pop-Location }
