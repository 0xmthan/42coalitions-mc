#!/usr/bin/env bash
#
# Build the Coalitions plugin and (by default) install it into ../server/plugins.
#
# Usage:
#   ./build.sh                # build + install into ../server/plugins
#   ./build.sh --no-install   # build only, jar lands in ./target
#
# Bootstraps a JDK 25 and Maven under ../.tools if the machine has none.
#
# Env overrides:
#   JAVA_HOME     JDK to build with (must be >= 25)
#   MVN           maven binary to use
#   SERVER_ROOT   where to install     (default ../server)

set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERVER_ROOT="${SERVER_ROOT:-$(cd -- "${HERE}/.." && pwd)/server}"
# Shared with the other plugins, so the JDK and Maven are fetched once.
TOOLS="$(cd -- "${HERE}/.." && pwd)/.tools"

JAVA_MIN=25
MAVEN_VERSION=3.9.16
JDK_URL="https://api.adoptium.net/v3/binary/latest/${JAVA_MIN}/ga/linux/x64/jdk/hotspot/normal/eclipse"
MAVEN_URL="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"

INSTALL=1
[ "${1:-}" = "--no-install" ] && INSTALL=0

log() { printf '\033[1;32m==>\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

if command -v curl >/dev/null; then
    fetch() { curl -fL --retry 3 --retry-delay 2 -o "$2" "$1"; }
elif command -v wget >/dev/null; then
    fetch() { wget -O "$2" "$1"; }
else
    die "need curl or wget"
fi

# --- jdk ------------------------------------------------------------------
javac_ok() {
    [ -x "$1" ] || return 1
    local v
    v="$("$1" -version 2>&1 | sed -n '1s/javac \([0-9][0-9]*\).*/\1/p')"
    [ -n "${v}" ] && [ "${v}" -ge "${JAVA_MIN}" ]
}

find_jdk() {
    local c
    for c in "${JAVA_HOME:-/nonexistent}" "${TOOLS}/jdk" /usr/lib/jvm/*; do
        javac_ok "${c}/bin/javac" && { printf '%s' "${c}"; return; }
    done
    printf ''
}

JDK="$(find_jdk)"
if [ -z "${JDK}" ]; then
    log "Downloading Temurin JDK ${JAVA_MIN} (no JDK ${JAVA_MIN}+ on this machine)"
    mkdir -p "${TOOLS}"
    stage="$(mktemp -d "${TOOLS}/.jdk-stage.XXXXXX")"
    trap 'rm -rf -- "${stage:-}"' EXIT
    fetch "${JDK_URL}" "${stage}/jdk.tar.gz" || die "JDK download failed"
    tar -xzf "${stage}/jdk.tar.gz" -C "${stage}" || die "JDK archive is corrupt -- rerun"
    inner="$(find "${stage}" -type f -path '*/bin/javac' -print -quit)"
    [ -n "${inner}" ] || die "javac not found in JDK archive"
    rm -rf -- "${TOOLS}/jdk"
    mv -- "$(dirname -- "$(dirname -- "${inner}")")" "${TOOLS}/jdk"
    rm -rf -- "${stage}"; trap - EXIT
    JDK="${TOOLS}/jdk"
fi
export JAVA_HOME="${JDK}"
log "JDK: ${JAVA_HOME}"

# --- maven ----------------------------------------------------------------
MVN="${MVN:-}"
if [ -z "${MVN}" ]; then
    if [ -x "${TOOLS}/maven/bin/mvn" ]; then
        MVN="${TOOLS}/maven/bin/mvn"
    elif command -v mvn >/dev/null; then
        MVN="$(command -v mvn)"
    else
        log "Downloading Maven ${MAVEN_VERSION}"
        mkdir -p "${TOOLS}"
        stage="$(mktemp -d "${TOOLS}/.mvn-stage.XXXXXX")"
        trap 'rm -rf -- "${stage:-}"' EXIT
        fetch "${MAVEN_URL}" "${stage}/maven.tar.gz" || die "Maven download failed"
        tar -xzf "${stage}/maven.tar.gz" -C "${stage}" || die "Maven archive is corrupt -- rerun"
        rm -rf -- "${TOOLS}/maven"
        mv -- "${stage}/apache-maven-${MAVEN_VERSION}" "${TOOLS}/maven"
        rm -rf -- "${stage}"; trap - EXIT
        MVN="${TOOLS}/maven/bin/mvn"
    fi
fi
log "Maven: ${MVN}"

# Installing through a temp file and renaming, rather than writing over the jar
# in place: a running server holds the jar open, and overwriting the same inode
# changes what it reads. Classes it has not loaded yet then come back missing --
# NoClassDefFoundError on something that is plainly there on disk. A rename
# leaves the old inode alone for whoever still has it open.
install_jar() {
    cp -- "$1" "$2.new"
    mv -f -- "$2.new" "$2"
}

warn_if_running() {
    if pgrep -f "paper-.*\\.jar" >/dev/null 2>&1; then
        printf '\033[1;33m!!\033[0m %s\n' \
            "The server is running: it keeps the old jar until you restart it." >&2
    fi
}

# --- build ----------------------------------------------------------------
cd -- "${HERE}"
"${MVN}" -q -B -Dmaven.repo.local="${TOOLS}/m2" clean install

JAR="${HERE}/target/Coalitions.jar"
[ -s "${JAR}" ] || die "build produced no jar"
log "Built ${JAR}"

if [ "${INSTALL}" = 1 ]; then
    [ -d "${SERVER_ROOT}" ] || die "no server dir at ${SERVER_ROOT} (use --no-install)"
    mkdir -p "${SERVER_ROOT}/plugins"
    install_jar "${JAR}" "${SERVER_ROOT}/plugins/Coalitions.jar"
    log "Installed into ${SERVER_ROOT}/plugins/"
    warn_if_running
fi
