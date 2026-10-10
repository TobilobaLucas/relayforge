$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) {
    Write-Output '.env already exists. Keeping your existing credentials.'
    exit 0
}
function New-RandomBase64 {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($bytes)
}
$values = @(
    'RELAY_ADMIN_TOKEN=' + (New-RandomBase64)
    'RELAY_ENCRYPTION_KEY=' + (New-RandomBase64)
    'DATABASE_PASSWORD=' + (New-RandomBase64)
)
[IO.File]::WriteAllLines($envPath, $values, (New-Object Text.UTF8Encoding($false)))
Write-Output 'Created .env with random local credentials. This file is ignored by Git.'
