#!/usr/bin/env bash
#
# Build the Coalitions plugin for local testing. Release jars are built by
# .github/workflows/release.yml instead.
#
# Usage:
#   ./build.sh                    # build only, jar lands in ./target
#   ./build.sh --install <server> # build + drop the jar in <server>/plugins
#
# Bootstraps a JDK 25 and Maven under ./.tools if the machine has none.
#
# Env overrides:
#   JAVA_HOME     JDK to build with (must be >= 25)
#   MVN           maven binary to use
#   SERVER_ROOT   same as --install <server>

set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERVER_ROOT="${SERVER_ROOT:-}"
TOOLS="${HERE}/.tools"

JAVA_MIN=25
MAVEN_VERSION=3.9.16
MAVEN_URL="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"

log() { printf '\033[1;32m==>\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }
usage() { sed -n '6,8s/^# \{0,1\}//p' "${BASH_SOURCE[0]}" >&2; exit "${1:-2}"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --install)  [ $# -ge 2 ] || usage; SERVER_ROOT="$2"; shift 2 ;;
        -h|--help)  usage 0 ;;
        *)          usage ;;
    esac
done

case "$(uname -s)" in
    Linux)  JDK_OS=linux ;;
    Darwin) JDK_OS=mac ;;
    *)      JDK_OS="" ;;
esac
case "$(uname -m)" in
    x86_64|amd64)  JDK_ARCH=x64 ;;
    arm64|aarch64) JDK_ARCH=aarch64 ;;
    *)             JDK_ARCH="" ;;
esac
JDK_URL="https://api.adoptium.net/v3/binary/latest/${JAVA_MIN}/ga/${JDK_OS}/${JDK_ARCH}/jdk/hotspot/normal/eclipse"

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
    # Whatever javac is on PATH (Homebrew and the like), resolved to its home.
    if c="$(command -v javac)" && javac_ok "${c}"; then
        c="$(cd -- "$(dirname -- "$(readlink -f -- "${c}")")/.." && pwd)"
        printf '%s' "${c}"; return
    fi
    printf ''
}

JDK="$(find_jdk)"
if [ -z "${JDK}" ]; then
    [ -n "${JDK_OS}" ] && [ -n "${JDK_ARCH}" ] \
        || die "no JDK ${JAVA_MIN}+ found and no download for $(uname -sm) -- set JAVA_HOME"
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
"${MVN}" -q -B -Dmaven.repo.local="${TOOLS}/m2" clean package

JAR="${HERE}/target/Coalitions.jar"
[ -s "${JAR}" ] || die "build produced no jar"
log "Built ${JAR}"

if [ -n "${SERVER_ROOT}" ]; then
    [ -d "${SERVER_ROOT}" ] || die "no server dir at ${SERVER_ROOT}"
    mkdir -p "${SERVER_ROOT}/plugins"
    install_jar "${JAR}" "${SERVER_ROOT}/plugins/Coalitions.jar"
    log "Installed into ${SERVER_ROOT}/plugins/"
    warn_if_running
fi
