#!/bin/bash
# Sourced by ContainerRuntime after the shared resumable apt helpers are defined.
# No remote installer scripts are executed. Ubuntu packages use apt's signed indexes;
# Node comes from nodejs.org over HTTPS and must match its published SHA-256 digest.
set -eu

if ! grep -q '^nameserver ' /etc/resolv.conf 2>/dev/null; then
    rm -f /etc/resolv.conf
    printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions timeout:2 attempts:2\n' > /etc/resolv.conf
fi
printf '127.0.0.1 localhost\n::1 localhost\n' > /etc/hosts
printf 'precedence ::ffff:0:0/96  100\n' > /etc/gai.conf

echo 'PocketAgent: preparing the native agent workspace'
rm -f "$PD_STATE/workspace-ready"
pd_repair
pd_update || exit 11
# A stage marker alone must not hide files removed after an earlier install.
if ! command -v git >/dev/null 2>&1 || ! command -v python3 >/dev/null 2>&1 \
        || ! command -v gcc >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
    rm -f "$PD_STATE/stage/workspace-tools-v1"
fi
pd_step workspace-tools-v1 ca-certificates curl git git-lfs openssh-client python3 \
    python3-pip python3-venv build-essential pkg-config unzip xz-utils jq procps \
    rsync lsof sudo || exit 14
if ! command -v git >/dev/null 2>&1 || ! command -v python3 >/dev/null 2>&1 \
        || ! command -v gcc >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
    # dpkg can still remember a package whose executable has been removed.
    apt-get install -y --reinstall --no-install-recommends git python3 gcc curl || exit 14
fi

# Keep the supported LTS line, while taking patch/security updates on first setup.
# A cancelled download stays in the private state directory and resumes by its
# immutable versioned filename on the next setup attempt.
if ! /usr/local/bin/node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 24 ? 0 : 1)' 2>/dev/null \
        || ! /usr/local/bin/npm --version >/dev/null 2>&1; then
    echo 'PocketAgent: downloading the official Node.js 24 ARM64 runtime'
    pd_node_dir="$PD_STATE/downloads/node"
    mkdir -p "$pd_node_dir" /usr/local
    curl --proto '=https' --tlsv1.2 --fail --location --retry 3 --connect-timeout 20 \
        --max-time 180 --max-filesize 2097152 \
        'https://nodejs.org/dist/latest-v24.x/SHASUMS256.txt' \
        -o "$pd_node_dir/SHASUMS256.txt.new" || exit 22
    mv "$pd_node_dir/SHASUMS256.txt.new" "$pd_node_dir/SHASUMS256.txt"
    pd_node_line=$(awk 'length($1) == 64 && $1 !~ /[^0-9a-f]/ && $2 ~ /^node-v24\.[0-9]+\.[0-9]+-linux-arm64\.tar\.xz$/ {print $1 " " $2}' "$pd_node_dir/SHASUMS256.txt")
    # Both fields passed strict publisher-format checks before becoming path data.
    set -- $pd_node_line
    [ "$#" -eq 2 ] || exit 22
    pd_node_digest=$1
    pd_node_archive=$2
    pd_node_version=${pd_node_archive#node-}
    pd_node_version=${pd_node_version%-linux-arm64.tar.xz}
    pd_node_file="$pd_node_dir/$pd_node_archive"
    if ! printf '%s  %s\n' "$pd_node_digest" "$pd_node_file" | sha256sum --check --status 2>/dev/null; then
        if ! curl --proto '=https' --tlsv1.2 --fail --location --retry 3 --connect-timeout 20 \
            --max-time 1800 --max-filesize 157286400 -C - \
            "https://nodejs.org/dist/$pd_node_version/$pd_node_archive" \
            -o "$pd_node_file"; then
            # Some mirrors refuse Range, and a corrupt complete file gives 416.
            # Restart into the same unpublished cache file; install still waits
            # for checksum verification below.
            curl --proto '=https' --tlsv1.2 --fail --location --retry 3 --connect-timeout 20 \
                --max-time 1800 --max-filesize 157286400 \
                "https://nodejs.org/dist/$pd_node_version/$pd_node_archive" \
                -o "$pd_node_file" || exit 22
        fi
    fi
    if ! printf '%s  %s\n' "$pd_node_digest" "$pd_node_file" | sha256sum --check --status; then
        rm -f "$pd_node_file"
        echo 'PocketAgent: Node checksum mismatch; download removed' >&2
        exit 22
    fi
    tar -xJf "$pd_node_file" -C /usr/local --strip-components=1 --no-same-owner || exit 22
    rm -f "$pd_node_file"
fi

id coder >/dev/null 2>&1 || useradd -m -s /bin/bash coder
mkdir -p /home/coder/Projects /home/coder/.local/bin /home/coder/.pocketagent \
    /home/coder/.cache /home/coder/.config
chown coder:coder /home/coder /home/coder/Projects /home/coder/.local \
    /home/coder/.local/bin /home/coder/.pocketagent /home/coder/.cache /home/coder/.config
# Agent engines run in the user's project; the native app invokes administrative
# setup separately. Do not silently grant an agent passwordless sudo here.
echo 'PocketAgent: checking Git, Python, compiler, Node and npm'
git --version && python3 --version && gcc --version >/dev/null \
    && /usr/local/bin/node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 24 ? 0 : 1)' \
    && /usr/local/bin/npm --version || exit 23
printf '%s\n' "${POCKETAGENT_APP_VERSION:-unknown}" > "$PD_STATE/workspace-ready.tmp"
mv "$PD_STATE/workspace-ready.tmp" "$PD_STATE/workspace-ready"
apt-get clean
echo 'PocketAgent: workspace ready'
