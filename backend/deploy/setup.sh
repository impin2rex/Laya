#!/usr/bin/env bash
# One-shot installer for the Listen Together server on an Oracle Cloud
# (or any Ubuntu 22.04/24.04) VM. Safe to re-run: it rebuilds and restarts.
#
#   sudo bash deploy/setup.sh
#
# This installs the app only: Go, the binary, and the systemd service listening
# on 127.0.0.1:8000. TLS and the public host name are not its business -- the
# reverse proxy that publishes them is set up and owned by hand.
#
# First run on a fresh VM also takes DEPLOY_PUBKEY (the public half of the
# GitHub Actions deploy key) to create the restricted `deploy` user.
#
# Run it from the backend/ directory of a checkout on the VM.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

GO_VERSION="${GO_VERSION:-1.27.0}"
BACKEND_DIR="$(cd "$(dirname "$0")/.." && pwd)"

case "$(uname -m)" in
  aarch64) GOARCH=arm64 ;;   # Ampere A1 shape
  x86_64)  GOARCH=amd64 ;;   # E2.1.Micro shape
  *) echo "unsupported arch $(uname -m)"; exit 1 ;;
esac

echo "== packages"
if ! dpkg -s curl git iptables-persistent >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y curl git iptables-persistent
fi

echo "== Go $GO_VERSION"
if ! /usr/local/go/bin/go version 2>/dev/null | grep -q "go$GO_VERSION"; then
  curl -fsSL "https://go.dev/dl/go$GO_VERSION.linux-$GOARCH.tar.gz" -o /tmp/go.tgz
  rm -rf /usr/local/go && tar -C /usr/local -xzf /tmp/go.tgz && rm /tmp/go.tgz
fi

echo "== build"
id laya >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin laya
install -d -o laya -g laya /opt/laya-jam
(cd "$BACKEND_DIR" && /usr/local/go/bin/go build -o /opt/laya-jam/server .)
chown laya:laya /opt/laya-jam/server

echo "== service"
install -m 644 "$BACKEND_DIR/deploy/laya-jam.service" /etc/systemd/system/laya-jam.service
systemctl daemon-reload
systemctl enable laya-jam
systemctl restart laya-jam
# The CPU keepalive burner is retired; clean it off VMs that still have it.
if [ -f /etc/systemd/system/laya-keepalive.service ]; then
  systemctl disable --now laya-keepalive || true
  rm -f /etc/systemd/system/laya-keepalive.service
  systemctl daemon-reload
fi

echo "== deploy hook"
install -m 755 "$BACKEND_DIR/deploy/laya-deploy.sh" /usr/local/sbin/laya-deploy

if [ -n "${DEPLOY_PUBKEY:-}" ]; then
  echo "== deploy user"
  # The GitHub Actions key may only run the deploy hook (forced command).
  id deploy >/dev/null 2>&1 || useradd --create-home --shell /bin/bash deploy
  install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
  printf 'command="sudo /usr/local/sbin/laya-deploy",no-port-forwarding,no-X11-forwarding,no-agent-forwarding,no-pty %s\n' "$DEPLOY_PUBKEY" > /home/deploy/.ssh/authorized_keys
  chown deploy:deploy /home/deploy/.ssh/authorized_keys && chmod 600 /home/deploy/.ssh/authorized_keys
  # env_keep so the branch the workflow asks for survives sudo; laya-deploy
  # validates it before it reaches git.
  printf '%s\n%s\n' \
    'Defaults:deploy env_keep += "SSH_ORIGINAL_COMMAND"' \
    'deploy ALL=(root) NOPASSWD: /usr/local/sbin/laya-deploy' > /etc/sudoers.d/laya-deploy
  chmod 440 /etc/sudoers.d/laya-deploy && visudo -cf /etc/sudoers.d/laya-deploy
fi

echo "== kernel network limits"
# The micro VM's default conntrack table is tiny (7680) and keeps an idle
# established TCP flow for five days, which is how a crowd of phones that
# vanish without a FIN would fill it. Give it room and a one-hour memory.
cat > /etc/sysctl.d/90-laya.conf <<'SYSCTL'
net.netfilter.nf_conntrack_max = 65536
net.netfilter.nf_conntrack_tcp_timeout_established = 3600
SYSCTL
# Load the module at boot, or systemd-sysctl runs before it exists and the limit is lost.
echo nf_conntrack > /etc/modules-load.d/nf_conntrack.conf
sysctl --system >/dev/null 2>&1 || true

echo "== swap"
# 1 GB of RAM and no swap meant a connection spike could get something OOM-killed.
if ! swapon --show --noheadings | grep -q .; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
echo 'vm.swappiness = 1' > /etc/sysctl.d/91-laya-swap.conf
sysctl -q -p /etc/sysctl.d/91-laya-swap.conf || true

echo "== tcp buffers + health watchdog"
# Smaller default TCP buffers so thousands of idle sockets cost less kernel memory.
cat > /etc/sysctl.d/92-laya-tcp.conf <<'SYSCTL'
net.ipv4.tcp_rmem = 4096 16384 262144
net.ipv4.tcp_wmem = 4096 16384 262144
SYSCTL
sysctl -q -p /etc/sysctl.d/92-laya-tcp.conf || true
cat > /usr/local/sbin/laya-watchdog <<'WATCHDOG'
#!/usr/bin/env bash
# Restarts the server when its own health check has failed twice in a row -- a
# wedged process that is still alive, which Restart=always cannot see. Checks
# the app directly, not the proxy, so it says nothing about TLS being up.
# At most one restart per 5 minutes.
F=/run/laya-watchdog.fails; L=/run/laya-watchdog.last
if curl -s -m6 -o /dev/null -f http://127.0.0.1:8000/healthz; then echo 0 > $F; exit 0; fi
n=$(( $(cat $F 2>/dev/null || echo 0) + 1 )); echo $n > $F
[ "$n" -lt 2 ] && exit 0
now=$(date +%s); last=$(cat $L 2>/dev/null || echo 0)
[ $((now - last)) -lt 300 ] && exit 0
echo $now > $L; echo 0 > $F
logger -t laya-watchdog "health check failed twice; restarting laya-jam"
systemctl restart laya-jam
WATCHDOG
chmod 755 /usr/local/sbin/laya-watchdog
cat > /etc/systemd/system/laya-watchdog.service <<'UNIT'
[Unit]
Description=Restart laya-jam if its health check keeps failing
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/laya-watchdog
UNIT
cat > /etc/systemd/system/laya-watchdog.timer <<'UNIT'
[Unit]
Description=Run the Laya health watchdog every minute
[Timer]
OnBootSec=3min
OnUnitActiveSec=60s
AccuracySec=5s
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now laya-watchdog.timer >/dev/null 2>&1 || true

echo "== firewall"
# Oracle's Ubuntu images ship an iptables REJECT rule that blocks everything but
# SSH, independent of the VCN security list. Open 80/443 above it for whatever
# terminates TLS. Port 8000 stays closed, and the server binds 127.0.0.1 anyway.
for port in 80 443; do
  while iptables -D INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null; do :; done
  reject="$(iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" { print $1; exit }')"
  iptables -I INPUT "${reject:-1}" -p tcp --dport "$port" -m state --state NEW -j ACCEPT
done
netfilter-persistent save

echo "== check"
sleep 2
curl -fsS http://127.0.0.1:8000/healthz && echo
echo "Done. The server is on 127.0.0.1:8000; your proxy publishes it."
