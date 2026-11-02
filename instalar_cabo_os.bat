@echo off
chcp 65001 >nul
set "ADB=C:\Users\Eder_\cleanyourtv\android-tools\sdk\platform-tools\adb.exe"
set "APK=C:\Users\Eder_\cleanyourtv\launcher\app\build\outputs\apk\release\app-release.apk"

echo ===================================================
echo   Instalador de CaboOS - Smart TV Edition
echo ===================================================
set /p PORT="Escribe el puerto de Depuracion Inalambrica de tu TV (ej. 43907): "
"%ADB%" start-server
"%ADB%" connect 192.168.100.22:%PORT%

echo.
echo Instalando CaboOS en la TV...
"%ADB%" -s 192.168.100.22:%PORT% install -r "%APK%"

echo Iniciando CaboOS...
"%ADB%" -s 192.168.100.22:%PORT% shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/.MainActivity
timeout /t 1 /nobreak >nul

echo Activando Medallon 3D Cristalizado y deshabilitando volumen por defecto...
"%ADB%" -s 192.168.100.22:%PORT% shell settings put secure enabled_accessibility_services com.gathin.tvhome/com.gathin.tvhome.HomeKeyService
"%ADB%" -s 192.168.100.22:%PORT% shell settings put secure accessibility_enabled 1

echo.
echo ===================================================
echo   ¡CaboOS actualizado con exito!
echo ===================================================
pause
