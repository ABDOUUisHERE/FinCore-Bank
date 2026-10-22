$ErrorActionPreference = 'Stop'
python (Join-Path $PSScriptRoot 'bootstrap-local.py')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
