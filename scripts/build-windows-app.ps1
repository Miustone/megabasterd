param(
    [string]$JdkHome = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot

if (-not $JdkHome) {
    $candidates = @(
        $env:JAVA_HOME,
        "$env:USERPROFILE\tools\jdk-25"
    ) + (Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium\jdk-25*","$env:ProgramFiles\Java\jdk-25*","$env:ProgramFiles\Microsoft\jdk-25*" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)

    foreach ($c in $candidates) {
        if ($c -and (Test-Path "$c\bin\jpackage.exe")) {
            $JdkHome = $c
            break
        }
    }
}

if (-not $JdkHome -or -not (Test-Path "$JdkHome\bin\jpackage.exe")) {
    throw "JDK 25 with jpackage.exe not found. Pass -JdkHome <path-to-jdk-25>."
}

$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"

Push-Location $RepoRoot
try {
    & "$RepoRoot\mvnw.cmd" -q -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package build failed with exit code $LASTEXITCODE"
    }

    $inputDir = "$RepoRoot\target\jpackage-input"
    $destDir = "$RepoRoot\target\dist"
    Remove-Item -Recurse -Force $inputDir -ErrorAction SilentlyContinue
    if (Test-Path "$destDir\MegaBasterd") {
        try {
            Remove-Item -Recurse -Force "$destDir\MegaBasterd" -ErrorAction Stop
        } catch {
            throw "Cannot replace $destDir\MegaBasterd (close running MegaBasterd.exe first): $($_.Exception.Message)"
        }
    }
    New-Item -ItemType Directory -Force -Path $inputDir,$destDir | Out-Null

    $jarName = "MegaBasterd-9.0.0-jar-with-dependencies.jar"
    Copy-Item "$RepoRoot\target\$jarName" "$inputDir\$jarName"

    $jpackageArgs = @(
        "--type", "app-image",
        "--name", "MegaBasterd",
        "--app-version", "9.0.0",
        "--vendor", "Miustone / tonikelope",
        "--description", "MegaBasterd 9.0 (Java 25 + WinUI 3)",
        "--input", $inputDir,
        "--main-jar", $jarName,
        "--main-class", "com.tonikelope.megabasterd.MainPanel",
        "--dest", $destDir,
        "--icon", "$RepoRoot\src\main\resources\images\pica_roja_big.ico",
        "--java-options", "--enable-native-access=ALL-UNNAMED"
    )

    & "$JdkHome\bin\jpackage.exe" @jpackageArgs
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed with exit code $LASTEXITCODE"
    }

    Write-Host "Built standalone Windows app: $destDir\MegaBasterd\MegaBasterd.exe"
} finally {
    Pop-Location
}
