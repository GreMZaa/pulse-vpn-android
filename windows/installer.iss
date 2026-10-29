; Inno Setup 6 Script for Pulse VPN Windows Client
#define MyAppName "ПУЛЬС ВПН"
#define MyAppVersion "0.2.26"
#define MyAppPublisher "GreMZaa"
#define MyAppURL "https://github.com/GreMZaa/pulse-vpn-android"
#define MyAppExeName "PulseVPN.exe"

[Setup]
AppId={{E31D20E0-5B57-472B-BBA1-7ACDF6AD2FD3}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={localappdata}\PulseVPN
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=E:\WEBBOT\VPN\installer_output
OutputBaseFilename=PulseVPN_Setup
SetupIconFile=E:\WEBBOT\VPN\windows_client\app_icon.ico
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\app_icon.ico
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: checkedonce

[Files]
; Собранный автономный клиент PyInstaller (включая Python рантайм, зависимости и Uvicorn/FastAPI)
Source: "E:\WEBBOT\VPN\dist\PulseVPN\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

; Ядро Xray и библиотеки
Source: "E:\WEBBOT\VPN\windows_client\bin\xray.exe"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "E:\WEBBOT\VPN\windows_client\bin\wintun.dll"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "E:\WEBBOT\VPN\windows_client\bin\geoip.dat"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "E:\WEBBOT\VPN\windows_client\bin\geosite.dat"; DestDir: "{app}\bin"; Flags: ignoreversion

; Иконки приложения
Source: "E:\WEBBOT\VPN\windows_client\app_icon.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "E:\WEBBOT\VPN\windows_client\icon.png"; DestDir: "{app}"; Flags: ignoreversion

; Клиентский мобильный веб-интерфейс
Source: "E:\WEBBOT\VPN\backend\client_ui.html"; DestDir: "{app}\backend"; Flags: ignoreversion
Source: "E:\WEBBOT\VPN\backend\cloud_config.json"; DestDir: "{app}\backend"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\app_icon.ico"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\app_icon.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
