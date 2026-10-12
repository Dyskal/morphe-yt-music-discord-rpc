param(
    [string]$ToolsRoot = "$env:LOCALAPPDATA\Temp\opencode",
    [string]$Jdk = "$env:LOCALAPPDATA\Temp\opencode\jdk-21.0.12.1+1",
    [string]$Apk,
    [string]$OutputApk
)
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath("$PSScriptRoot\..\..")
foreach ($path in @("$Jdk\bin\java.exe", "$ToolsRoot\android-sdk", "$ToolsRoot\patches-gradle-plugin", "$ToolsRoot\morphe-patcher")) {
    if (!(Test-Path -LiteralPath $path)) { throw "Missing local prerequisite: $path" }
}
$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$env:PATH"
$env:ANDROID_HOME = "$ToolsRoot\android-sdk"
$env:MORPHE_BUILD_DEPS = $ToolsRoot
if (!$env:GITHUB_ACTOR) { $env:GITHUB_ACTOR = 'local' }
if (!$env:GITHUB_TOKEN) { $env:GITHUB_TOKEN = 'local' }
$arguments = @('--project-dir', $root)
if ($Apk) {
    $arguments += @(':patches:validateDiscord', "-PdiscordApk=$Apk")
    if ($OutputApk) { $arguments += "-PdiscordOutput=$OutputApk" }
} else {
    $arguments += @(':patches:buildAndroid', ':patches:test')
}
& "$root\gradlew.bat" @arguments
if ($LASTEXITCODE -ne 0) { throw "Build failed (exit $LASTEXITCODE)" }
