#!/usr/bin/env bash
# Runs reproduce.sh on a fresh Hetzner CCX33 (4 dedicated AMD EPYC Milan cores, 8 vCPU, 32 GB,
# about 0.70 EUR per hour at the time of writing) and deletes the server when done, whatever happens.
#
# Needs the hcloud CLI and three things in the environment:
#   HCLOUD_TOKEN     an API token for a Hetzner Cloud project
#   HCLOUD_SSH_KEY   the name of an SSH key already uploaded to that project
#   SSH_IDENTITY     the matching private key (default ~/.ssh/id_ed25519)
# Optional: HCLOUD_TYPE (default ccx33), HCLOUD_LOCATION (default nbg1), OUT (default ../out-vps).
#
# Nothing secret goes to the box: the Jev scripts are not copied and TYPESAFE_API_KEY is not
# forwarded, so the hosted column is always produced from your own machine.
set -uo pipefail
: "${HCLOUD_TOKEN:?set HCLOUD_TOKEN}"
: "${HCLOUD_SSH_KEY:?set HCLOUD_SSH_KEY to the name of an SSH key in the Hetzner project}"
IDENTITY="${SSH_IDENTITY:-$HOME/.ssh/id_ed25519}"
TYPE="${HCLOUD_TYPE:-ccx33}"
LOCATION="${HCLOUD_LOCATION:-nbg1}"
CODE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$CODE/out-vps}"
NAME="harriet-bench-$(date +%Y%m%d%H%M%S)"
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
apt-get -qq update >/dev/null && apt-get -qq install -y unzip zip curl >/dev/null
curl -sL https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse -o /tmp/jdk.tgz
mkdir -p /root/jdk && tar xzf /tmp/jdk.tgz -C /root/jdk --strip-components=1
curl -Ls https://sh.jbang.dev | bash -s - app setup >/dev/null 2>&1 || true
mkdir -p /root/code
REMOTE

scp "${SSH_OPTS[@]}" "$CODE"/*.java "$CODE/reproduce.sh" "root@$IP:/root/code/"
ssh "${SSH_OPTS[@]}" "root@$IP" \
  'export JAVA_HOME=/root/jdk PATH=/root/jdk/bin:/root/.jbang/bin:$PATH; OUT=/root/out bash /root/code/reproduce.sh'
scp "${SSH_OPTS[@]}" -r "root@$IP:/root/out/." "$OUT/"
echo "logs in $OUT"
