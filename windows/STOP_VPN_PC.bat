@echo off
chcp 65001 >nul
echo [ПУЛЬС ВПН] Остановка туннеля и сброс настроек сети Windows...

powershell -Command "try { Invoke-RestMethod -Uri 'http://127.0.0.1:8000/api/v1/local/disconnect' -Method Post -TimeoutSec 3 } catch {}" >nul 2>&1
taskkill /F /IM xray.exe >nul 2>&1

powershell -Command "Set-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -Name ProxyEnable -Value 0; [System.Runtime.InteropServices.Marshal]::GetLastWin32Error() | Out-Null" >nul 2>&1

echo [✓] Туннель выключен. Системный прокси Windows отключен.
timeout /t 2 >nul
exit
