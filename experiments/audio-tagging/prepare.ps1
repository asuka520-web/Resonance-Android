param([string]$ModelDirectory=(Join-Path $PSScriptRoot '.cache'))
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path $ModelDirectory | Out-Null
$modelFile=Join-Path $ModelDirectory 'msd-musicnn-1.onnx'
$metadataFile=Join-Path $ModelDirectory 'msd-musicnn-1.json'
if(-not(Test-Path -LiteralPath $modelFile)) {
    Invoke-WebRequest -Uri 'https://essentia.upf.edu/models/feature-extractors/musicnn/msd-musicnn-1.onnx' -OutFile $modelFile
}
if((Get-FileHash -LiteralPath $modelFile -Algorithm SHA256).Hash -ne '49668FFEC47E52E94B96F45930BB46A28A1368D4BDFB5C05378FA834ACA616E1'){throw 'Model checksum mismatch'}
if(-not(Test-Path -LiteralPath $metadataFile)) {
    Invoke-WebRequest -Uri 'https://essentia.upf.edu/models/feature-extractors/musicnn/msd-musicnn-1.json' -OutFile $metadataFile
}
if((Get-FileHash -LiteralPath $metadataFile -Algorithm SHA256).Hash -ne '8E6B3B509F0610C0E65DCE467FD459D6777509388EADDB13ED138D8AC1341FFE'){throw 'Metadata checksum mismatch'}
Push-Location -LiteralPath $PSScriptRoot
try {npm.cmd ci --no-audit --no-fund;if($LASTEXITCODE -ne 0){throw 'Dependency installation failed'}}finally{Pop-Location}
Write-Output 'Local probe dependencies and official model verified. No app data was modified.'
