@echo off
rem Runs talks-art.ps1 beside this file. Nothing is saved until you answer Y.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0talks-art.ps1" %*
pause
