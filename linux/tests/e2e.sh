#!/usr/bin/env bash
# End to end: the real server, and two Linux agents signed in as the same person, each on a
# session bus of its own with a stand-in desktop (examples/fake-desktop.rs for notifications and
# a media player; scripts for xdg-open, wl-copy, wl-paste and pw-play that write down what they
# were given).
#
#   linux/tests/e2e.sh
#
# Needs: dbus-daemon (dbus), gdbus (libglib2.0-bin), curl, jq, java, cargo.
# It builds what's missing: the server (SAMO_SERVER_ONLY=1 ./gradlew :server:installDist) and the
# agent (cargo build, CARGO_JOBS jobs, 2 unless said). KEEP=1 leaves the scratch folder behind.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
linux=$(dirname "$here")
repo=$(dirname "$linux")

for tool in dbus-daemon gdbus curl jq java cargo; do
    command -v "$tool" > /dev/null || { echo "e2e: needs $tool" >&2; exit 2; }
done

server_bin=${SAMO_SERVER:-$repo/server/build/install/samo-server/bin/samo-server}
if [ ! -x "$server_bin" ]; then
    (cd "$repo" && SAMO_SERVER_ONLY=1 ./gradlew :server:installDist)
fi
(cd "$linux" && cargo build -j "${CARGO_JOBS:-2}" --bin sam-orchestrator --example fake-desktop)
sam=$linux/target/debug/sam-orchestrator
fake=$linux/target/debug/examples/fake-desktop

work=$(mktemp -d "${TMPDIR:-/tmp}/samo-e2e.XXXXXX")
pids=()
finish() {
    code=$?
    for pid in "${pids[@]}"; do kill "$pid" 2> /dev/null || true; done
    wait 2> /dev/null || true
    if [ "$code" -ne 0 ]; then
        echo
        echo "e2e: FAILED. The scratch folder is kept: $work" >&2
        for log in "$work"/server.log "$work"/A/daemon.log "$work"/B/daemon.log; do
            [ -f "$log" ] && { echo "--- $log (end)"; tail -n 15 "$log"; } >&2
        done
    elif [ -z "${KEEP:-}" ]; then
        rm -rf "$work"
    else
        echo "e2e: kept $work"
    fi
    exit "$code"
}
trap finish EXIT

step() { printf '\n== %s\n' "$*"; }
ok() { printf '   ok: %s\n' "$*"; }
fail() { printf '   FAILED: %s\n' "$*" >&2; exit 1; }

# wait_for <seconds> <what> <command…>: until the command succeeds.
wait_for() {
    local seconds=$1 what=$2
    shift 2
    local until=$((SECONDS + seconds))
    until "$@" > /dev/null 2>&1; do
        [ "$SECONDS" -lt "$until" ] || fail "$what (waited ${seconds}s)"
        sleep 0.2
    done
    ok "$what"
}

free_port() {
    local port
    while :; do
        port=$((20000 + RANDOM % 20000))
        (exec 3<> "/dev/tcp/127.0.0.1/$port") 2> /dev/null || { echo "$port"; return; }
    done
}

# ---- The server ----

port=$(free_port)
server=http://127.0.0.1:$port
admin_password=some-long-test-pw
start_server() {
    SAMO_DATA_DIR=$work/data SAMO_ADMIN_PASSWORD=$admin_password SAMO_PORT=$port SAMO_HOST=127.0.0.1 "$server_bin" >> "$work/server.log" 2>&1 &
    server_pid=$!
    pids+=("$server_pid")
    wait_for 90 "the server is up on $server" curl -fsS "$server/api/auth/config"
}
step "Starting the server"
start_server

# api <cookie jar> <method> <path> [json]: as a browser session.
api() {
    local jar=$1 method=$2 path=$3 body=${4:-}
    if [ -n "$body" ]; then
        curl -fsS -b "$jar" -c "$jar" -X "$method" -H 'X-SAMO: 1' -H 'Content-Type: application/json' --data "$body" "$server$path"
    else
        curl -fsS -b "$jar" -c "$jar" -X "$method" -H 'X-SAMO: 1' "$server$path"
    fi
}

step "An account, and an app listed"
admin=$work/admin.cookies
me=$work/maddie.cookies
api "$admin" POST /api/auth/login "{\"username\":\"admin\",\"password\":\"$admin_password\"}" > /dev/null
api "$admin" POST /api/admin/users '{"username":"maddie","displayName":"Maddie","password":"first-temporary-pw"}' > /dev/null
# An admin-set password is temporary: the person chooses their own before anything else works.
api "$me" POST /api/auth/login '{"username":"maddie","password":"first-temporary-pw"}' > /dev/null
api "$me" POST /api/me/password '{"current":"first-temporary-pw","new":"maddies-own-long-pw"}' > /dev/null
settings=$(api "$admin" GET /api/admin/settings | jq -c '{settings: (.settings | .apps = [{id: "music", name: "SuperAwesomeMagicalMusic", url: "https://music.example.com"}])}')
api "$admin" PUT /api/admin/settings "$settings" > /dev/null
ok "maddie has her own password; the admin listed music"

token_for() {
    curl -fsS -H 'Content-Type: application/json' --data "{\"username\":\"maddie\",\"password\":\"maddies-own-long-pw\",\"app\":true,\"deviceName\":\"$1\"}" "$server/api/auth/login" | jq -r .token
}

# ---- Two computers ----

# as <A|B> <command…>: in that computer's session.
as() {
    local n=$1
    shift
    # AS_EXEC=1 as … &: the command takes this subshell's place, so $! is the command's own pid.
    ${AS_EXEC:+exec} env -u WAYLAND_DISPLAY -u DISPLAY -u INVOCATION_ID \
        HOME="$work/$n/home" XDG_CONFIG_HOME="$work/$n/config" XDG_STATE_HOME="$work/$n/state" XDG_RUNTIME_DIR="$work/$n/run" \
        PATH="$work/$n/bin:$PATH" NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost \
        SAM_ORCHESTRATOR_SYSFS="$work/$n/sys" SAM_ORCHESTRATOR_RING_SOUND="$work/ring.oga" \
        DBUS_SESSION_BUS_ADDRESS="$(cat "$work/$n/bus.address" 2> /dev/null || true)" "$@"
}
: > "$work/ring.oga"

make_computer() {
    local n=$1 dir=$work/$1
    mkdir -p "$dir"/{home,config,state,run,bin,services,sys/class/power_supply}
    chmod 700 "$dir/run"
    # The desktop's tools, writing down what they're given: one argument a line, after how many.
    cat > "$dir/bin/xdg-open" << EOF
#!/bin/sh
{ echo "args=\$#"; for a in "\$@"; do printf '%s\n' "\$a"; done; } >> "$dir/xdg-open.log"
EOF
    cat > "$dir/bin/wl-copy" << EOF
#!/bin/sh
cat > "$dir/clipboard"
echo "args=\$#" >> "$dir/wl-copy.log"
EOF
    cat > "$dir/bin/wl-paste" << EOF
#!/bin/sh
echo "\$*" >> "$dir/wl-paste.log"
cat "$dir/clipboard-source"
EOF
    cat > "$dir/bin/pw-play" << EOF
#!/bin/sh
echo "\$*" >> "$dir/pw-play.log"
exec sleep 2
EOF
    cat > "$dir/bin/xdg-mime" << EOF
#!/bin/sh
case "\$3" in x-scheme-handler/sammusic) echo sammusic.desktop ;; esac
EOF
    chmod +x "$dir"/bin/*
    # The session bus, able to start the agent the way the packaged service file says (less systemd).
    sed -e "s|^Exec=.*|Exec=$sam daemon|" -e '/^SystemdService=/d' "$linux/data/app.superawesomemagical.Orchestrator.service" > "$dir/services/app.superawesomemagical.Orchestrator.service"
    cat > "$dir/bus.conf" << EOF
<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN" "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
  <type>session</type>
  <listen>unix:dir=$dir/run</listen>
  <servicedir>$dir/services</servicedir>
  <policy context="default">
    <allow send_destination="*" eavesdrop="true"/>
    <allow eavesdrop="true"/>
    <allow own="*"/>
  </policy>
</busconfig>
EOF
    AS_EXEC=1 as "$n" dbus-daemon --config-file="$dir/bus.conf" --nofork --print-address=3 3> "$dir/bus.address" 2>> "$dir/bus.log" &
    pids+=("$!")
    wait_for 10 "$n's session bus is up" test -s "$dir/bus.address"
    AS_EXEC=1 as "$n" "$fake" "$dir/desktop.log" 2>> "$dir/fake.log" &
    pids+=("$!")
    wait_for 10 "$n's stand-in desktop is up" grep -q ready "$dir/desktop.log"
}

status() { as "$1" "$sam" status --json; }
is() { [ "$(status "$1" | jq -r "$2")" = "$3" ]; }
# seen <A|B> <jq filter over the array of everything the stand-in desktop was asked>
seen() { jq -s -e "$2" "$work/$1/desktop.log"; }
notify() { # notify <A|B> <app> <replaces> <summary> <body> <hints>: the id it was given
    as "$1" gdbus call --session --dest org.freedesktop.Notifications --object-path /org/freedesktop/Notifications \
        --method org.freedesktop.Notifications.Notify "$2" "$3" "" "$4" "$5" "[]" "$6" 5000 | sed -E 's/.*uint32 ([0-9]+).*/\1/'
}
desktop() { # desktop <A|B> <method> <args…>
    local n=$1 method=$2
    shift 2
    as "$n" gdbus call --session --dest org.freedesktop.Notifications --object-path /org/freedesktop/Notifications --method "$method" "$@" > /dev/null
}
orchestrator() { # orchestrator <A|B> <method> <args…>
    local n=$1 method=$2
    shift 2
    as "$n" gdbus call --session --dest app.superawesomemagical.Orchestrator --object-path /app/superawesomemagical/Orchestrator \
        --method "app.superawesomemagical.Orchestrator1.$method" "$@"
}

owned() { as "$1" gdbus call --session --dest org.freedesktop.DBus --object-path /org/freedesktop/DBus --method org.freedesktop.DBus.NameHasOwner app.superawesomemagical.Orchestrator | grep -q true; }
not_owned() { ! owned "$1"; }
# B's agent is started by hand (A's by its session bus), and stopped and started again later.
start_b() {
    AS_EXEC=1 as B "$sam" daemon >> "$work/B/daemon.log" 2>&1 &
    b_pid=$!
    pids+=("$b_pid")
    wait_for 10 "B's agent is on its session bus" owned B
}
stop_b() {
    kill "$b_pid"
    wait "$b_pid" 2> /dev/null || true
    wait_for 10 "B's agent has stopped" not_owned B
}

step "Two computers, each with its own session"
make_computer A
make_computer B
printf 'Battery\n' > "$work/A/sys/class/power_supply/type.unused"
mkdir -p "$work/A/sys/class/power_supply/BAT0"
printf 'Battery\n' > "$work/A/sys/class/power_supply/BAT0/type"
printf '77\n' > "$work/A/sys/class/power_supply/BAT0/capacity"
printf 'Charging\n' > "$work/A/sys/class/power_supply/BAT0/status"
rm "$work/A/sys/class/power_supply/type.unused"

step "A: the session bus starts the agent when something asks (D-Bus activation)"
is A .signedIn false || fail "A should start signed out"
ok "status answered, from an agent nobody started by hand: signed out"
as A "$sam" status | grep -q "Nobody's signed in" || fail "the words for signed out"
if as A "$sam" devices > /dev/null 2> "$work/err"; then fail "devices should fail when signed out"; fi
grep -q "Nobody's signed in" "$work/err" || fail "devices, signed out, should say so: $(cat "$work/err")"
ok "devices fails, in words, when nobody's signed in"

step "A: signing in with a token on standard input, the agent already running"
token_for "computer A" | as A "$sam" sign-in --token-stdin "$server" > "$work/out" 2> "$work/err" || fail "sign-in: $(cat "$work/err")"
grep -q "isn't https" "$work/err" || fail "an http:// server should come with a warning"
ok "warned that the server isn't https"
grep -q "You're signed in as Maddie (maddie)" "$work/out" || fail "sign-in said: $(cat "$work/out")"
wait_for 15 "A is signed in and connected (the running agent picked it up)" is A '.signedIn and .connected' true
[ "$(stat -c %a "$work/A/state/superawesomemagical/orchestrator")" = 700 ] || fail "the sign-in's folder should be 0700"
[ "$(stat -c %a "$work/A/state/superawesomemagical/orchestrator/credentials.json")" = 600 ] || fail "the sign-in's file should be 0600"
jq -e '.token and .privateKey and .deviceId' "$work/A/state/superawesomemagical/orchestrator/credentials.json" > /dev/null || fail "the sign-in should be saved"
jq -e --arg s "$server" '.server == $s' "$work/A/config/superawesomemagical/orchestrator.json" > /dev/null || fail "the server's address should be in the settings"
ok "the sign-in is saved 0600 in a 0700 folder; the server's address is in the settings file"

step "B: signing in first, then starting the agent"
token_for "computer B" | as B "$sam" sign-in --token-stdin "$server" > /dev/null 2>&1 || fail "B's sign-in"
start_b
wait_for 15 "B is signed in and connected" is B '.signedIn and .connected' true
if as B "$sam" daemon > /dev/null 2> "$work/err"; then fail "a second agent in one session should refuse to start"; fi
grep -q "already running" "$work/err" || fail "a second agent should say one is running: $(cat "$work/err")"
ok "a second agent in the same session refuses to start"

a_id=$(status A | jq -r .device.id)
b_id=$(status B | jq -r .device.id)
[ -n "$a_id" ] && [ -n "$b_id" ] && [ "$a_id" != "$b_id" ] || fail "two devices, two ids"
# Both computers have this machine's host name: tell them apart the way a person would, by renaming.
api "$me" PATCH "/api/devices/$a_id" '{"name":"Den laptop"}' > /dev/null
api "$me" PATCH "/api/devices/$b_id" '{"name":"Studio PC"}' > /dev/null
wait_for 10 "a rename on the server reaches the computer itself" is A .device.name "Den laptop"
wait_for 10 "and the other one" is B .device.name "Studio PC"

step "Each lists the other"
both() { as "$1" "$sam" devices --json | jq -e --arg a "$a_id" --arg b "$b_id" --arg me "$2" \
    'length == 2 and all(.[]; .online and .kind == "LINUX" and (.capabilities | index("notifications") and index("clipboard") and index("links") and index("ring") and index("activity") and index("media")))
     and ([.[] | select(.current)] | length == 1 and .[0].id == $me) and ([.[].id] | index($a) != null and index($b) != null)'; }
wait_for 10 "A sees both, connected, itself marked" both A "$a_id"
wait_for 10 "B sees both, connected, itself marked" both B "$b_id"
wait_for 10 "A's battery (77%, charging) shows on B" sh -c "$(declare -f as); work=$work; sam=$sam; as B $sam devices --json | jq -e '.[] | select(.name == \"Den laptop\") | .battery == {level: 77, charging: true}'"
as B "$sam" devices | grep -q "Den laptop: Linux.*connected, battery 77% and charging" || fail "devices in words: $(as B "$sam" devices)"
as B "$sam" devices | grep -q "Studio PC (this computer)" || fail "devices should mark this computer"
ok "devices, in words: $(as B "$sam" devices | tr '\n' ';')"

step "A link from A opens on B"
as A "$sam" send-link "studio" 'https://example.com/a?b=c&d=--help' > "$work/out" || fail "send-link"
grep -q "The link is on Studio PC now" "$work/out" || fail "send-link said: $(cat "$work/out")"
wait_for 10 "B's xdg-open was given the link" grep -qxF 'https://example.com/a?b=c&d=--help' "$work/B/xdg-open.log"
[ "$(grep -c '^args=1$' "$work/B/xdg-open.log")" = 1 ] && [ "$(wc -l < "$work/B/xdg-open.log")" = 2 ] || fail "xdg-open should be run once, with the link as its only argument: $(cat "$work/B/xdg-open.log")"
ok "once, with the link as its one argument"
wait_for 10 "B says \"Link from Den laptop\"" seen B 'any(.[]; .event == "notify" and .summary == "Link from Den laptop" and (.body | contains("https://example.com/a?b=c&amp;d=--help")))'
for hostile in 'file:///etc/passwd' 'javascript:alert(1)' '--help' ' https://example.com' 'smb://x/y'; do
    if as A "$sam" send-link studio -- "$hostile" > /dev/null 2>&1; then fail "send-link should refuse $hostile"; fi
    if orchestrator A Send "$b_id" "{\"type\":\"link\",\"url\":\"$hostile\"}" > /dev/null 2>&1; then fail "Send should refuse the link $hostile"; fi
done
ok "links that aren't web or family links are refused before they're sent"
as A "$sam" send-link "$b_id" 'sammusic://album/42' > /dev/null || fail "a family link, by device id"
wait_for 10 "a family link (sammusic://) opens where an app handles it" grep -qxF 'sammusic://album/42' "$work/B/xdg-open.log"
as A "$sam" send-link studio 'samvideo://watch/7' > /dev/null
wait_for 10 "and is not opened where none does, with a notification saying so" seen B 'any(.[]; .event == "notify" and (.body | contains("no app here opens samvideo links")))'
grep -q samvideo "$work/B/xdg-open.log" && fail "samvideo has no handler: it shouldn't have been opened"

step "The clipboard goes from A to B"
printf 'héllo from the aurora ✨\nline two' > "$work/A/clipboard-source"
as A "$sam" send-clipboard > "$work/out" || fail "send-clipboard"
grep -q -- "--no-newline" "$work/A/wl-paste.log" || fail "wl-paste should be asked for no newline"
wait_for 10 "B's wl-copy was given it on standard input" cmp -s "$work/A/clipboard-source" "$work/B/clipboard"
[ "$(cat "$work/B/wl-copy.log")" = "args=0" ] || fail "wl-copy should get no arguments: $(cat "$work/B/wl-copy.log")"
as B "$sam" settings clipboard false > /dev/null
printf 'not for B' > "$work/A/clipboard-source"
as A "$sam" send-clipboard studio > /dev/null
sleep 2
grep -q 'not for B' "$work/B/clipboard" && fail "B turned clipboards off: it shouldn't have taken one"
as B "$sam" settings clipboard true > /dev/null
ok "with clipboard turned off on B, one sent there is left alone"
as B "$sam" settings --json | jq -e '.clipboard == true and .notificationsIn == true and .notificationsOut == true and .links == true and .neverMirror == []' > /dev/null || fail "settings --json"
if as B "$sam" settings clipboard maybe > /dev/null 2>&1; then fail "a setting that isn't true or false should be refused"; fi

step "What's happening on A shows on B"
orchestrator A PublishActivity music "Aurora" "Nightfall" "sammusic://track/9" > /dev/null
wait_for 10 "B's activities has it, named for A" sh -c "$(declare -f as); work=$work; as B $sam activities --json | jq -e --arg a $a_id '. == [.[0]] and .[0].device == \$a and .[0].deviceName == \"Den laptop\" and .[0].app == \"music\" and .[0].title == \"Aurora\" and .[0].detail == \"Nightfall\" and .[0].url == \"sammusic://track/9\" and (.[0].at > 0)'"
as B "$sam" activities | grep -q "Den laptop: Aurora on music (Nightfall)" || fail "activities in words: $(as B "$sam" activities)"
as A "$sam" activities --json | jq -e '. == []' > /dev/null || fail "A's own activity isn't one of its other devices'"
orchestrator A PublishActivity music "" "" "" > /dev/null
wait_for 10 "an empty title means stopped: it's gone from B" sh -c "$(declare -f as); work=$work; as B $sam activities --json | jq -e '. == []'"

step "A notification on A shows on B, marked with A's name, and doesn't come back"
a1=$(notify A Signal 0 "Sam" "On my <b>way</b> &amp; nearly there" "{'desktop-entry': <'org.signal.Signal'>}")
[ "$a1" -gt 0 ] || fail "A's notification should have an id"
wait_for 10 "B shows it as \"Signal · Den laptop\"" seen B 'any(.[]; .event == "notify" and .app == "Signal · Den laptop" and .summary == "Sam")'
seen B '[.[] | select(.event == "notify" and .summary == "Sam")] | length == 1 and .[0].hints["x-sam-mirrored"] == true and .[0].hints["desktop-entry"] == "app.superawesomemagical.Orchestrator"
        and .[0].body == "On my way &amp; nearly there"' > /dev/null || fail "B's copy: $(seen B '[.[] | select(.summary == "Sam")]')"
ok "marked x-sam-mirrored; the markup stripped, and the plain words escaped for B's desktop"
b1=$(seen B '[.[] | select(.event == "notify" and .summary == "Sam")][0].id')
sleep 3
seen A '[.[] | select(.event == "notify" and .summary == "Sam")] | length == 1' > /dev/null || fail "it came back to A: $(seen A '[.[] | select(.summary == "Sam")]')"
seen B '[.[] | select(.event == "notify" and .summary == "Sam")] | length == 1' > /dev/null || fail "it showed twice on B"
seen A '[.[] | select(.event == "notify" and (.summary | startswith("Link from")))] | length == 0' > /dev/null || fail "B's own \"Link from\" notification was mirrored to A"
ok "three seconds on: still once on A, once on B; B's own notifications never reached A"

step "Dismissing it on B closes it on A"
desktop B test.Fake.Dismiss "$b1"
wait_for 10 "A's real notification ($a1) is closed" seen A "any(.[]; .event == \"close\" and .id == $a1)"

step "Closing one on A closes its copy on B; one that only times out stays"
a2=$(notify A Calendar 0 "Dentist at 3" "" "{}")
wait_for 10 "B shows the second one" seen B 'any(.[]; .event == "notify" and .summary == "Dentist at 3" and .app == "Calendar · Den laptop")'
b2=$(seen B '[.[] | select(.event == "notify" and .summary == "Dentist at 3")][0].id')
desktop A org.freedesktop.Notifications.CloseNotification "$a2"
wait_for 10 "B's copy ($b2) is closed" seen B "any(.[]; .event == \"close\" and .id == $b2)"
a3=$(notify A Calendar 0 "Lunch at 12" "" "{}")
wait_for 10 "B shows the third one" seen B 'any(.[]; .event == "notify" and .summary == "Lunch at 12")'
b3=$(seen B '[.[] | select(.event == "notify" and .summary == "Lunch at 12")][0].id')
desktop A test.Fake.Expire "$a3"
sleep 2
seen B "any(.[]; .event == \"close\" and .id == $b3)" > /dev/null && fail "a notification that timed out on A shouldn't be closed on B"
ok "the one that timed out on A is still on B"

step "What's never mirrored"
notify A Spotify 0 "Now playing" "A song" "{'transient': <true>}" > /dev/null
notify A Whatever 0 "Loop" "Already mirrored" "{'x-sam-mirrored': <true>}" > /dev/null
notify A Empty 0 "" "" "{}" > /dev/null
as A "$sam" settings neverMirror "Secrets, Other" > /dev/null
notify A secrets 0 "Private" "Not for other devices" "{}" > /dev/null
as B "$sam" settings notificationsIn false > /dev/null
notify A Signal 0 "While B wasn't taking any" "x" "{}" > /dev/null
sleep 3
as B "$sam" settings notificationsIn true > /dev/null
as A "$sam" settings notificationsOut false > /dev/null
notify A Signal 0 "While A wasn't sending any" "x" "{}" > /dev/null
sleep 2
as A "$sam" settings notificationsOut true > /dev/null
notify A Signal 0 "The marker" "after all of those" "{}" > /dev/null
wait_for 10 "a later, ordinary one arrives" seen B 'any(.[]; .event == "notify" and .summary == "The marker")'
seen B '[.[] | select(.event == "notify") | .summary] | (index("Now playing") or index("Loop") or index("Private") or index("While B wasn'"'"'t taking any") or index("While A wasn'"'"'t sending any") or index("")) | not' > /dev/null \
    || fail "something that should never be mirrored was: $(seen B '[.[] | select(.event == "notify") | .summary]')"
ok "transient, already-mirrored, empty and never-mirror apps' notifications stayed on A; so did everything while either side had it off"

step "A notification updating ten times a second doesn't flood"
p=$(notify A Files 0 "Copying" "0 of 10" "{}")
for n in 1 2 3 4 5 6 7 8 9 10; do notify A Files "$p" "Copying" "$n of 10" "{}" > /dev/null; done
wait_for 10 "the last update arrives" seen B 'any(.[]; .event == "notify" and .summary == "Copying" and .body == "10 of 10")'
count=$(seen B '[.[] | select(.event == "notify" and .summary == "Copying")] | length')
[ "$count" -le 2 ] || fail "eleven updates in a moment became $count on B (at most 2 expected)"
seen B '[.[] | select(.event == "notify" and .summary == "Copying")] | (map(.id) | unique | length) == 1' > /dev/null || fail "the updates should replace one notification on B"
ok "eleven updates became $count on B, replacing the same notification"

step "Ringing B from A"
as A "$sam" ring studio > "$work/out" || fail "ring"
grep -q "Studio PC is ringing" "$work/out" || fail "ring said: $(cat "$work/out")"
wait_for 10 "B plays the sound" grep -qF "$work/ring.oga" "$work/B/pw-play.log"
wait_for 10 "and shows a critical \"Found it\" notification" seen B 'any(.[]; .event == "notify" and .summary == "Den laptop is looking for this computer" and .hints.urgency == 2 and (.actions | index("Found it")))'
ring_id=$(seen B '[.[] | select(.event == "notify" and (.summary | contains("looking for")))][0].id')
sleep 3
[ "$(wc -l < "$work/B/pw-play.log")" -ge 2 ] || fail "the sound should play again and again"
ok "the sound loops"
desktop B test.Fake.Dismiss "$ring_id"
sleep 1
played=$(wc -l < "$work/B/pw-play.log")
sleep 4
[ "$(wc -l < "$work/B/pw-play.log")" = "$played" ] || fail "dismissing the notification should stop the ring"
ok "dismissing the notification stops it"
as A "$sam" ring studio > /dev/null
wait_for 10 "ringing again" seen B "[.[] | select(.event == \"notify\" and (.summary | contains(\"looking for\")))] | length == 2"
ring_id=$(seen B '[.[] | select(.event == "notify" and (.summary | contains("looking for")))][1].id')
as A "$sam" ring --stop studio > /dev/null
wait_for 10 "ring --stop from A closes B's notification" seen B "any(.[]; .event == \"close\" and .id == $ring_id)"
played=$(wc -l < "$work/B/pw-play.log")
sleep 4
[ "$(wc -l < "$work/B/pw-play.log")" = "$played" ] || fail "ring --stop should stop the sound"
ok "and the sound"
seen A '[.[] | select(.event == "notify" and (.summary | contains("looking for")))] | length == 0' > /dev/null || fail "the ring's notification was mirrored to A"

step "Media keys from A reach B's player"
orchestrator A Send "$b_id" '{"type":"media","action":"pause"}' > /dev/null
wait_for 10 "pause" seen B 'any(.[]; .event == "media" and .action == "Pause")'
orchestrator A Send "" '{"type":"media","action":"next"}' > /dev/null
wait_for 10 "next, sent to every other device" seen B 'any(.[]; .event == "media" and .action == "Next")'
orchestrator A Send "" '{"type":"hologram","beam":1}' > /dev/null || fail "a kind this version doesn't know can still be sent"
sleep 1
is B .connected true || fail "a kind B doesn't know should be skipped, not break it"
ok "a kind of message B doesn't know is skipped"

step "A sign-in ticket for an app"
ticket=$(as A "$sam" ticket music) || fail "ticket music"
decode() { local part; part=$(printf '%s' "$1" | tr '_-' '/+'); while [ $((${#part} % 4)) -ne 0 ]; do part="$part="; done; printf '%s' "$part" | base64 -d; }
[ "$(printf '%s' "$ticket" | tr -cd . | wc -c)" = 2 ] || fail "a ticket is a JWT: $ticket"
decode "$(printf '%s' "$ticket" | cut -d. -f1)" | jq -e '.alg == "ES256" and .typ == "samo-ticket+jwt"' > /dev/null || fail "the ticket's header"
decode "$(printf '%s' "$ticket" | cut -d. -f2)" | jq -e --arg d "$a_id" --arg iss "$server" \
    '.aud == "https://music.example.com" and .app == "music" and .preferred_username == "maddie" and .device_id == $d and .device_name == "Den laptop" and .iss == $iss' > /dev/null \
    || fail "the ticket's claims: $(decode "$(printf '%s' "$ticket" | cut -d. -f2)")"
ok "ticket music is a JWT for https://music.example.com, as maddie on Den laptop"
as A "$sam" ticket music --json | jq -e '.url == "https://music.example.com" and (.ticket | length > 100)' > /dev/null || fail "ticket --json"
orchestrator A SignInTo music | grep -q "^('https://music.example.com', '" || fail "SignInTo over D-Bus"
if orchestrator A SignInTo video > /dev/null 2> "$work/err"; then fail "an app the admin hasn't listed should be refused"; fi
grep -q "app.superawesomemagical.Orchestrator.Error.UnknownApp" "$work/err" || fail "the error for an unlisted app: $(cat "$work/err")"
ok "an app the admin hasn't listed: Error.UnknownApp"
as A "$sam" apps | grep -q "music  SuperAwesomeMagicalMusic  https://music.example.com" || fail "apps: $(as A "$sam" apps)"
as A "$sam" apps --json | jq -e '. == [{id: "music", name: "SuperAwesomeMagicalMusic", url: "https://music.example.com"}]' > /dev/null || fail "apps --json"
ok "apps lists it"

step "What's sent while B's agent isn't running waits for it"
stop_b
wait_for 10 "A sees B go away" sh -c "$(declare -f as); work=$work; as A $sam devices --json | jq -e '.[] | select(.name == \"Studio PC\") | .online == false'"
as A "$sam" send-link studio 'https://example.com/while-away' > "$work/out"
grep -q "Studio PC is away: it'll get the link when it's back" "$work/out" || fail "send-link to a device that's away said: $(cat "$work/out")"
start_b
wait_for 15 "B's agent, started again, opens it" grep -qxF 'https://example.com/while-away' "$work/B/xdg-open.log"
sleep 1
stop_b
start_b
wait_for 15 "B is connected again" is B .connected true
sleep 2
[ "$(grep -cxF 'https://example.com/while-away' "$work/B/xdg-open.log")" = 1 ] || fail "an acknowledged message shouldn't be delivered again"
ok "started once more: the link isn't opened a second time (it was acknowledged)"

step "The server goes away and comes back"
kill "$server_pid"
wait "$server_pid" 2> /dev/null || true
wait_for 15 "A notices it's not connected" is A '.signedIn and (.connected | not)' true
as A "$sam" status | grep -q "Not connected right now" || fail "status in words while the server is away"
sleep 3
start_server
wait_for 30 "A is connected again by itself" is A .connected true
wait_for 30 "and B" is B .connected true
as A "$sam" send-link studio 'https://example.com/after-restart' > /dev/null
wait_for 10 "and things flow again" grep -qxF 'https://example.com/after-restart' "$work/B/xdg-open.log"

step "Signing out on B"
as B "$sam" sign-out | grep -q "Signed out" || fail "sign-out"
is B .signedIn false || fail "B should be signed out"
jq -e '(.token | not) and .privateKey' "$work/B/state/superawesomemagical/orchestrator/credentials.json" > /dev/null || fail "signing out forgets the token and keeps the device's key"
wait_for 10 "B is gone from A's devices" sh -c "$(declare -f as); work=$work; as A $sam devices --json | jq -e 'length == 1 and .[0].name == \"Den laptop\"'"
if as B "$sam" ticket music > /dev/null 2> "$work/err"; then fail "no tickets when signed out"; fi
(orchestrator B SignInTo music 2>&1 || true) | grep -q "Error.NotSignedIn" || fail "SignInTo when signed out should be Error.NotSignedIn"
ok "signed out: no token kept (the key is), no tickets (Error.NotSignedIn)"

step "Removing A from the web page signs it out"
api "$me" DELETE "/api/devices/$a_id" > /dev/null
wait_for 20 "A notices, and says it's signed out" is A .signedIn false
jq -e '.token | not' "$work/A/state/superawesomemagical/orchestrator/credentials.json" > /dev/null || fail "A should have forgotten its token"
ok "its token is forgotten"

echo
echo "e2e: everything passed"
