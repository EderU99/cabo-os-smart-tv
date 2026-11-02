# Restaurar Sansui Apollo Premium4K TV al estado de fábrica (Launcher original de Google TV + Apps)
param(
    [Parameter(Mandatory = $true)][string]$TvIpPort
)
$adb = "C:\Users\Eder_\Desktop\adb\platform-tools\adb.exe"
& $adb connect $TvIpPort

$pkgs = @(
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tungsten.setupwraith",
    "tv.anoki.acr.anokiacroptin",
    "com.android.adservices.api",
    "com.android.ondevicepersonalization.services",
    "com.android.federatedcompute.services",
    "com.google.android.feedback",
    "com.android.tv.feedbackconsent",
    "com.google.android.partnersetup",
    "com.mediatek.android.leanbacklauncher.partnercustomizer",
    "com.google.android.syncadapters.calendar",
    "com.google.android.play.games",
    "fusion.android.tv.demo",
    "com.mediatek.androidbox",
    "com.google.android.youtube.tvmusic",
    "com.google.android.apps.tv.dreamx",
    "com.google.android.videos",
    "com.univision.prendetv"
)

foreach ($p in $pkgs) {
    & $adb -s $TvIpPort shell "pm enable --user 0 $p"
}

& $adb -s $TvIpPort shell "cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity"
& $adb -s $TvIpPort shell "settings put global private_dns_mode opportunistic"
& $adb -s $TvIpPort shell "input keyevent KEYCODE_HOME"
Write-Host "¡Restauración completada!"
