@echo off
REM ============================================================
REM Research Gateway - build the Docker image and export it as a
REM portable archive you can copy to another server and load with:
REM   docker load -i research-gateway-<tag>.tar
REM   docker run -p 8080:8080 research-gateway:<tag>
REM
REM IMPORTANT: the image is built for a CPU architecture. If the
REM target server has a different CPU than this machine you MUST
REM build for the target arch, otherwise you get on startup:
REM   exec /usr/bin/sh: exec format error
REM
REM Set the target platform with the PLATFORM env var (default: this host).
REM   set PLATFORM=linux/arm64 && build-image.bat   (ARM servers, e.g. NVIDIA DGX, Graviton)
REM   set PLATFORM=linux/amd64 && build-image.bat   (x86 servers)
REM ============================================================
setlocal

REM Run from the project root (the directory this script lives in).
cd /d "%~dp0"

if "%IMAGE_NAME%"=="" set "IMAGE_NAME=research-gateway"
if "%~1"=="" (set "TAG=latest") else (set "TAG=%~1")
set "OUTPUT=%IMAGE_NAME%-%TAG%.tar"

if not "%PLATFORM%"=="" (
  echo ^>^> Building %IMAGE_NAME%:%TAG% for platform %PLATFORM% ...
  docker buildx build --platform "%PLATFORM%" -t "%IMAGE_NAME%:%TAG%" --load .
) else (
  echo ^>^> Building %IMAGE_NAME%:%TAG% for the native host platform ...
  docker build -t "%IMAGE_NAME%:%TAG%" .
)
if errorlevel 1 goto :error

echo ^>^> Exporting image to %OUTPUT% ...
docker save -o "%OUTPUT%" "%IMAGE_NAME%:%TAG%"
if errorlevel 1 goto :error

echo.
echo ^>^> Done. Created: %OUTPUT%
echo.
echo    Copy it to the target server, then run there:
echo      docker load -i %OUTPUT%
echo      docker run -d -p 8080:8080 %IMAGE_NAME%:%TAG%
endlocal
exit /b 0

:error
echo.
echo ^>^> Build failed.
endlocal
exit /b 1
