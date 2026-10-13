$ErrorActionPreference = "Stop"

$extensionRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $extensionRoot
$artifact = Join-Path $repositoryRoot "target\smartdeptest-component1-1.0-SNAPSHOT.jar"
$dependencies = Join-Path $repositoryRoot "target\dependency"
$engineRoot = Join-Path $extensionRoot "engine"
$engineDependencies = Join-Path $engineRoot "dependency"

if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
    throw "SmartDepTest JAR not found. Run 'mvn package' from the repository root first."
}
if (-not (Test-Path -LiteralPath $dependencies -PathType Container)) {
    throw "Runtime dependencies not found. Run 'mvn package' from the repository root first."
}

New-Item -ItemType Directory -Path $engineDependencies -Force | Out-Null
Copy-Item -LiteralPath $artifact -Destination $engineRoot -Force
Get-ChildItem -LiteralPath $engineDependencies -Filter "*.jar" -File |
    Remove-Item -Force
Copy-Item -Path (Join-Path $dependencies "*.jar") -Destination $engineDependencies -Force
