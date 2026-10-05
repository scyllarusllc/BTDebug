#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

command -v adb >/dev/null || { echo "adb is required (Android SDK platform-tools)." >&2; exit 1; }

device_ids=()
device_endpoints=()
device_count=0
while IFS= read -r endpoint; do
    [[ -n "$endpoint" ]] || continue
    echo "Connecting to $endpoint via mDNS..."
    if ! adb connect "$endpoint" >/dev/null 2>&1; then
        echo "  Unreachable; trying the next advertised endpoint." >&2
        continue
    fi
    [[ "$(adb -s "$endpoint" get-state 2>/dev/null)" == "device" ]] || continue
    hardware_id="$(adb -s "$endpoint" shell getprop ro.serialno 2>/dev/null | tr -d '\r')"
    [[ -n "$hardware_id" ]] || hardware_id="$endpoint"
    duplicate=false
    for ((index=0; index<device_count; index++)); do
        if [[ "${device_ids[$index]}" == "$hardware_id" ]]; then duplicate=true; break; fi
    done
    if [[ "$duplicate" == false ]]; then
        device_ids[$device_count]="$hardware_id"
        device_endpoints[$device_count]="$endpoint"
        device_count=$((device_count + 1))
    fi
done < <(adb mdns services | awk -F '\t' '$2 ~ /^_adb-tls-connect\._tcp\.?$/ && $3 != "" { print $3 }')

if [[ $device_count -eq 0 ]]; then
    echo "No authorized wireless Android device found via adb mDNS. Enable Wireless debugging and pair this computer first." >&2
    exit 1
fi

selected=-1
if [[ -n "${POMATEZ_ANDROID_SERIAL:-}" ]]; then
    for ((index=0; index<device_count; index++)); do
        if [[ "${device_ids[$index]}" == "$POMATEZ_ANDROID_SERIAL" || "${device_endpoints[$index]}" == "$POMATEZ_ANDROID_SERIAL" ]]; then
            selected="$index"
            break
        fi
    done
    if [[ "$selected" -lt 0 ]]; then
        echo "Requested device $POMATEZ_ANDROID_SERIAL was not found. Available hardware serials: ${device_ids[*]}" >&2
        exit 1
    fi
elif [[ $device_count -eq 1 ]]; then
    selected=0
else
    echo "Multiple Android devices found: ${device_ids[*]}" >&2
    echo "Select one with POMATEZ_ANDROID_SERIAL=<hardware-serial> make dev-android" >&2
    exit 1
fi

endpoint="${device_endpoints[$selected]}"
echo "Using ${device_ids[$selected]} at $endpoint"
./gradlew :app:assembleDebug
adb -s "$endpoint" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$endpoint" shell am start -n com.btdebug.btdebugger.dev/com.btdebug.btdebugger.MainActivity
echo "BTDebug launched on ${device_ids[$selected]}."
