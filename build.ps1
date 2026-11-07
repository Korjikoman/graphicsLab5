param([switch]$Run, [switch]$Test)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
Set-Location -LiteralPath $projectRoot

# Сначала используем JDK из JAVA_HOME; если он не задан, ищем программы в PATH.
function Find-JavaTool([string]$toolName) {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin/$toolName.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $command = Get-Command "$toolName.exe" -ErrorAction SilentlyContinue
    if (!$command) { throw "JDK 17+ required: $toolName.exe was not found. Set JAVA_HOME or PATH." }
    return $command.Source
}

$compiler = Find-JavaTool 'javac'
$java = Find-JavaTool 'java'
$jar = Find-JavaTool 'jar'
$classes = Join-Path $projectRoot 'build/classes'
$testClasses = Join-Path $projectRoot 'build/test-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/main/java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& $compiler --release 17 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }

$jarPath = Join-Path $projectRoot 'PhotoLab.jar'
# Keep the JAR tool's temporary file inside the project, on the same filesystem.
& $jar "-J-Djava.io.tmpdir=$classes" --create --file $jarPath --main-class ru.graphics.lab5.PhotoEditor -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed.' }
Write-Host "Built: $jarPath"

if ($Test) {
    New-Item -ItemType Directory -Force -Path $testClasses | Out-Null
    $testSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/test/java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
    & $compiler --release 17 -encoding UTF-8 -cp $classes -d $testClasses @testSources
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    & $java '-Djava.awt.headless=true' -cp "$classes;$testClasses" ru.graphics.lab5.ImageProcessorTest
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
}

if ($Run) {
    & $java -jar $jarPath
    if ($LASTEXITCODE -ne 0) { throw 'Application failed.' }
}
