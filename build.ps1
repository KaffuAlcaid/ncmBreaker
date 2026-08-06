param()

$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$jdkRoot = Join-Path $projectRoot 'Java\zulu-jdk-25-win'
$javac = Join-Path $jdkRoot 'bin\javac.exe'
$jar = Join-Path $jdkRoot 'bin\jar.exe'
$sourceRoot = Join-Path $projectRoot 'src\main\java'
$buildRoot = Join-Path $projectRoot 'build'
$classesRoot = Join-Path $buildRoot 'classes'
$distRoot = Join-Path $projectRoot 'dist'
$jarPath = Join-Path $distRoot 'ncm-breaker.jar'

if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $jar)) {
    throw 'Windows Zulu JDK 25 was not found under Java\zulu-jdk-25-win.'
}

if (Test-Path -LiteralPath $classesRoot) {
    Remove-Item -LiteralPath $classesRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $classesRoot -Force | Out-Null
New-Item -ItemType Directory -Path $distRoot -Force | Out-Null

$sources = Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
if (-not $sources) {
    throw 'No Java source files were found.'
}

& $javac --release 17 -encoding UTF-8 -d $classesRoot $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE."
}

& $jar --create --file $jarPath --main-class com.ncmbreaker.Main -C $classesRoot .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE."
}

Write-Host "Built $jarPath"
