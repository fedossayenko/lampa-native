#!/system/bin/sh
# Magisk boot script (/data/adb/service.d/, 755): keeps Lampa Native's microphone granted on a rooted Fire TV stick.
# Cause: Fire OS (AudioRecordPermissionEnforcer in fosservices.jar) revokes RECORD_AUDIO from every app that is not a
# system app or an Appstore app on Amazon's allowlist, at every boot and at each daily Arcus config sync
# (RecordAudioPermissionRevoked; seen 2026-11-01 and again 2026-11-23, when a boot-only grant had been undone). Nothing
# on the stick can add us to that list, so this watches the system's own "revoked" log line and grants again at once.
# STOPGAP: the root fix would be to make the app a system app (Magisk module); this only undoes the revoke.
# Every boot, revoke and grant is logged in /data/adb/grant-mic.log; voice search says "No microphone" if it fails.
P=local.lampanative
PERM=android.permission.RECORD_AUDIO
LOG=/data/adb/grant-mic.log
log() { echo "$(date '+%F %T') $*" >> "$LOG"; }
granted() { dumpsys package "$P" | grep -q "$PERM: granted=true"; }
fix() { # $1 = why; pm can fail for a moment right after the revoke, so a few tries
  granted && return
  for _ in 1 2 3 4 5; do
    out=$(pm grant "$P" "$PERM" 2>&1) # a pipe: system_server cannot write pm's output into a file under /data/adb
    granted && { log "granted ($1)"; return; }
    sleep 2
  done
  log "FAILED to grant ($1): $out"
}
[ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 100000 ] && tail -n 200 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
log "boot done, watching"
while :; do
  fix start & # at the same time as logcat starts below, so a revoke while it runs is still seen
  # a revoke is logged by AmazonAudioService (main buffer) the moment it happens; -T 1: from now on
  logcat -b main -T 1 -s AmazonAudioService:I | while read -r line; do
    case "$line" in *"revoked for : $P"*) log "revoked by Fire OS"; sleep 1; fix revoke ;; esac
  done
  sleep 30 # logcat ended (buffer cleared, restarted): start again, re-checking first
done
