@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\launch-playtest.ps1 -World Realmfolk_Housing -Designer
pause
