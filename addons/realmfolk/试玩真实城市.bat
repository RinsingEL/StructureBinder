@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch-playtest.ps1" -World Realmfolk_City_Test
if errorlevel 1 pause
