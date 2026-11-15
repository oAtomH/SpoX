@echo off
setlocal
cd /d "%~dp0"
title SpoX build

rem ---- 1. Make sure Rust is installed (GNU toolchain: no Visual Studio needed) ----
where cargo >nul 2>nul
if errorlevel 1 (
  if exist "%USERPROFILE%\.cargo\bin\cargo.exe" (
    set "PATH=%USERPROFILE%\.cargo\bin;%PATH%"
  ) else (
    echo Rust is not installed yet. Downloading the official installer from rustup.rs ...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing https://win.rustup.rs/x86_64 -OutFile $env:TEMP\rustup-init.exe"
    if not exist "%TEMP%\rustup-init.exe" (
      echo Could not download the Rust installer. Check your internet connection.
      pause
      exit /b 1
    )
    "%TEMP%\rustup-init.exe" -y --default-host x86_64-pc-windows-gnu --default-toolchain stable --profile minimal
    set "PATH=%USERPROFILE%\.cargo\bin;%PATH%"
  )
)
rustup toolchain list | findstr /i "stable-x86_64-pc-windows-gnu" >nul
if errorlevel 1 rustup toolchain install stable-x86_64-pc-windows-gnu --profile minimal

rem ---- 2. The GNU toolchain needs MinGW's dlltool.exe (+ as.exe): find it, or install WinLibs once ----
set "DLLDIR="
call :finddll "%LOCALAPPDATA%\Microsoft\WinGet\Packages"
if defined DLLDIR goto checkdll
call :finddll "%ProgramFiles%\WinGet\Packages"
if defined DLLDIR goto checkdll
call :finddll "C:\msys64\mingw64\bin"
if defined DLLDIR goto checkdll
call :finddll "C:\mingw64"
if defined DLLDIR goto checkdll
echo.
echo Installing the MinGW build tools (WinLibs, one time only, about 100 MB)...
winget install -e --id BrechtSanders.WinLibs.POSIX.UCRT --accept-source-agreements --accept-package-agreements
echo winget finished with code %errorlevel%
call :finddll "%LOCALAPPDATA%\Microsoft\WinGet\Packages"
if not defined DLLDIR call :finddll "%ProgramFiles%\WinGet\Packages"
if not defined DLLDIR (
  echo.
  echo Could not find dlltool.exe after installing WinLibs.
  echo Please download WinLibs [UCRT, x86_64, zip] from https://winlibs.com
  echo extract it to C:\mingw64 so that C:\mingw64\bin\dlltool.exe exists, then run build.bat again.
  pause
  exit /b 1
)
:checkdll
set "PATH=%DLLDIR%;%PATH%"
echo.
echo Using MinGW tools from: %DLLDIR%
rem Rust's own dlltool calls the assembler as.exe, which it does not ship: this folder provides it.
"%DLLDIR%dlltool.exe" --version | findstr /i dlltool
if errorlevel 1 (
  echo That dlltool.exe does not run. Send this window's text to Claude.
  pause
  exit /b 1
)

rem ---- 3. Build (first time takes a few minutes, later builds are quick) ----
echo.
echo Building SpoX...
cargo +stable-x86_64-pc-windows-gnu build --release
if errorlevel 1 (
  echo.
  echo BUILD FAILED. Copy the error text above and send it to Claude.
  pause
  exit /b 1
)

rem ---- 4. Put SpoX.exe next to this file + desktop shortcut ----
taskkill /im SpoX.exe /f >nul 2>nul
rem Windows needs a moment to let go of the old SpoX.exe after it closes: retry the copy
set /a TRIES=0
:copyexe
timeout /t 1 /nobreak >nul
copy /y "target\release\spox.exe" "SpoX.exe" >nul 2>nul
if not errorlevel 1 goto copied
set /a TRIES+=1
if %TRIES% lss 8 goto copyexe
echo.
echo Could not replace SpoX.exe (is it still open, or running from somewhere else?).
echo Close SpoX completely (tray icon - Quit) and run build.bat again.
pause
exit /b 1
:copied
powershell -NoProfile -ExecutionPolicy Bypass -Command "$d=[Environment]::GetFolderPath('Desktop'); $s=(New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $d 'SpoX.lnk')); $s.TargetPath=(Join-Path '%~dp0' 'SpoX.exe'); $s.WorkingDirectory='%~dp0'; $s.Description='SpoX mini player'; $s.Save()"

echo.
for %%A in (SpoX.exe) do echo Done: SpoX.exe (%%~zA bytes). A shortcut was added to your desktop.
echo.
if /i "%~1"=="installer" goto installer
start "" "SpoX.exe"
timeout /t 4 >nul
exit /b 0

rem ---- 5. (build.bat installer) the classic setup wizard for friends: SpoX-Setup-x.y.z.exe ----
:installer
set "PF86=%ProgramFiles(x86)%"
call :findiscc
if defined ISCC goto haveiscc
echo Installing Inno Setup [one time only, used to make the setup wizard]...
winget install -e --id JRSoftware.InnoSetup --scope user --accept-source-agreements --accept-package-agreements
call :findiscc
if defined ISCC goto haveiscc
echo Could not find Inno Setup. Get it from https://jrsoftware.org/isdl.php and run: build.bat installer
pause
exit /b 1
:haveiscc
"%ISCC%" /Q "installer\SpoX.iss"
if errorlevel 1 (
  echo Setup wizard build failed. Send this window's text to Claude.
  pause
  exit /b 1
)
for %%A in (SpoX-Setup-*.exe) do echo Done: %%A - send this file to your friends.
pause
exit /b 0

rem ---- helper: finds a MinGW bin folder (dlltool.exe + as.exe) under a folder, sets DLLDIR ----
:finddll
set "DLLDIR="
if not exist "%~1" goto :eof
for /r "%~1" %%F in (dlltool.exe) do (
  if exist "%%~dpFas.exe" if exist "%%F" (
    set "DLLDIR=%%~dpF"
    goto :eof
  )
)
goto :eof

:findiscc
set "ISCC="
if exist "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" set "ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
if exist "%PF86%\Inno Setup 6\ISCC.exe" set "ISCC=%PF86%\Inno Setup 6\ISCC.exe"
if exist "%ProgramFiles%\Inno Setup 6\ISCC.exe" set "ISCC=%ProgramFiles%\Inno Setup 6\ISCC.exe"
goto :eof
