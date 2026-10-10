param([string]$JavaExe = 'java')
$ErrorActionPreference = 'Stop'
$config = Join-Path $PSScriptRoot '.env'
if (Test-Path -LiteralPath $config) {
    foreach ($line in Get-Content -LiteralPath $config -Encoding utf8) {
        if ($line -match '^\s*(GOOGLE_CLIENT_ID|GOOGLE_CLIENT_SECRET|GOOGLE_REDIRECT_URI|GOOGLE_DRIVE_MAX_UPLOAD_BYTES)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
}
$jar = Join-Path $PSScriptRoot 'target/video-parser-1.0.0.jar'
if (!(Test-Path -LiteralPath $jar)) { $jar = Join-Path $PSScriptRoot '../video-parser-java.jar' }
& $JavaExe -jar $jar
