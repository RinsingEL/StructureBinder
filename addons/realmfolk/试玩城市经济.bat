@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch-playtest.ps1" -GameDirectory "%~dp0..\.realmfolk-economy-playtest" -World Realmfolk_Economy -Designer -GeomantiaPort 5061 -DesignerPort 5062
pause
