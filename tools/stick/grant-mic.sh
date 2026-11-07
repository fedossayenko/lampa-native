#!/system/bin/sh
# Magisk boot script (/data/adb/service.d/, 755): Fire OS takes Lampa Native's microphone at every boot (Amazon's
# RecordAudioPermissionRevoked ~25 s in, 2026-11-01), so voice search said "No microphone". Given back after it.
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 90
pm grant local.lampanative android.permission.RECORD_AUDIO
