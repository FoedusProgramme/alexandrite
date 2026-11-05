#!/bin/sh
# Installs, upgrades or updates Alexandrite for the current user on Linux and macOS:
#   curl -fsSL https://alexandrite.unitedsoftware.org/install.sh | sh
#   curl -fsSL https://alexandrite.unitedsoftware.org/install.sh | sh -s -- --version 0.2.0 --no-init
set -eu

RELEASES=${ALEXANDRITE_INSTALL_BASE:-https://github.com/FoedusProgramme/alexandrite/releases}
ADOPTIUM=${ALEXANDRITE_ADOPTIUM_BASE:-https://api.adoptium.net}
ROOT=${ALEXANDRITE_INSTALL_DIR:-$HOME/.local/lib/alexandrite}
BIN_DIR=${ALEXANDRITE_BIN_DIR:-$HOME/.local/bin}
READY_TIMEOUT=${ALEXANDRITE_READY_TIMEOUT:-90}
TTY=${ALEXANDRITE_INSTALL_TTY:-/dev/tty}
SYSTEMD_UNIT=alexandrite.service
LAUNCHD_LABEL=org.unitedsoftware.alexandrite
JAVA_MAJOR=21
CHANNEL=${ALEXANDRITE_CHANNEL:-stable}
TAG=
MARK_BEGIN='# >>> alexandrite >>>'
MARK_END='# <<< alexandrite <<<'
STAGES=4

# The English texts by key, as printf formats whose %s take the arguments of `text`.
texts_en() {
    case $1 in
        usage) T="Usage: install.sh [--version <v>] [--channel <stable|ci>] [--no-init] [--no-modify-path] [--update] [--download-jre] [--config <file>] [--data-dir <dir>]

Installs Alexandrite into %s and links the command into %s.
Running it again upgrades in place; two versions are kept.
When no config file exists yet, it then runs 'alexandrite init' on the terminal.

  --version <v>      install release <v> instead of the latest
  --channel <c>      follow stable releases (the default) or CI builds of master
  --no-init          do not run 'alexandrite init' after installing
  --no-modify-path   do not add %s to PATH in the shell's startup files
  --update           update an existing installation (what 'alexandrite update' runs)
  --download-jre     use a Java runtime of Alexandrite's own even when the system has Java %s+
  --config <file>    the config file the new version is checked against
  --data-dir <dir>   the data directory of the instance to wait for

ALEXANDRITE_INSTALL_DIR and ALEXANDRITE_BIN_DIR change the two directories.
ALEXANDRITE_INSTALL_BASE and ALEXANDRITE_ADOPTIUM_BASE replace the download sites (file:// URLs are for testing).
ALEXANDRITE_INSTALL_TTY replaces the terminal device init reads from (for testing)." ;;
        stage_environment) T="Environment" ;;
        stage_java) T="Java" ;;
        stage_download) T="Download and verify" ;;
        stage_install) T="Install" ;;
        doing) T="%s ..." ;;
        detected) T="Detected: %s %s" ;;
        key_os) T="OS:" ;;
        key_arch) T="Architecture:" ;;
        key_root) T="Install directory:" ;;
        key_command) T="Command:" ;;
        key_installed) T="Installed version:" ;;
        key_release) T="Release:" ;;
        key_latest) T="Latest release:" ;;
        key_channel) T="Channel:" ;;
        key_version) T="Version:" ;;
        key_location) T="Location:" ;;
        key_java) T="Java:" ;;
        none) T="none" ;;
        version_was) T="%s (was %s)" ;;
        needs_value) T="%s needs a value." ;;
        unknown_option) T="Unknown option %s." ;;
        relative_root) T="ALEXANDRITE_INSTALL_DIR must be an absolute path." ;;
        no_download_tool) T="Neither curl nor wget is installed." ;;
        refused_url) T="Refusing to download %s: only https:// and file:// URLs are used." ;;
        no_sha_tool) T="No SHA-256 tool (sha256sum, shasum or openssl) is installed." ;;
        checksum_mismatch) T="The checksum of %s does not match (%s, expected %s); nothing was changed." ;;
        checksum_ok) T="The checksum of %s matches." ;;
        jre_checksum_mismatch) T="The checksum of the Java runtime does not match (%s, expected %s); nothing was changed." ;;
        jre_checksum_ok) T="The checksum of the Java runtime matches." ;;
        on_windows) T="On Windows, run install.ps1 in PowerShell." ;;
        unsupported_os) T="Alexandrite installs on Linux and macOS only, not on %s." ;;
        unsupported_arch) T="Alexandrite installs on x64 and arm64 only, not on %s." ;;
        java_found) T="Java %s: %s" ;;
        no_jre) T="Adoptium names no Java %s runtime for %s %s." ;;
        jre_needed) T="No Java %s+ found; downloading a Java runtime from Adoptium into %s." ;;
        jre_downloading) T="Downloading the Java runtime" ;;
        jre_downloaded) T="Downloaded the Java runtime" ;;
        jre_unpacking) T="Unpacking the Java runtime" ;;
        jre_unpacked) T="Unpacked the Java runtime" ;;
        cannot_download) T="Cannot download %s." ;;
        cannot_unpack_jre) T="Cannot unpack the Java runtime." ;;
        jre_not_one) T="The Java runtime archive is not one directory." ;;
        jre_no_java) T="The Java runtime archive holds no bin/java." ;;
        jre_broken) T="The downloaded Java runtime does not run." ;;
        no_release) T="Cannot download %s: no such release?" ;;
        no_archive) T="%s lists no alexandrite-<version>.tar.gz." ;;
        wrong_archive) T="Release %s holds %s." ;;
        no_version) T="'%s' is no version." ;;
        no_channel) T="'%s' is no release channel; use stable or ci." ;;
        downloading) T="Downloading Alexandrite %s" ;;
        downloaded) T="Downloaded %s" ;;
        unpacking) T="Unpacking %s" ;;
        unpacked) T="Unpacked %s" ;;
        cannot_unpack) T="Cannot unpack %s." ;;
        no_binary) T="%s holds no alexandrite-%s/bin/alexandrite." ;;
        does_not_start) T="Alexandrite %s does not start:" ;;
        names_itself) T="Alexandrite %s names itself '%s'." ;;
        starts) T="Alexandrite %s starts." ;;
        check_passes) T="The config passes the check of Alexandrite %s." ;;
        both_fail) T="The config fails the check of %s and of the installed %s alike; going on:" ;;
        check_fails) T="The config fails the check of Alexandrite %s:" ;;
        own_file) T="%s is a file of its own and stays; the command is %s." ;;
        linked) T="Linked %s" ;;
        not_on_path) T="%s is not on your PATH. Add this line to your shell's profile (~/.profile, ~/.bashrc or ~/.zshrc):
  export PATH=\"%s:\$PATH\"" ;;
        separator) T=", " ;;
        path_added) T="Added %s to PATH in %s." ;;
        path_present) T="The shell's startup files add %s to PATH already." ;;
        path_unwritable) T="Cannot add %s to PATH in %s." ;;
        not_installed) T="Alexandrite is not installed in %s; run install.sh without --update." ;;
        locked) T="Another install runs in %s (remove %s if none does)." ;;
        installed_already) T="Alexandrite %s is installed already." ;;
        newer) T="Alexandrite %s is newer than the latest release %s; nothing to do." ;;
        not_installed_stays) T="Alexandrite %s was not installed; %s stays." ;;
        not_installed_new) T="Alexandrite %s was not installed." ;;
        switched) T="Switched to Alexandrite %s." ;;
        restarting) T="Restarting the background service with Alexandrite %s ..." ;;
        no_restart) T="The background service did not restart with Alexandrite %s." ;;
        no_answer) T="Alexandrite %s did not answer within %s seconds." ;;
        restarted) T="The background service runs Alexandrite %s." ;;
        no_service) T="No background service runs; the next start runs Alexandrite %s." ;;
        not_back) T="Alexandrite %s did not come back either; see its logs." ;;
        switched_back) T="Switched back to Alexandrite %s." ;;
        installed_was) T="Alexandrite %s is installed (was %s)." ;;
        installed_at) T="Alexandrite %s is installed: %s" ;;
        installed) T="Alexandrite %s is installed." ;;
        run) T="Run: %s" ;;
        reload) T="Open a new terminal, or run: %s" ;;
        then_run) T="Then run: alexandrite" ;;
        starting_setup) T="Starting setup: alexandrite init" ;;
        setup_skipped) T="Setup skipped (no terminal); run 'alexandrite init' after install." ;;
        *) return 1 ;;
    esac
}

texts_zh() {
    case $1 in
        usage) T="用法：install.sh [--version <版本>] [--channel <stable|ci>] [--no-init] [--no-modify-path] [--update] [--download-jre] [--config <文件>] [--data-dir <目录>]

把 Alexandrite 安装到 %s，并把命令链接到 %s。
再次运行即原地升级；保留两个版本。
如果还没有配置文件，随后在终端上运行 'alexandrite init'。

  --version <版本>   安装指定版本，而不是最新版本
  --channel <频道>   跟随稳定发布（默认）或 master 的 CI 构建
  --no-init          安装后不运行 'alexandrite init'
  --no-modify-path   不在 shell 的启动文件中把 %s 加入 PATH
  --update           更新已有的安装（'alexandrite update' 运行的就是它）
  --download-jre     即使系统已有 Java %s+，也使用 Alexandrite 自带的 Java 运行时
  --config <文件>    检查新版本时使用的配置文件
  --data-dir <目录>  要等待其就绪的实例的数据目录

ALEXANDRITE_INSTALL_DIR 和 ALEXANDRITE_BIN_DIR 可改变这两个目录。
ALEXANDRITE_INSTALL_BASE 和 ALEXANDRITE_ADOPTIUM_BASE 可替换下载地址（file:// 地址用于测试）。
ALEXANDRITE_INSTALL_TTY 可替换 init 读取的终端设备（用于测试）。" ;;
        stage_environment) T="环境" ;;
        stage_download) T="下载并校验" ;;
        stage_install) T="安装" ;;
        doing) T="%s……" ;;
        detected) T="已检测：%s %s" ;;
        key_os) T="系统：" ;;
        key_arch) T="架构：" ;;
        key_root) T="安装目录：" ;;
        key_command) T="命令：" ;;
        key_installed) T="已安装版本：" ;;
        key_release) T="发布版本：" ;;
        key_latest) T="最新版本：" ;;
        key_channel) T="频道：" ;;
        key_version) T="版本：" ;;
        key_location) T="位置：" ;;
        key_java) T="运行时：" ;;
        none) T="无" ;;
        version_was) T="%s（原为 %s）" ;;
        needs_value) T="%s 需要一个值。" ;;
        unknown_option) T="未知选项 %s。" ;;
        relative_root) T="ALEXANDRITE_INSTALL_DIR 必须是绝对路径。" ;;
        no_download_tool) T="curl 和 wget 都没有安装。" ;;
        refused_url) T="拒绝下载 %s：只使用 https:// 和 file:// 地址。" ;;
        no_sha_tool) T="没有安装 SHA-256 工具（sha256sum、shasum 或 openssl）。" ;;
        checksum_mismatch) T="%s 的校验和不符（%s，应为 %s）；未做任何更改。" ;;
        checksum_ok) T="%s 的校验和相符。" ;;
        jre_checksum_mismatch) T="Java 运行时的校验和不符（%s，应为 %s）；未做任何更改。" ;;
        jre_checksum_ok) T="Java 运行时的校验和相符。" ;;
        on_windows) T="在 Windows 上请在 PowerShell 中运行 install.ps1。" ;;
        unsupported_os) T="Alexandrite 只能安装在 Linux 和 macOS 上，不支持 %s。" ;;
        unsupported_arch) T="Alexandrite 只能安装在 x64 和 arm64 上，不支持 %s。" ;;
        java_found) T="Java %s：%s" ;;
        no_jre) T="Adoptium 没有提供 Java %s 运行时（%s %s）。" ;;
        jre_needed) T="未找到 Java %s+；从 Adoptium 下载 Java 运行时到 %s。" ;;
        jre_downloading) T="正在下载 Java 运行时" ;;
        jre_downloaded) T="已下载 Java 运行时" ;;
        jre_unpacking) T="正在解压 Java 运行时" ;;
        jre_unpacked) T="已解压 Java 运行时" ;;
        cannot_download) T="无法下载 %s。" ;;
        cannot_unpack_jre) T="无法解压 Java 运行时。" ;;
        jre_not_one) T="Java 运行时压缩包不是单个目录。" ;;
        jre_no_java) T="Java 运行时压缩包中没有 bin/java。" ;;
        jre_broken) T="下载的 Java 运行时无法运行。" ;;
        no_release) T="无法下载 %s：该版本不存在？" ;;
        no_archive) T="%s 中没有 alexandrite-<版本>.tar.gz。" ;;
        wrong_archive) T="版本 %s 中的压缩包是 %s。" ;;
        no_version) T="'%s' 不是版本号。" ;;
        no_channel) T="'%s' 不是发布频道；请使用 stable 或 ci。" ;;
        downloading) T="正在下载 Alexandrite %s" ;;
        downloaded) T="已下载 %s" ;;
        unpacking) T="正在解压 %s" ;;
        unpacked) T="已解压 %s" ;;
        cannot_unpack) T="无法解压 %s。" ;;
        no_binary) T="%s 中没有 alexandrite-%s/bin/alexandrite。" ;;
        does_not_start) T="Alexandrite %s 无法启动：" ;;
        names_itself) T="Alexandrite %s 报告的版本是 '%s'。" ;;
        starts) T="Alexandrite %s 可以启动。" ;;
        check_passes) T="配置通过了 Alexandrite %s 的检查。" ;;
        both_fail) T="配置在 %s 和已安装的 %s 上都未通过检查；继续：" ;;
        check_fails) T="配置未通过 Alexandrite %s 的检查：" ;;
        own_file) T="%s 是另一个文件，保持不变；命令位于 %s。" ;;
        linked) T="已链接 %s" ;;
        not_on_path) T="%s 不在 PATH 中。请把这一行加入 shell 的配置文件（~/.profile、~/.bashrc 或 ~/.zshrc）：
  export PATH=\"%s:\$PATH\"" ;;
        separator) T="、" ;;
        path_added) T="已把 %s 加入 PATH，改动的文件：%s。" ;;
        path_present) T="shell 的启动文件已把 %s 加入 PATH。" ;;
        path_unwritable) T="无法把 %s 加入 %s 中的 PATH。" ;;
        not_installed) T="%s 中没有安装 Alexandrite；请不带 --update 运行 install.sh。" ;;
        locked) T="%s 中正在进行另一次安装（如果没有，请删除 %s）。" ;;
        installed_already) T="Alexandrite %s 已经安装。" ;;
        newer) T="Alexandrite %s 比最新发布的 %s 更新；无需操作。" ;;
        not_installed_stays) T="未安装 Alexandrite %s；继续使用 %s。" ;;
        not_installed_new) T="未安装 Alexandrite %s。" ;;
        switched) T="已切换到 Alexandrite %s。" ;;
        restarting) T="正在用 Alexandrite %s 重启后台服务……" ;;
        no_restart) T="后台服务未能用 Alexandrite %s 重启。" ;;
        no_answer) T="Alexandrite %s 在 %s 秒内没有响应。" ;;
        restarted) T="后台服务正在运行 Alexandrite %s。" ;;
        no_service) T="没有正在运行的后台服务；下次启动时将运行 Alexandrite %s。" ;;
        not_back) T="Alexandrite %s 也未能恢复运行；请查看日志。" ;;
        switched_back) T="已切换回 Alexandrite %s。" ;;
        installed_was) T="已安装 Alexandrite %s（原为 %s）。" ;;
        installed_at) T="已安装 Alexandrite %s：%s" ;;
        installed) T="Alexandrite %s 已安装。" ;;
        run) T="运行：%s" ;;
        reload) T="请打开新的终端，或运行：%s" ;;
        then_run) T="然后运行：alexandrite" ;;
        starting_setup) T="开始设置：alexandrite init" ;;
        setup_skipped) T="已跳过设置（没有终端）；安装后请运行 'alexandrite init'。" ;;
        *) return 1 ;;
    esac
}

case "${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}" in
    zh*) ZH=1 ;;
    *) ZH=0 ;;
esac

# The text of key $1 in the chosen language, English where it has none, with its %s filled from the rest.
text() {
    key=$1
    shift
    T=
    if [ "$ZH" = 0 ] || ! texts_zh "$key"; then texts_en "$key"; fi
    # shellcheck disable=SC2059
    printf "$T" "$@"
}

# Stages, glyphs and the status line only on a terminal; colours also need NO_COLOR unset.
FANCY=0
FANCY_ERR=0
if [ -t 1 ] && [ "${TERM:-dumb}" != dumb ]; then FANCY=1; fi
if [ -t 2 ] && [ "${TERM:-dumb}" != dumb ]; then FANCY_ERR=1; fi
ESC=$(printf '\033')
RED= GREEN= YELLOW= CYAN= DIM= BOLD= RESET= ERR_RED= ERR_YELLOW= ERR_DIM= ERR_RESET= CLEAR= HIDE= SHOW=
if [ "$FANCY" = 1 ]; then
    CLEAR="$ESC[2K" HIDE="$ESC[?25l" SHOW="$ESC[?25h"
    if [ -z "${NO_COLOR:-}" ]; then
        RED="$ESC[31m" GREEN="$ESC[32m" YELLOW="$ESC[33m" CYAN="$ESC[36m" DIM="$ESC[2m" BOLD="$ESC[1m" RESET="$ESC[0m"
    fi
fi
if [ "$FANCY_ERR" = 1 ] && [ -z "${NO_COLOR:-}" ]; then
    ERR_RED="$ESC[31m" ERR_YELLOW="$ESC[33m" ERR_DIM="$ESC[2m" ERR_RESET="$ESC[0m"
fi

say() {
    printf '%s\n' "$(text "$@")"
}

# Prints the text $3 led by the glyph $1 in the colour $2 on a terminal, the bare text otherwise.
mark() {
    if [ "$FANCY" = 1 ]; then printf '  %s%s%s %s\n' "$2" "$1" "$RESET" "$3"; else printf '%s\n' "$3"; fi
}

ok() {
    mark '✓' "$GREEN" "$(text "$@")"
}

info() {
    mark '·' "$DIM" "$(text "$@")"
}

point() {
    mark '→' "$CYAN" "$(text "$@")"
}

# An outcome worth a line on a terminal only.
tick() {
    if [ "$FANCY" = 1 ]; then ok "$@"; fi
}

warn() {
    if [ "$FANCY_ERR" = 1 ]; then
        printf '  %s!%s %s\n' "$ERR_YELLOW" "$ERR_RESET" "$(text "$@")" >&2
    else
        say "$@" >&2
    fi
}

die() {
    if [ "$FANCY_ERR" = 1 ]; then
        printf '  %s✗%s %s\n' "$ERR_RED" "$ERR_RESET" "$(text "$@")" >&2
    else
        say "$@" >&2
    fi
    exit 1
}

# The columns that the UTF-8 text $1 takes, taking each character of three or four bytes as a wide one.
columns() {
    bytes=$(printf '%s' "$1" | LC_ALL=C wc -c)
    characters=$(printf '%s' "$1" | LC_ALL=C tr -d '\200-\277' | LC_ALL=C wc -c)
    printf '%s' $((characters + (bytes - characters) / 2))
}

# Pads the text $1 with spaces to $2 columns.
padded() {
    pad=$(($2 - $(columns "$1")))
    if [ "$pad" -lt 1 ]; then pad=1; fi
    printf "%s%-${pad}s" "$1" ''
}

stage() {
    [ "$FANCY" = 1 ] || return 0
    printf '\n%s[%s/%s] %s%s\n' "$BOLD" "$1" "$STAGES" "$(text "$2")" "$RESET"
}

# Prints the key text $1 and the value $2 in aligned columns on a terminal.
kv() {
    [ "$FANCY" = 1 ] || return 0
    printf '    %s%s%s%s\n' "$DIM" "$(padded "$(text "$1")" 20)" "$RESET" "$2"
}

# The path $1 with the home directory shown as ~.
shown() {
    case $1 in
        "$HOME"/*) printf '~/%s' "${1#"$HOME"/}" ;;
        *) printf '%s' "$1" ;;
    esac
}

# Runs "$@" with its output in a log behind a status line $1 that becomes $2; shows the log's end when it fails.
step() {
    doing=$1 finished=$2
    shift 2
    log=$TMP/step.log
    if [ "$FANCY" = 0 ]; then
        say doing "$doing"
        if ("$@") >"$log" 2>&1; then return 0; fi
        tail -n 10 "$log" | sed 's/^/  | /' >&2
        return 1
    fi
    (
        trap - EXIT INT TERM HUP
        "$@"
    ) >"$log" 2>&1 &
    pid=$!
    printf '%s' "$HIDE"
    frame=0
    while kill -0 "$pid" 2>/dev/null; do
        case $frame in
            0) glyph='◒' ;;
            1) glyph='◐' ;;
            2) glyph='◓' ;;
            *) glyph='◑' ;;
        esac
        printf '\r%s  %s%s%s %s' "$CLEAR" "$CYAN" "$glyph" "$RESET" "$doing"
        frame=$(((frame + 1) % 4))
        sleep 0.1 2>/dev/null || sleep 1
    done
    if wait "$pid"; then code=0; else code=$?; fi
    printf '\r%s%s' "$CLEAR" "$SHOW"
    if [ "$code" = 0 ]; then
        mark '✓' "$GREEN" "$finished"
        return 0
    fi
    mark '✗' "$RED" "$doing"
    tail -n 10 "$log" | sed "s/^/    ${ERR_DIM}│${ERR_RESET} /" >&2
    return 1
}

usage() {
    say usage "$ROOT" "$BIN_DIR" "$BIN_DIR" "$JAVA_MAJOR"
}

have() {
    command -v "$1" >/dev/null 2>&1
}

fetch() {
    case $1 in
        file://*) cp "${1#file://}" "$2" ;;
        https://*)
            if have curl; then
                curl -fsSL --proto '=https' --proto-redir '=https' --retry 3 -o "$2" "$1"
            elif have wget; then
                wget -q --https-only -O "$2" "$1"
            else
                die no_download_tool
            fi
            ;;
        *) die refused_url "$1" ;;
    esac
}

# The location $1 redirects to, $1 itself for a file:// URL.
redirect_target() {
    case $1 in
        file://*) printf '%s\n' "$1" ;;
        *)
            if have curl; then
                curl -fsS --proto '=https' -o /dev/null -w '%{redirect_url}' "$1"
            else
                wget --https-only --max-redirect=0 -S --spider "$1" 2>&1 |
                    sed -n 's/^ *[Ll]ocation: *//p' | tr -d '\r' | head -n 1
            fi
            ;;
    esac
}

sha256() {
    if have sha256sum; then
        sha256sum "$1" | cut -d ' ' -f 1
    elif have shasum; then
        shasum -a 256 "$1" | cut -d ' ' -f 1
    elif have openssl; then
        openssl dgst -sha256 -r "$1" | cut -d ' ' -f 1
    else
        return 1
    fi
}

# Checks the file $1 against the hash $2, naming it $3, or the Java runtime when $3 is empty.
verify() {
    actual=$(sha256 "$1") || die no_sha_tool
    expected=$(printf '%s' "$2" | tr 'A-F' 'a-f')
    if [ -z "$expected" ] || [ "$actual" != "$expected" ]; then
        if [ -n "$3" ]; then die checksum_mismatch "$3" "$actual" "$expected"; fi
        die jre_checksum_mismatch "$actual" "$expected"
    fi
    if [ -n "$3" ]; then tick checksum_ok "$3"; else tick jre_checksum_ok; fi
}

# Whether version $1 sorts before $2, a pre-release (`1.0.0-rc1`) before its release.
version_lt() {
    awk -v a="$1" -v b="$2" 'BEGIN {
        na = split(a, A, "-"); nb = split(b, B, "-")
        ca = split(A[1], X, "."); cb = split(B[1], Y, ".")
        n = ca > cb ? ca : cb
        for (i = 1; i <= n; i++) {
            if (X[i] + 0 < Y[i] + 0) exit 0
            if (X[i] + 0 > Y[i] + 0) exit 1
        }
        exit (na > 1 && nb == 1) ? 0 : 1
    }'
}

detect_platform() {
    case $(uname -s) in
        Linux)
            OS=linux
            if [ -f /etc/alpine-release ] || ldd --version 2>&1 | grep -qi musl; then OS=alpine-linux; fi
            ;;
        Darwin) OS=mac ;;
        MINGW* | MSYS* | CYGWIN*) die on_windows ;;
        *) die unsupported_os "$(uname -s)" ;;
    esac
    case $(uname -m) in
        x86_64 | amd64) ARCH=x64 ;;
        aarch64 | arm64) ARCH=aarch64 ;;
        *) die unsupported_arch "$(uname -m)" ;;
    esac
    if [ "$OS" = mac ] && [ "$ARCH" = x64 ] && [ "$(sysctl -n sysctl.proc_translated 2>/dev/null || true)" = 1 ]; then
        ARCH=aarch64
    fi
}

os_name() {
    if [ "$OS" = mac ]; then
        printf 'macOS %s' "$(sw_vers -productVersion 2>/dev/null || true)"
    elif [ -r /etc/os-release ]; then
        name=$(sed -n 's/^PRETTY_NAME=//p' /etc/os-release | tr -d '"' | head -n 1)
        printf '%s' "${name:-Linux}"
    else
        printf 'Linux'
    fi
}

show_environment() {
    [ "$FANCY" = 1 ] || return 0
    ok detected "$OS" "$ARCH"
    kv key_os "$(os_name)"
    kv key_arch "$ARCH"
    kv key_root "$ROOT"
    kv key_command "$BIN_DIR/alexandrite"
    kv key_installed "${PREV:-$(text none)}"
    kv key_channel "$CHANNEL"
    if [ "$PINNED" = 1 ]; then kv key_release "$VERSION"; else kv key_latest "$VERSION"; fi
}

java_major() {
    [ -x "$1" ] || return 1
    v=$("$1" -version 2>&1 | sed -n 's/.* version "\([^"]*\)".*/\1/p' | head -n 1)
    case $v in 1.*) v=${v#1.} ;; esac
    v=${v%%[!0-9]*}
    [ -n "$v" ] && printf '%s\n' "$v"
}

java_ok() {
    major=$(java_major "$1") && [ "$major" -ge "$JAVA_MAJOR" ]
}

# Sets JAVA_PIN to the Java home the launcher uses and records it in java-home.
ensure_java() {
    JAVA_PIN=
    if [ "$DOWNLOAD_JRE" = 0 ]; then
        if [ -f "$ROOT/java-home" ] && java_ok "$(cat "$ROOT/java-home")/bin/java"; then
            JAVA_PIN=$(cat "$ROOT/java-home")
        elif [ -n "${JAVA_HOME:-}" ] && java_ok "$JAVA_HOME/bin/java"; then
            JAVA_PIN=$JAVA_HOME
        elif [ "$OS" = mac ] && home=$(/usr/libexec/java_home -v "$JAVA_MAJOR+" 2>/dev/null) &&
            java_ok "$home/bin/java"; then
            JAVA_PIN=$home
        elif java=$(command -v java) && { [ "$OS" != mac ] || [ "$java" != /usr/bin/java ]; } && java_ok "$java"; then
            case $java in
                */bin/java) JAVA_PIN=${java%/bin/java} ;;
                *) JAVA_PIN=$("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.home = //p') ;;
            esac
        fi
    fi
    if [ -z "$JAVA_PIN" ]; then
        download_jre
    else
        tick java_found "$(java_major "$JAVA_PIN/bin/java")" "$JAVA_PIN"
    fi
    printf '%s\n' "$JAVA_PIN" >"$TMP/java-home"
    mv -f "$TMP/java-home" "$ROOT/java-home"
    JAVA_HOME=$JAVA_PIN
    export JAVA_HOME
}

fetch_jre() {
    fetch "$1" "$TMP/jre.tar.gz"
    fetch "$1.sha256.txt" "$TMP/jre.sha256"
}

download_jre() {
    url="$ADOPTIUM/v3/binary/latest/$JAVA_MAJOR/ga/$OS/$ARCH/jre/hotspot/normal/eclipse"
    package=$(redirect_target "$url") || package=
    [ -n "$package" ] || die no_jre "$JAVA_MAJOR" "$OS" "$ARCH"
    info jre_needed "$JAVA_MAJOR" "$ROOT/jre"
    step "$(text jre_downloading)" "$(text jre_downloaded)" fetch_jre "$package" || die cannot_download "$package"
    verify "$TMP/jre.tar.gz" "$(cut -d ' ' -f 1 <"$TMP/jre.sha256")" ""
    mkdir "$TMP/jre"
    step "$(text jre_unpacking)" "$(text jre_unpacked)" tar -xzf "$TMP/jre.tar.gz" -C "$TMP/jre" ||
        die cannot_unpack_jre
    set -- "$TMP/jre"/*
    if [ $# -ne 1 ] || [ ! -d "$1" ]; then die jre_not_one; fi
    if [ -x "$1/Contents/Home/bin/java" ]; then
        home=/Contents/Home
    elif [ -x "$1/bin/java" ]; then
        home=
    else
        die jre_no_java
    fi
    java_ok "$1$home/bin/java" || die jre_broken
    rm -rf "$ROOT/jre.old"
    if [ -e "$ROOT/jre" ]; then mv "$ROOT/jre" "$ROOT/jre.old"; fi
    mv "$1" "$ROOT/jre"
    rm -rf "$ROOT/jre.old"
    JAVA_PIN=$ROOT/jre$home
    tick java_found "$JAVA_MAJOR" "$JAVA_PIN"
}

# The release whose assets this run installs: a pinned version, else the CI build, else the latest release.
release_tag() {
    if [ -n "$VERSION" ]; then
        printf 'v%s' "$VERSION"
    elif [ "$CHANNEL" = ci ]; then
        printf 'ci'
    else
        printf 'latest'
    fi
}

# Sets TAG, VERSION, ARCHIVE and ARCHIVE_SHA from the release's SHA256SUMS.
resolve_release() {
    SUMS_TAG=$(release_tag)
    TAG=$SUMS_TAG
    sums="$RELEASES/$SUMS_TAG/download/SHA256SUMS"
    fetch "$sums" "$TMP/SHA256SUMS" || die no_release "$sums"
    tr -d '\r' <"$TMP/SHA256SUMS" >"$TMP/sums"
    line=$(sed -n 's/^\([0-9a-fA-F]\{64\}\) [ *]\(alexandrite-[^ /]*\.tar\.gz\)$/\1 \2/p' "$TMP/sums" | head -n 1)
    [ -n "$line" ] || die no_archive "$sums"
    ARCHIVE=${line#* }
    ARCHIVE_SHA=${line%% *}
    found=${ARCHIVE#alexandrite-}
    found=${found%.tar.gz}
    if [ -n "$VERSION" ] && [ "$found" != "$VERSION" ]; then die wrong_archive "$VERSION" "$ARCHIVE"; fi
    VERSION=$found
    case $VERSION in
        '' | .* | *[!0-9A-Za-z.+_-]*) die no_version "$VERSION" ;;
    esac
    # The latest checksums come from `latest`, but a stable archive lives under its version's tag.
    if [ "$SUMS_TAG" != ci ]; then TAG=v$VERSION; fi
}

sums_hash() {
    sed -n "s/^\([0-9a-fA-F]\{64\}\) [ *]$1\$/\1/p" "$TMP/sums" | head -n 1
}

link_version() {
    if [ -L "$ROOT/$1" ]; then
        target=$(readlink "$ROOT/$1")
        printf '%s\n' "${target##*/}"
    fi
}

stage_release() {
    step "$(text downloading "$VERSION")" "$(text downloaded "$ARCHIVE")" \
        fetch "$RELEASES/$TAG/download/$ARCHIVE" "$TMP/$ARCHIVE" || die cannot_download "$ARCHIVE"
    verify "$TMP/$ARCHIVE" "$ARCHIVE_SHA" "$ARCHIVE"
    mkdir "$TMP/release"
    step "$(text unpacking "$ARCHIVE")" "$(text unpacked "$ARCHIVE")" tar -xzf "$TMP/$ARCHIVE" -C "$TMP/release" ||
        die cannot_unpack "$ARCHIVE"
    set -- "$TMP/release"/*
    if [ $# -ne 1 ] || [ ! -x "$1/bin/alexandrite" ]; then die no_binary "$ARCHIVE" "$VERSION"; fi
    rm -rf "$ROOT/versions/$VERSION"
    mv "$1" "$ROOT/versions/$VERSION"
    hash=$(sums_hash install.sh)
    if [ -n "$hash" ]; then
        fetch "$RELEASES/$TAG/download/install.sh" "$TMP/install.sh" || die cannot_download install.sh
        verify "$TMP/install.sh" "$hash" install.sh
    elif [ -f "$0" ] && [ "${0##*/}" = install.sh ]; then
        cp "$0" "$TMP/install.sh"
    fi
}

# Runs the app binary $1 with the arguments that follow and the locations given to this script.
app() {
    binary=$1
    shift
    if [ -n "$CONFIG_FILE" ]; then set -- "$@" --config "$CONFIG_FILE"; fi
    if [ -n "$DATA_DIR" ]; then set -- "$@" --data-dir "$DATA_DIR"; fi
    "$binary" "$@"
}

check_release() {
    new=$ROOT/versions/$VERSION/bin/alexandrite
    if ! out=$("$new" --version 2>&1); then
        warn does_not_start "$VERSION"
        printf '%s\n' "$out" >&2
        return 1
    fi
    case $out in
        *"alexandrite $VERSION"*) ;;
        *)
            warn names_itself "$VERSION" "$out"
            return 1
            ;;
    esac
    tick starts "$VERSION"
    [ -n "$PREV" ] || return 0
    if out=$(app "$new" config check 2>&1); then
        tick check_passes "$VERSION"
        return 0
    fi
    if ! app "$ROOT/versions/$PREV/bin/alexandrite" config check >/dev/null 2>&1; then
        warn both_fail "$VERSION" "$PREV"
        printf '%s\n' "$out" >&2
        return 0
    fi
    warn check_fails "$VERSION"
    printf '%s\n' "$out" >&2
    return 1
}

# Records the channel this installation follows for `alexandrite update` and its checks.
write_channel() {
    printf '%s\n' "$CHANNEL" >"$TMP/channel"
    mv -f "$TMP/channel" "$ROOT/channel"
}

write_launcher() {
    mkdir -p "$ROOT/bin"
    quoted=$(printf '%s' "$ROOT" | sed "s/'/'\\\\''/g")
    {
        printf '#!/bin/sh\n'
        printf "ALEXANDRITE_INSTALL_DIR='%s'\n" "$quoted"
        cat <<'EOF'
export ALEXANDRITE_INSTALL_DIR
if [ -z "${ALEXANDRITE_JAVA_HOME:-}" ] && [ -f "$ALEXANDRITE_INSTALL_DIR/java-home" ]; then
    ALEXANDRITE_JAVA_HOME=$(cat "$ALEXANDRITE_INSTALL_DIR/java-home")
fi
if [ -n "${ALEXANDRITE_JAVA_HOME:-}" ] && [ -x "$ALEXANDRITE_JAVA_HOME/bin/java" ]; then
    JAVA_HOME=$ALEXANDRITE_JAVA_HOME
    export JAVA_HOME ALEXANDRITE_JAVA_HOME
fi
exec "$ALEXANDRITE_INSTALL_DIR/current/bin/alexandrite" "$@"
EOF
    } >"$TMP/launcher"
    chmod 755 "$TMP/launcher"
    mv -f "$TMP/launcher" "$ROOT/bin/alexandrite"
}

# Appends the block that puts BIN_DIR on PATH to the startup file $1 of the shell kind $2 (sh or fish), once.
add_block() {
    if [ -f "$1" ] && grep -qF "$MARK_BEGIN" "$1"; then return 0; fi
    if [ "$2" = fish ]; then
        quoted=$(printf '%s' "$BIN_DIR" | sed 's/[\\"$]/\\&/g')
        line="if not contains -- \"$quoted\" \$PATH; set -gx PATH \"$quoted\" \$PATH; end"
    else
        quoted=$(printf '%s' "$BIN_DIR" | sed 's/[\\"$`]/\\&/g')
        line="case \":\${PATH}:\" in *\":$quoted:\"*) ;; *) export PATH=\"$quoted:\$PATH\" ;; esac"
    fi
    if ! {
        mkdir -p "$(dirname "$1")" &&
            if [ -s "$1" ] && [ -n "$(tail -c 1 "$1")" ]; then printf '\n' >>"$1"; fi &&
            printf '%s\n%s\n%s\n' "$MARK_BEGIN" "$line" "$MARK_END" >>"$1"
    } 2>/dev/null; then
        warn path_unwritable "$BIN_DIR" "$1"
        return 0
    fi
    if [ -n "$PATH_FILES" ]; then PATH_FILES="$PATH_FILES$(text separator)"; fi
    PATH_FILES="$PATH_FILES$(shown "$1")"
}

# Puts BIN_DIR on PATH in ~/.profile and the startup file of the user's shell; sets PATH_FILES to the files changed.
modify_path() {
    PATH_FILES=
    add_block "$HOME/.profile" sh
    case ${SHELL:-} in
        bash | */bash)
            add_block "$HOME/.bashrc" sh
            if [ -f "$HOME/.bash_profile" ]; then add_block "$HOME/.bash_profile" sh; fi
            ;;
        zsh | */zsh) add_block "${ZDOTDIR:-$HOME}/.zshrc" sh ;;
        fish | */fish) add_block "${XDG_CONFIG_HOME:-$HOME/.config}/fish/conf.d/alexandrite.fish" fish ;;
    esac
}

link_launcher() {
    mkdir -p "$BIN_DIR"
    LAUNCHER=$BIN_DIR/alexandrite
    ON_PATH=1
    if [ -e "$LAUNCHER" ] && [ ! -L "$LAUNCHER" ]; then
        warn own_file "$LAUNCHER" "$ROOT/bin/alexandrite"
        LAUNCHER=$ROOT/bin/alexandrite
        ON_PATH=0
        MODIFY_PATH=0
        return 0
    fi
    ln -sfn "$ROOT/bin/alexandrite" "$LAUNCHER"
    tick linked "$(shown "$LAUNCHER")"
    case ":$PATH:" in
        *":$BIN_DIR:"*) return 0 ;;
    esac
    ON_PATH=0
    if [ "$MODIFY_PATH" = 0 ]; then
        say not_on_path "$BIN_DIR" "$BIN_DIR"
        return 0
    fi
    modify_path
    if [ -n "$PATH_FILES" ]; then ok path_added "$BIN_DIR" "$PATH_FILES"; else info path_present "$BIN_DIR"; fi
}

# Sets SERVICE to the background service that runs now, empty when none does.
detect_service() {
    SERVICE=
    PLIST=$HOME/Library/LaunchAgents/$LAUNCHD_LABEL.plist
    if [ "$OS" = mac ]; then
        if [ -f "$PLIST" ] && have launchctl && launchctl print "gui/$(id -u)/$LAUNCHD_LABEL" >/dev/null 2>&1; then
            SERVICE=launchd
        fi
    elif have systemctl && systemctl --user is-active --quiet "$SYSTEMD_UNIT" 2>/dev/null; then
        SERVICE=systemd
    fi
}

restart_service() {
    case $SERVICE in
        systemd) systemctl --user restart "$SYSTEMD_UNIT" ;;
        launchd)
            domain=gui/$(id -u)
            launchctl bootout "$domain/$LAUNCHD_LABEL" 2>/dev/null || true
            tries=0
            until launchctl bootstrap "$domain" "$PLIST" 2>/dev/null; do
                tries=$((tries + 1))
                [ "$tries" -lt 10 ] || return 1
                sleep 1
            done
            ;;
    esac
}

# Polls `alexandrite status` until the instance answers.
wait_ready() {
    waited=0
    while [ "$waited" -lt "$READY_TIMEOUT" ]; do
        if app "$ROOT/bin/alexandrite" status >/dev/null 2>&1; then return 0; fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

switch_to() {
    ln -sfn "versions/$1" "$ROOT/current"
    if [ -n "$2" ]; then ln -sfn "versions/$2" "$ROOT/previous"; else rm -f "$ROOT/previous"; fi
}

# Switches back to PREV after the failure the text key $1 names with the arguments that follow.
rollback() {
    warn "$@"
    if [ "$OLD_PREVIOUS" = "$VERSION" ]; then OLD_PREVIOUS=; fi
    switch_to "$PREV" "$OLD_PREVIOUS"
    if [ -n "$SERVICE" ]; then
        if ! { restart_service && wait_ready; }; then warn not_back "$PREV"; fi
    fi
    rm -rf "${ROOT:?}/versions/$VERSION"
    die switched_back "$PREV"
}

prune() {
    keep_current=$(link_version current)
    keep_previous=$(link_version previous)
    for directory in "$ROOT/versions"/*; do
        [ -d "$directory" ] || continue
        name=${directory##*/}
        if [ "$name" != "$keep_current" ] && [ "$name" != "$keep_previous" ]; then rm -rf "$directory"; fi
    done
}

cleanup() {
    printf '%s' "$SHOW"
    rm -rf "$TMP"
    rmdir "$ROOT/.lock" 2>/dev/null || true
}

summary() {
    if [ "$FANCY" = 0 ]; then
        if [ -n "$PREV" ]; then say installed_was "$VERSION" "$PREV"; else say installed_at "$VERSION" "$LAUNCHER"; fi
        return 0
    fi
    printf '\n'
    ok installed "$VERSION"
    if [ -n "$PREV" ]; then kv key_version "$(text version_was "$VERSION" "$PREV")"; else kv key_version "$VERSION"; fi
    kv key_location "$ROOT"
    kv key_command "$LAUNCHER"
    kv key_java "$JAVA_PIN"
    if [ "$ON_PATH" = 1 ]; then
        point run alexandrite
    elif [ "$MODIFY_PATH" = 1 ]; then
        case ${SHELL:-} in
            fish | */fish) point reload "set -gx PATH \"$BIN_DIR\" \$PATH" ;;
            *) point reload "export PATH=\"$BIN_DIR:\$PATH\"" ;;
        esac
        point then_run
    else
        point run "$LAUNCHER"
    fi
}

# Runs `alexandrite init` on the terminal when no config file exists yet.
maybe_init() {
    [ "$NO_INIT" = 0 ] || return 0
    if config=$(app "$LAUNCHER" config path 2>/dev/null) && [ -n "$config" ]; then
        if [ -e "$config" ]; then return 0; fi
    elif [ -n "$PREV" ]; then
        return 0
    fi
    if (: <"$TTY") 2>/dev/null; then
        printf '\n'
        point starting_setup
        app "$LAUNCHER" init <"$TTY" || true
    else
        say setup_skipped
    fi
}

main() {
    VERSION=
    PINNED=0
    NO_INIT=0
    MODIFY_PATH=1
    UPDATE=0
    DOWNLOAD_JRE=0
    CONFIG_FILE=
    DATA_DIR=
    while [ $# -gt 0 ]; do
        case $1 in
            --version | --channel | --config | --data-dir)
                [ $# -ge 2 ] || die needs_value "$1"
                case $1 in
                    --version) VERSION=$2 ;;
                    --channel) CHANNEL=$2 ;;
                    --config) CONFIG_FILE=$2 ;;
                    *) DATA_DIR=$2 ;;
                esac
                shift 2
                ;;
            --version=*) VERSION=${1#*=} && shift ;;
            --channel=*) CHANNEL=${1#*=} && shift ;;
            --config=*) CONFIG_FILE=${1#*=} && shift ;;
            --data-dir=*) DATA_DIR=${1#*=} && shift ;;
            --no-init) NO_INIT=1 && shift ;;
            --no-modify-path) MODIFY_PATH=0 && shift ;;
            --update) UPDATE=1 && NO_INIT=1 && MODIFY_PATH=0 && shift ;;
            --download-jre) DOWNLOAD_JRE=1 && shift ;;
            -h | --help) usage && exit 0 ;;
            *) usage >&2 && die unknown_option "$1" ;;
        esac
    done
    VERSION=${VERSION#v}
    if [ -n "$VERSION" ]; then PINNED=1; fi
    CHANNEL=$(printf '%s' "$CHANNEL" | tr '[:upper:]' '[:lower:]')
    case $CHANNEL in
        stable | ci) ;;
        *) usage >&2 && die no_channel "$CHANNEL" ;;
    esac
    case $ROOT in
        /*) ;;
        *) die relative_root ;;
    esac
    stage 1 stage_environment
    detect_platform
    mkdir -p "$ROOT/versions"
    mkdir "$ROOT/.lock" 2>/dev/null || die locked "$ROOT" "$ROOT/.lock"
    TMP=$ROOT/.tmp.$$
    trap 'code=$?; cleanup; exit "$code"' EXIT
    trap 'exit 1' INT TERM HUP
    mkdir "$TMP"

    PREV=$(link_version current)
    OLD_PREVIOUS=$(link_version previous)
    PREV_CHANNEL=stable
    if [ -f "$ROOT/channel" ]; then PREV_CHANNEL=$(cat "$ROOT/channel" 2>/dev/null || printf stable); fi
    if [ "$UPDATE" = 1 ] && [ -z "$PREV" ]; then die not_installed "$ROOT"; fi
    resolve_release
    show_environment
    if [ "$VERSION" = "$PREV" ] && [ -x "$ROOT/versions/$PREV/bin/alexandrite" ]; then
        stage 2 stage_java
        ensure_java
        stage 4 stage_install
        write_launcher
        link_launcher
        write_channel
        ok installed_already "$VERSION"
        cleanup
        trap - EXIT
        maybe_init
        return 0
    fi
    if [ "$UPDATE" = 1 ] && [ "$PINNED" = 0 ] && [ "$CHANNEL" = "$PREV_CHANNEL" ] &&
        version_lt "$VERSION" "$PREV"; then
        info newer "$PREV" "$VERSION"
        return 0
    fi
    stage 2 stage_java
    ensure_java
    stage 3 stage_download
    stage_release
    if ! check_release; then
        rm -rf "${ROOT:?}/versions/$VERSION"
        if [ -n "$PREV" ]; then die not_installed_stays "$VERSION" "$PREV"; fi
        die not_installed_new "$VERSION"
    fi
    stage 4 stage_install
    write_launcher
    SERVICE=
    if [ -n "$PREV" ]; then detect_service; fi
    switch_to "$VERSION" "$PREV"
    tick switched "$VERSION"
    if [ -n "$PREV" ]; then
        if [ -n "$SERVICE" ]; then
            info restarting "$VERSION"
            restart_service || rollback no_restart "$VERSION"
            wait_ready || rollback no_answer "$VERSION" "$READY_TIMEOUT"
            tick restarted "$VERSION"
        else
            info no_service "$VERSION"
        fi
        prune
    fi
    if [ -f "$TMP/install.sh" ]; then
        chmod 755 "$TMP/install.sh"
        mv -f "$TMP/install.sh" "$ROOT/install.sh"
    fi
    link_launcher
    write_channel
    summary
    cleanup
    trap - EXIT
    maybe_init
}

main "$@"
