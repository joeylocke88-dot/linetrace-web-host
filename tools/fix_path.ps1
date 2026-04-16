$sdkPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools"
if (Test-Path $sdkPath) {
    $currentPath = [System.Environment]::GetEnvironmentVariable("PATH", "User")
    if ($currentPath -notlike "*$sdkPath*") {
        $newPath = $currentPath + ";" + $sdkPath
        [System.Environment]::SetEnvironmentVariable("PATH", $newPath, "User")
        Write-Host "PATH updated successfully. Please restart Android Studio or your terminal for changes to take effect."
    } else {
        Write-Host "PATH already contains the Android SDK platform-tools."
    }
} else {
    Write-Error "Android SDK platform-tools not found at $sdkPath"
}
