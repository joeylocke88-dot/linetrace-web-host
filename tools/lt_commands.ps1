# LineTrace AR - Tactical Command Suite (PowerShell)

function LT-Logcat-NOTFOUND {
    Write-Host "Searching for Depth NOT_FOUND errors..." -ForegroundColor Cyan
    adb logcat -d | Select-String "NOT_FOUND" -Context 1,1
}

function LT-Logcat-VIO {
    Write-Host "Monitoring VisualInertialState..." -ForegroundColor Yellow
    adb logcat -v time | Select-String "VisualInertialState"
}

function LT-Lazarus-Trigger {
    Write-Host "Simulating Intentional Session Stall for Lazarus Recovery..." -ForegroundColor Red
    adb shell am broadcast -a com.linetrace.app.SIMULATE_STALL
}

function LT-Pull-Sessions {
    Write-Host "Pulling Recorded Sessions to Desktop..." -ForegroundColor Green
    $dest = Join-Path $HOME "Desktop/LineTrace_Sessions"
    if (!(Test-Path $dest)) { New-Item -ItemType Directory -Path $dest }
    adb pull /storage/emulated/0/Android/data/com.linetrace.app/files/sessions/ $dest
}

function LT-Thermal-Check {
    Write-Host "Reading Battery Temperature..." -ForegroundColor Magenta
    adb shell dumpsys battery | Select-String "temperature"
}

function LT-Mem-Stress {
    Write-Host "Verifying 1M Surfel Memory Footprint (~413MB PSS)..." -ForegroundColor Cyan
    adb shell dumpsys meminfo com.linetrace.app
}

function LT-Logcat-Fusion {
    Write-Host "Monitoring 100% Frequency Fusion Events..." -ForegroundColor Green
    adb logcat -v time | Select-String "FusionMonitor"
}

function LT-Logcat-Sync {
    Write-Host "Monitoring Cloud Synchronization (Onrender)..." -ForegroundColor Yellow
    adb logcat -v time | Select-String "WorldSync", "ImuNetworkBridge"
}

function LT-Logcat-Hypervisor {
    Write-Host "Monitoring Diagnostic Overlay & Latency..." -ForegroundColor White
    adb logcat -v time | Select-String "LineRenderer", "btnLock"
}

function LT-Build-Deploy {
    Write-Host "Compiling and Deploying Tactical App..." -ForegroundColor Cyan
    ./gradlew.bat installDebug
    adb shell am start -n com.linetrace.app/.MainActivity
}

Write-Host "LineTrace AR PowerShell Tools Loaded." -ForegroundColor Green
Write-Host "Commands: LT-Logcat-NOTFOUND, LT-Logcat-VIO, LT-Lazarus-Trigger, LT-Pull-Sessions, LT-Thermal-Check, LT-Mem-Stress, LT-Logcat-Fusion, LT-Logcat-Sync, LT-Logcat-Hypervisor, LT-Build-Deploy" -ForegroundColor Gray
