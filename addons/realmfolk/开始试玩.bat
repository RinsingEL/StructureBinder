@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch-playtest.ps1" -World Realmfolk_Start
if errorlevel 1 pause
