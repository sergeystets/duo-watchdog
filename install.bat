@echo off
rem Runs install.ps1: installs or updates Duo Watchdog in IntelliJ IDEA and VS Code, asking where.
rem Usage: install.bat ["C:\path\to\IntelliJ IDEA"]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
set "CODE=%ERRORLEVEL%"
rem Keep the window open when started by double-click, so the result can be read.
echo %CMDCMDLINE% | find /i "%~nx0" >nul && pause
exit /b %CODE%
