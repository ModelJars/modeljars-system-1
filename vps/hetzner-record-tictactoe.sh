#!/usr/bin/env bash
# Records the tic-tac-toe demo headless on a fresh Hetzner CCX33 (JavaFX under Xvfb, software
# rendering) so the clip carries server timings, copies demo.mp4 and demo.gif back, and deletes the
# server when done, whatever happens.
#
# Needs the hcloud CLI and three things in the environment:
#   HCLOUD_TOKEN     an API token for a Hetzner Cloud project
#   HCLOUD_SSH_KEY   the name of an SSH key already uploaded to that project
#   SSH_IDENTITY     the matching private key (default ~/.ssh/id_ed25519)
# Optional: HCLOUD_TYPE (default ccx33), HCLOUD_LOCATION (default nbg1), OUT (default ../out-record),
# APP_ARGS (default "--auto random --games 2 --seed 42").
#
# Nothing secret goes to the box: TYPESAFE_API_KEY is not forwarded, so the hosted comparison is
# off in this clip; record with --jev on your own machine for that.
set -uo pipefail
: "${HCLOUD_TOKEN:?set HCLOUD_TOKEN}"
: "${HCLOUD_SSH_KEY:?set HCLOUD_SSH_KEY to the name of an SSH key in the Hetzner project}"
IDENTITY="${SSH_IDENTITY:-$HOME/.ssh/id_ed25519}"
TYPE="${HCLOUD_TYPE:-ccx33}"
LOCATION="${HCLOUD_LOCATION:-nbg1}"
APP_ARGS="${APP_ARGS:---auto random --games 2 --seed 42}"
CODE="$(cd "$(dirname "$0")/../tictactoe" && pwd)"
OUT="${OUT:-$CODE/../out-record}"
NAME="harriet-record-$(date +%Y%m%d%H%M%S)"
mkdir -p "$OUT"

SSH_OPTS=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=8 -o LogLevel=ERROR -i "$IDENTITY")

cleanup() {
  echo "deleting $NAME"
  hcloud server delete "$NAME"
}
trap cleanup EXIT

hcloud server create --name "$NAME" --type "$TYPE" --image ubuntu-24.04 --location "$LOCATION" \
  --ssh-key "$HCLOUD_SSH_KEY" -o json > "$OUT/create.json" || exit 1
IP="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["server"]["public_net"]["ipv4"]["ip"])' "$OUT/create.json")"
echo "server $NAME at $IP"

for _ in $(seq 1 60); do
  ssh "${SSH_OPTS[@]}" "root@$IP" true 2>/dev/null && break
  sleep 5
done

ssh "${SSH_OPTS[@]}" "root@$IP" 'bash -s' <<'REMOTE'
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get -qq update >/dev/null
apt-get -qq install -y unzip zip curl xvfb ffmpeg fontconfig fonts-dejavu-core libgtk-3-0 libgl1 libgl1-mesa-dri libxtst6 libxxf86vm1 libasound2t64 >/dev/null
curl -sL https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse -o /tmp/jdk.tgz
mkdir -p /root/jdk && tar xzf /tmp/jdk.tgz -C /root/jdk --strip-components=1
curl -Ls https://sh.jbang.dev | bash -s - app setup >/dev/null 2>&1 || true
mkdir -p /root/ttt
REMOTE

scp "${SSH_OPTS[@]}" "$CODE/TicTacToe.java" "$CODE/HarrietTicTacToeFx.java" "root@$IP:/root/ttt/"
ssh "${SSH_OPTS[@]}" "root@$IP" "export JAVA_HOME=/root/jdk PATH=/root/jdk/bin:/root/.jbang/bin:\$PATH; cd /root/ttt && \
  { echo \"host: hetzner $TYPE, ubuntu-24.04, $LOCATION\"; date -u +%Y-%m-%dT%H:%M:%SZ; lscpu | grep -E 'Model name|^CPU\(s\)|Core|Thread'; java -version 2>&1 | head -1; } > ENVIRONMENT.txt; \
  xvfb-run -a -s '-screen 0 1280x800x24' jbang -R-Dprism.order=sw -R-Dglass.gtk.uiScale=1 HarrietTicTacToeFx.java $APP_ARGS --record /root/ttt/rec"
scp "${SSH_OPTS[@]}" "root@$IP:/root/ttt/rec/demo.mp4" "root@$IP:/root/ttt/rec/demo.gif" "root@$IP:/root/ttt/ENVIRONMENT.txt" "$OUT/"
echo "clip in $OUT"
