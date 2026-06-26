@echo off
REM Собирает нативный WASAPI loopback-захват в sertas_audio.dll (MSVC, x64).
REM Требует Visual Studio Build Tools (cl.exe) + JDK 21 с заданным JAVA_HOME.
REM Запускать из "x64 Native Tools Command Prompt for VS":
REM   set JAVA_HOME=C:\path\to\jdk-21
REM   scripts\build-windows-audio-dll.bat
REM
REM Готовый sertas_audio.dll потом подхватывается package-windows.sh (положите его
REM в build\native-win\ перед сборкой бандла на Mac, или скопируйте в бандл вручную).
setlocal
if "%JAVA_HOME%"=="" (
  echo [error] JAVA_HOME не задан - укажите путь к JDK 21
  exit /b 1
)
set ROOT=%~dp0..
set OUT=%ROOT%\build\native-win
if not exist "%OUT%" mkdir "%OUT%"

cl /nologo /LD /O2 /EHsc /MD ^
  /I "%JAVA_HOME%\include" /I "%JAVA_HOME%\include\win32" ^
  "%ROOT%\native-capture\windows\SertasAudioWin.cpp" ^
  /link ole32.lib /OUT:"%OUT%\sertas_audio.dll"

if %errorlevel% neq 0 (
  echo [error] сборка не удалась
  exit /b %errorlevel%
)
echo done -^> %OUT%\sertas_audio.dll
