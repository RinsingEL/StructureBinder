@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\launch-playtest.ps1 -World Realmfolk_City_Test -Designer
pause
