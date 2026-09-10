@echo off
rem Run the trusted project build script without changing the system execution policy.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
