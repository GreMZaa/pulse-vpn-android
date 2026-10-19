$ws = New-Object -ComObject WScript.Shell
$desktop = [System.Environment]::GetFolderPath('Desktop')
$baseDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$lnkPath = Join-Path $desktop "Pulse VPN.lnk"
$icoPath = Join-Path $baseDir "windows_client\app_icon.ico"
$sc = $ws.CreateShortcut($lnkPath)
$sc.TargetPath = Join-Path $baseDir "START_VPN_PC.bat"
$sc.WorkingDirectory = $baseDir
$sc.Description = "Pulse VPN - Windows Client"
if (Test-Path $icoPath) {
    $sc.IconLocation = "$icoPath,0"
}
$sc.Save()
Write-Host "[✓] Desktop shortcut with icon created at: $lnkPath"
