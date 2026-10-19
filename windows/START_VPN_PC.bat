@echo off
chcp 65001 >nul
title ПУЛЬС // ВПН для Windows
cd /d "%~dp0"

echo ===================================================
echo     🛡️ ПУЛЬС ВПН — Клиент для Windows
echo ===================================================
echo Запуск приложения...

start "" pythonw "%~dp0windows_client\app.py"
exit
