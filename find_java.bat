@echo off
where /R "C:\Program Files" javac.exe 2>nul
where /R "C:\Program Files (x86)" javac.exe 2>nul
where /R "C:\" jdk-11* 2>nul | findstr /i "jdk-11"
