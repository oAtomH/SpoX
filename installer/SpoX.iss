; SpoX classic setup wizard (Inno Setup 6). Built by: build.bat installer
; Per-user install (no admin), Start menu + optional desktop icon, uninstaller in Settings > Apps.

#define AppVer GetVersionNumbersString("..\SpoX.exe")

[Setup]
AppId={{6C1F5E2A-5B7D-4E39-9A51-5F0C2D8B7A11}
AppName=SpoX
AppVersion={#AppVer}
AppPublisher=SpoX
AppPublisherURL=https://github.com/oAtomH/SpoX
DefaultDirName={localappdata}\Programs\SpoX
DefaultGroupName=SpoX
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..
OutputBaseFilename=SpoX-Setup-{#AppVer}
SetupIconFile=..\installer\icon.ico
UninstallDisplayIcon={app}\SpoX.exe
WizardStyle=modern
WizardImageFile=wizard.bmp,wizard@2x.bmp
WizardSmallImageFile=small.bmp,small@2x.bmp
WizardImageStretch=no
WizardImageBackColor=$0D0C0B
Compression=lzma2/max
SolidCompression=yes
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "Desktop shortcut"; Flags: checkedonce
Name: "startup"; Description: "Start SpoX with Windows (hidden in the tray)"; Flags: unchecked

[Files]
Source: "..\SpoX.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\skin\*"; DestDir: "{app}\skin"; Flags: ignoreversion recursesubdirs createallsubdirs skipifsourcedoesntexist; Excludes: "_backup\*,_guides\*"

[Icons]
Name: "{group}\SpoX"; Filename: "{app}\SpoX.exe"
Name: "{userdesktop}\SpoX"; Filename: "{app}\SpoX.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "SpoX"; ValueData: """{app}\SpoX.exe"" --tray"; Flags: uninsdeletevalue; Tasks: startup

[Run]
Filename: "{app}\SpoX.exe"; Description: "Start SpoX now"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "taskkill"; Parameters: "/f /im soloist.exe"; Flags: runhidden; RunOnceId: "StopSoloist"
