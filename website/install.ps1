# Installs, upgrades or updates Alexandrite for the current user on Windows:
#   irm https://alexandrite.unitedsoftware.org/install.ps1 | iex
#   & ([scriptblock]::Create((irm https://alexandrite.unitedsoftware.org/install.ps1))) -Version 0.2.0 -NoInit
param(
    [string]$Version = '',
    [string]$Channel = 'stable',
    [switch]$NoInit,
    [switch]$NoModifyPath,
    [switch]$Update,
    [switch]$DownloadJre,
    [string]$Config = '',
    [string]$DataDir = '',
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Releases = if ($env:ALEXANDRITE_INSTALL_BASE) { $env:ALEXANDRITE_INSTALL_BASE } else { 'https://github.com/FoedusProgramme/alexandrite/releases' }
$Adoptium = if ($env:ALEXANDRITE_ADOPTIUM_BASE) { $env:ALEXANDRITE_ADOPTIUM_BASE } else { 'https://api.adoptium.net' }
$Root = if ($env:ALEXANDRITE_INSTALL_DIR) { $env:ALEXANDRITE_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\Alexandrite' }
$ReadyTimeout = if ($env:ALEXANDRITE_READY_TIMEOUT) { [int]$env:ALEXANDRITE_READY_TIMEOUT } else { 90 }
$TaskName = 'Alexandrite'
$JavaMajor = 21
$Stages = 4
$Chinese = [Globalization.CultureInfo]::CurrentUICulture.Name -like 'zh*'

# The English texts by key, as format strings whose {0}, {1} … take the arguments of Msg.
$TextsEn = @{
    usage = @'
Usage: install.ps1 [-Version <v>] [-Channel <stable|ci>] [-NoInit] [-NoModifyPath] [-Update] [-DownloadJre] [-Config <file>] [-DataDir <dir>]

Installs Alexandrite into {0} and adds its bin directory to your PATH.
Running it again upgrades in place; two versions are kept.
When no config file exists yet, it then runs 'alexandrite init' in this console.

  -Version <v>     install release <v> instead of the latest
  -Channel <c>     follow stable releases (the default) or CI builds of master
  -NoInit          do not run 'alexandrite init' after installing
  -NoModifyPath    do not add the bin directory to your PATH
  -Update          update an existing installation (what 'alexandrite update' runs)
  -DownloadJre     use a Java runtime of Alexandrite's own even when the system has Java {1}+
  -Config <file>   the config file the new version is checked against
  -DataDir <dir>   the data directory of the instance to wait for

ALEXANDRITE_INSTALL_DIR changes the directory.
ALEXANDRITE_INSTALL_BASE and ALEXANDRITE_ADOPTIUM_BASE replace the download sites (file:// URLs are for testing).
'@
    stage_environment = 'Environment'
    stage_java = 'Java'
    stage_download = 'Download and verify'
    stage_install = 'Install'
    doing = '{0} ...'
    detected = 'Detected: windows {0}'
    key_os = 'OS:'
    key_arch = 'Architecture:'
    key_root = 'Install directory:'
    key_command = 'Command:'
    key_installed = 'Installed version:'
    key_release = 'Release:'
    key_latest = 'Latest release:'
    key_version = 'Version:'
    key_location = 'Location:'
    key_java = 'Java:'
    none = 'none'
    version_was = '{0} (was {1})'
    only_windows = 'install.ps1 installs on Windows; on Linux and macOS run install.sh.'
    refused_url = 'Refusing to download {0}: only https:// and file:// URLs are used.'
    checksum_mismatch = 'The checksum of {0} does not match ({1}, expected {2}); nothing was changed.'
    checksum_ok = 'The checksum of {0} matches.'
    jre_checksum_mismatch = 'The checksum of the Java runtime does not match ({0}, expected {1}); nothing was changed.'
    jre_checksum_ok = 'The checksum of the Java runtime matches.'
    java_found = 'Java {0}: {1}'
    no_jre = 'Adoptium names no Java {0} runtime for Windows x64.'
    jre_needed = 'No Java {0}+ found; downloading a Java runtime from Adoptium into {1}.'
    the_jre = 'the Java runtime'
    jre_downloading = 'Downloading the Java runtime'
    jre_downloaded = 'Downloaded the Java runtime'
    jre_unpacking = 'Unpacking the Java runtime'
    jre_unpacked = 'Unpacked the Java runtime'
    cannot_download = 'Cannot download {0}.'
    cannot_unpack = 'Cannot unpack {0}.'
    jre_no_java = 'The Java runtime archive holds no bin\java.exe.'
    jre_broken = 'The downloaded Java runtime does not run.'
    no_release = 'Cannot download {0}: no such release?'
    no_archive = '{0} lists no alexandrite-<version>.zip.'
    wrong_archive = 'Release {0} holds {1}.'
    no_version = "'{0}' is no version."
    downloading = 'Downloading Alexandrite {0}'
    downloaded = 'Downloaded {0}'
    unpacking = 'Unpacking {0}'
    unpacked = 'Unpacked {0}'
    no_binary = '{0} holds no alexandrite-{1}\bin\alexandrite.bat.'
    does_not_start = 'Alexandrite {0} does not start:'
    names_itself = "Alexandrite {0} names itself '{1}'."
    starts = 'Alexandrite {0} starts.'
    check_passes = 'The config passes the check of Alexandrite {0}.'
    both_fail = 'The config fails the check of {0} and of the installed {1} alike; going on:'
    check_fails = 'The config fails the check of Alexandrite {0}:'
    path_added = "Added {0} to your PATH; open a new terminal to use 'alexandrite'."
    not_on_path = "{0} is not on your PATH: add it to use 'alexandrite' by name."
    not_installed = 'Alexandrite is not installed in {0}; run install.ps1 without -Update.'
    locked = 'Another install runs in {0} (remove {1} if none does).'
    installed_already = 'Alexandrite {0} is installed already.'
    newer = 'Alexandrite {0} is newer than the latest release {1}; nothing to do.'
    not_installed_stays = 'Alexandrite {0} was not installed; {1} stays.'
    not_installed_new = 'Alexandrite {0} was not installed.'
    switched = 'Switched to Alexandrite {0}.'
    restarting = 'Restarting the background service with Alexandrite {0} ...'
    no_restart = 'The background service did not restart with Alexandrite {0}: {1}'
    no_answer = 'Alexandrite {0} did not answer within {1} seconds.'
    restarted = 'The background service runs Alexandrite {0}.'
    no_service = 'No background service runs; the next start runs Alexandrite {0}.'
    not_back = 'Alexandrite {0} did not come back either; see its logs.'
    switched_back = 'Switched back to Alexandrite {0}.'
    installed_was = 'Alexandrite {0} is installed (was {1}).'
    installed_at = 'Alexandrite {0} is installed: {1}'
    installed = 'Alexandrite {0} is installed.'
    run = 'Run: {0}'
    reload = 'Open a new terminal, then run: alexandrite'
    starting_setup = 'Starting setup: alexandrite init'
    setup_skipped = "Setup skipped (no terminal); run 'alexandrite init' after install."
}

$TextsZh = @{
    usage = @'
用法：install.ps1 [-Version <版本>] [-Channel <stable|ci>] [-NoInit] [-NoModifyPath] [-Update] [-DownloadJre] [-Config <文件>] [-DataDir <目录>]

把 Alexandrite 安装到 {0}，并把其 bin 目录加入 PATH。
再次运行即原地升级；保留两个版本。
如果还没有配置文件，随后在此控制台中运行 'alexandrite init'。

  -Version <版本>   安装指定版本，而不是最新版本
  -Channel <频道>   跟随稳定发布（默认）或 master 的 CI 构建
  -NoInit           安装后不运行 'alexandrite init'
  -NoModifyPath     不把 bin 目录加入 PATH
  -Update           更新已有的安装（'alexandrite update' 运行的就是它）
  -DownloadJre      即使系统已有 Java {1}+，也使用 Alexandrite 自带的 Java 运行时
  -Config <文件>    检查新版本时使用的配置文件
  -DataDir <目录>   要等待其就绪的实例的数据目录

ALEXANDRITE_INSTALL_DIR 可改变安装目录。
ALEXANDRITE_INSTALL_BASE 和 ALEXANDRITE_ADOPTIUM_BASE 可替换下载地址（file:// 地址用于测试）。
'@
    stage_environment = '环境'
    stage_download = '下载并校验'
    stage_install = '安装'
    doing = '{0}……'
    detected = '已检测：windows {0}'
    key_os = '系统：'
    key_arch = '架构：'
    key_root = '安装目录：'
    key_command = '命令：'
    key_installed = '已安装版本：'
    key_release = '发布版本：'
    key_latest = '最新版本：'
    key_version = '版本：'
    key_location = '位置：'
    key_java = '运行时：'
    none = '无'
    version_was = '{0}（原为 {1}）'
    only_windows = 'install.ps1 只能在 Windows 上安装；在 Linux 和 macOS 上请运行 install.sh。'
    refused_url = '拒绝下载 {0}：只使用 https:// 和 file:// 地址。'
    checksum_mismatch = '{0} 的校验和不符（{1}，应为 {2}）；未做任何更改。'
    checksum_ok = '{0} 的校验和相符。'
    jre_checksum_mismatch = 'Java 运行时的校验和不符（{0}，应为 {1}）；未做任何更改。'
    jre_checksum_ok = 'Java 运行时的校验和相符。'
    java_found = 'Java {0}：{1}'
    no_jre = 'Adoptium 没有提供适用于 Windows x64 的 Java {0} 运行时。'
    jre_needed = '未找到 Java {0}+；从 Adoptium 下载 Java 运行时到 {1}。'
    the_jre = 'Java 运行时'
    jre_downloading = '正在下载 Java 运行时'
    jre_downloaded = '已下载 Java 运行时'
    jre_unpacking = '正在解压 Java 运行时'
    jre_unpacked = '已解压 Java 运行时'
    cannot_download = '无法下载 {0}。'
    cannot_unpack = '无法解压 {0}。'
    jre_no_java = 'Java 运行时压缩包中没有 bin\java.exe。'
    jre_broken = '下载的 Java 运行时无法运行。'
    no_release = '无法下载 {0}：该版本不存在？'
    no_archive = '{0} 中没有 alexandrite-<版本>.zip。'
    wrong_archive = '版本 {0} 中的压缩包是 {1}。'
    no_version = "'{0}' 不是版本号。"
    downloading = '正在下载 Alexandrite {0}'
    downloaded = '已下载 {0}'
    unpacking = '正在解压 {0}'
    unpacked = '已解压 {0}'
    no_binary = '{0} 中没有 alexandrite-{1}\bin\alexandrite.bat。'
    does_not_start = 'Alexandrite {0} 无法启动：'
    names_itself = "Alexandrite {0} 报告的版本是 '{1}'。"
    starts = 'Alexandrite {0} 可以启动。'
    check_passes = '配置通过了 Alexandrite {0} 的检查。'
    both_fail = '配置在 {0} 和已安装的 {1} 上都未通过检查；继续：'
    check_fails = '配置未通过 Alexandrite {0} 的检查：'
    path_added = "已把 {0} 加入 PATH；请打开新的终端以使用 'alexandrite'。"
    not_on_path = "{0} 不在 PATH 中：加入后即可直接使用 'alexandrite'。"
    not_installed = '{0} 中没有安装 Alexandrite；请不带 -Update 运行 install.ps1。'
    locked = '{0} 中正在进行另一次安装（如果没有，请删除 {1}）。'
    installed_already = 'Alexandrite {0} 已经安装。'
    newer = 'Alexandrite {0} 比最新发布的 {1} 更新；无需操作。'
    not_installed_stays = '未安装 Alexandrite {0}；继续使用 {1}。'
    not_installed_new = '未安装 Alexandrite {0}。'
    switched = '已切换到 Alexandrite {0}。'
    restarting = '正在用 Alexandrite {0} 重启后台服务……'
    no_restart = '后台服务未能用 Alexandrite {0} 重启：{1}'
    no_answer = 'Alexandrite {0} 在 {1} 秒内没有响应。'
    restarted = '后台服务正在运行 Alexandrite {0}。'
    no_service = '没有正在运行的后台服务；下次启动时将运行 Alexandrite {0}。'
    not_back = 'Alexandrite {0} 也未能恢复运行；请查看日志。'
    switched_back = '已切换回 Alexandrite {0}。'
    installed_was = '已安装 Alexandrite {0}（原为 {1}）。'
    installed_at = '已安装 Alexandrite {0}：{1}'
    installed = 'Alexandrite {0} 已安装。'
    run = '运行：{0}'
    reload = '请打开新的终端，然后运行：alexandrite'
    starting_setup = '开始设置：alexandrite init'
    setup_skipped = "已跳过设置（没有终端）；安装后请运行 'alexandrite init'。"
}

# The text of $Key in the chosen language, English where it has none, with its placeholders filled from the rest.
function Msg([string]$Key) {
    $format = $null
    if ($Chinese) { $format = $TextsZh[$Key] }
    if (-not $format) { $format = $TextsEn[$Key] }
    if ($args.Count -gt 0) { $format -f $args } else { $format }
}

# Stages, glyphs and the status line only on a console; colours also need NO_COLOR unset.
$Fancy = -not [Console]::IsOutputRedirected -and $env:TERM -ne 'dumb'
$FancyErr = -not [Console]::IsErrorRedirected -and $env:TERM -ne 'dumb'
$Colors = -not $env:NO_COLOR

function Write-Painted([string]$Text, [string]$Color, [switch]$NoNewline) {
    if ($Colors -and $Color) {
        Write-Host $Text -ForegroundColor $Color -NoNewline:$NoNewline
    } else {
        Write-Host $Text -NoNewline:$NoNewline
    }
}

# Writes $Text led by the glyph $Glyph in $Color on a console, the bare text otherwise.
function Write-Mark([string]$Glyph, [string]$Color, [string]$Text) {
    if ($Fancy) {
        Write-Host '  ' -NoNewline
        Write-Painted $Glyph $Color -NoNewline
        Write-Host " $Text"
    } else {
        Write-Host $Text
    }
}

function Say([string]$Key) {
    Write-Host (Msg $Key @args)
}

function Ok([string]$Key) {
    Write-Mark ([string][char]0x2713) 'Green' (Msg $Key @args)
}

function Info([string]$Key) {
    Write-Mark ([string][char]0x00B7) 'DarkGray' (Msg $Key @args)
}

function Point([string]$Key) {
    Write-Mark ([string][char]0x2192) 'Cyan' (Msg $Key @args)
}

# An outcome worth a line on a console only.
function Tick([string]$Key) {
    if ($Fancy) { Ok $Key @args }
}

function Warn([string]$Key) {
    $text = Msg $Key @args
    if ($FancyErr) { $text = "  ! $text" }
    [Console]::Error.WriteLine($text)
}

function Fail([string]$Key) {
    throw (Msg $Key @args)
}

# The columns $Text takes in a console, wide East Asian characters taking two.
function Get-Columns([string]$Text) {
    $columns = 0
    foreach ($c in $Text.ToCharArray()) {
        $code = [int]$c
        $wide = ($code -ge 0x1100 -and $code -le 0x115F) -or ($code -ge 0x2E80 -and $code -le 0xA4CF) -or
            ($code -ge 0xAC00 -and $code -le 0xD7A3) -or ($code -ge 0xF900 -and $code -le 0xFAFF) -or
            ($code -ge 0xFE30 -and $code -le 0xFE4F) -or ($code -ge 0xFF00 -and $code -le 0xFF60) -or
            ($code -ge 0xFFE0 -and $code -le 0xFFE6)
        if ($wide) { $columns += 2 } else { $columns += 1 }
    }
    $columns
}

function Get-Padded([string]$Text, [int]$Width) {
    $Text + (' ' * [Math]::Max(1, $Width - (Get-Columns $Text)))
}

function Show-Stage([int]$Number, [string]$Key) {
    if (-not $Fancy) { return }
    Write-Host ''
    Write-Painted "[$Number/$Stages] $(Msg $Key)" 'White'
}

function Show-Value([string]$Key, [string]$Value) {
    if (-not $Fancy) { return }
    Write-Host '    ' -NoNewline
    Write-Painted (Get-Padded (Msg $Key) 20) 'DarkGray' -NoNewline
    Write-Host $Value
}

function Get-Width {
    try { [Math]::Max(20, $Host.UI.RawUI.WindowSize.Width - 1) } catch { 79 }
}

# Runs $Action behind a status line $Doing that becomes $Done; shows the error's lines when it fails and rethrows.
function Invoke-Step([string]$Doing, [string]$Done, [scriptblock]$Action) {
    if (-not $Fancy) {
        Write-Host (Msg 'doing' $Doing)
    } else {
        Write-Host '  ' -NoNewline
        Write-Painted ([string][char]0x25D2) 'Cyan' -NoNewline
        Write-Host " $Doing" -NoNewline
    }
    try {
        & $Action | Out-Null
    } catch {
        $lines = @("$($_.Exception.Message)" -split "`r?`n" | Where-Object { $_ } | Select-Object -Last 10)
        if ($Fancy) {
            Write-Host ("`r" + (' ' * (Get-Width)) + "`r") -NoNewline
            Write-Mark ([string][char]0x2717) 'Red' $Doing
            foreach ($line in $lines) { [Console]::Error.WriteLine("    $([char]0x2502) $line") }
        } else {
            foreach ($line in $lines) { [Console]::Error.WriteLine("  | $line") }
        }
        throw
    }
    if ($Fancy) {
        Write-Host ("`r" + (' ' * (Get-Width)) + "`r") -NoNewline
        Write-Mark ([string][char]0x2713) 'Green' $Done
    }
}

function Show-Usage {
    Say 'usage' $Root $JavaMajor
}

function Get-Url([string]$Url, [string]$Destination) {
    if ($Url.StartsWith('file://')) {
        Copy-Item -LiteralPath ([Uri]$Url).LocalPath -Destination $Destination
    } elseif ($Url.StartsWith('https://')) {
        Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing
    } else {
        Fail 'refused_url' $Url
    }
}

# The location $Url redirects to, $Url itself for a file:// URL.
function Get-RedirectTarget([string]$Url) {
    if ($Url.StartsWith('file://')) { return $Url }
    $request = [Net.HttpWebRequest]::Create($Url)
    $request.Method = 'HEAD'
    $request.AllowAutoRedirect = $false
    try {
        $response = $request.GetResponse()
    } catch [Net.WebException] {
        $response = $_.Exception.Response
        if (-not $response) { throw }
    }
    try { $response.Headers['Location'] } finally { $response.Close() }
}

# Checks $File against $Expected, naming it $Label, or the Java runtime when $Label is empty.
function Assert-Checksum([string]$File, [string]$Expected, [string]$Label) {
    $actual = (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant()
    $expected = $Expected.Trim().ToLowerInvariant()
    if (-not $expected -or $actual -ne $expected) {
        if ($Label) { Fail 'checksum_mismatch' $Label $actual $expected }
        Fail 'jre_checksum_mismatch' $actual $expected
    }
    if ($Label) { Tick 'checksum_ok' $Label } else { Tick 'jre_checksum_ok' }
}

# Whether version $A sorts before $B, a pre-release (`1.0.0-rc1`) before its release.
function Test-VersionBefore([string]$A, [string]$B) {
    $coreA = $A.Split('-')[0].Split('.')
    $coreB = $B.Split('-')[0].Split('.')
    for ($i = 0; $i -lt [Math]::Max($coreA.Length, $coreB.Length); $i++) {
        $x = if ($i -lt $coreA.Length) { [int]($coreA[$i] -replace '\D.*$', '') } else { 0 }
        $y = if ($i -lt $coreB.Length) { [int]($coreB[$i] -replace '\D.*$', '') } else { 0 }
        if ($x -lt $y) { return $true }
        if ($x -gt $y) { return $false }
    }
    return $A.Contains('-') -and -not $B.Contains('-')
}

# Runs $Binary with $Arguments, then the locations given to this script when $Locations is set.
function Invoke-App([string]$Binary, [string[]]$Arguments, [switch]$Locations) {
    $all = @($Arguments)
    if ($Locations -and $Config) { $all += @('--config', $Config) }
    if ($Locations -and $DataDir) { $all += @('--data-dir', $DataDir) }
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Binary @all 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    [pscustomobject]@{ Code = $code; Output = ($output -join [Environment]::NewLine) }
}

function Get-JavaMajor([string]$Java) {
    if (-not (Test-Path -LiteralPath $Java -PathType Leaf)) { return 0 }
    $result = Invoke-App $Java @('-version')
    if ($result.Output -match 'version "([^"]*)"') {
        $v = $Matches[1]
        if ($v.StartsWith('1.')) { $v = $v.Substring(2) }
        if ($v -match '^(\d+)') { return [int]$Matches[1] }
    }
    return 0
}

# Downloads a Java runtime into jre when neither JAVA_HOME nor PATH offers Java 21+; the launcher prefers jre.
function Initialize-Java {
    $jre = Join-Path $Root 'jre'
    $script:JavaUsed = ''
    if (-not $DownloadJre) {
        if (Test-Path -LiteralPath (Join-Path $jre 'bin\java.exe')) {
            $major = Get-JavaMajor (Join-Path $jre 'bin\java.exe')
            if ($major -ge $JavaMajor) {
                $env:JAVA_HOME = $jre
                $script:JavaUsed = $jre
                Tick 'java_found' $major $jre
                return
            }
        } elseif ($env:JAVA_HOME) {
            $major = Get-JavaMajor (Join-Path $env:JAVA_HOME 'bin\java.exe')
            if ($major -ge $JavaMajor) {
                $script:JavaUsed = $env:JAVA_HOME
                Tick 'java_found' $major $env:JAVA_HOME
                return
            }
        } else {
            $java = Get-Command java.exe -ErrorAction SilentlyContinue
            if ($java) {
                $major = Get-JavaMajor $java.Source
                if ($major -ge $JavaMajor) {
                    $script:JavaUsed = $java.Source
                    Tick 'java_found' $major $java.Source
                    return
                }
            }
        }
    }
    $url = "$Adoptium/v3/binary/latest/$JavaMajor/ga/windows/x64/jre/hotspot/normal/eclipse"
    $package = Get-RedirectTarget $url
    if (-not $package) { Fail 'no_jre' $JavaMajor }
    Info 'jre_needed' $JavaMajor "$Root\jre"
    $zip = Join-Path $Tmp 'jre.zip'
    try {
        Invoke-Step (Msg 'jre_downloading') (Msg 'jre_downloaded') {
            Get-Url $package $zip
            Get-Url "$package.sha256.txt" (Join-Path $Tmp 'jre.sha256')
        }
    } catch {
        Fail 'cannot_download' $package
    }
    Assert-Checksum $zip ((Get-Content -LiteralPath (Join-Path $Tmp 'jre.sha256') -Raw).Trim().Split(' ')[0]) ''
    $unpacked = Join-Path $Tmp 'jre'
    try {
        Invoke-Step (Msg 'jre_unpacking') (Msg 'jre_unpacked') { Expand-Archive -LiteralPath $zip -DestinationPath $unpacked }
    } catch {
        Fail 'cannot_unpack' (Msg 'the_jre')
    }
    $top = @(Get-ChildItem -LiteralPath $unpacked)
    if ($top.Count -ne 1 -or -not (Test-Path -LiteralPath (Join-Path $top[0].FullName 'bin\java.exe'))) { Fail 'jre_no_java' }
    if ((Get-JavaMajor (Join-Path $top[0].FullName 'bin\java.exe')) -lt $JavaMajor) { Fail 'jre_broken' }
    $old = Join-Path $Root 'jre.old'
    if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force }
    if (Test-Path -LiteralPath (Join-Path $Root 'jre')) { Move-Item -LiteralPath (Join-Path $Root 'jre') $old }
    Move-Item -LiteralPath $top[0].FullName (Join-Path $Root 'jre')
    if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force }
    $env:JAVA_HOME = $jre
    $script:JavaUsed = $jre
    Tick 'java_found' $JavaMajor $jre
}

function Get-Pointer([string]$Name) {
    $file = Join-Path $Root "$Name.txt"
    if (Test-Path -LiteralPath $file) { (Get-Content -LiteralPath $file -Raw).Trim() } else { '' }
}

function Set-Pointer([string]$Name, [string]$Value) {
    $file = Join-Path $Root "$Name.txt"
    if (-not $Value) {
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
        return
    }
    $next = "$file.new"
    [IO.File]::WriteAllText($next, $Value)
    Move-Item -LiteralPath $next $file -Force
}

function Switch-To([string]$Current, [string]$Previous) {
    Set-Pointer 'current' $Current
    Set-Pointer 'previous' $Previous
}

# The release whose assets this run installs: a pinned version, else the CI build, else the latest release.
function Get-ReleaseTag {
    if ($script:Version) { "v$script:Version" } elseif ($script:Channel -eq 'ci') { 'ci' } else { 'latest' }
}

# Sets $script:Tag, $script:Version, $script:Archive and $script:ArchiveSha from the release's SHA256SUMS.
function Resolve-Release {
    $script:Tag = Get-ReleaseTag
    $sums = "$Releases/$script:Tag/download/SHA256SUMS"
    $file = Join-Path $Tmp 'SHA256SUMS'
    try { Get-Url $sums $file } catch {
        Fail 'no_release' $sums
    }
    $script:Sums = @(Get-Content -LiteralPath $file | ForEach-Object { $_.TrimEnd("`r") })
    $line = $script:Sums | Where-Object { $_ -match '^[0-9a-fA-F]{64} [ *]alexandrite-[^ /]*\.zip$' } | Select-Object -First 1
    if (-not $line) { Fail 'no_archive' $sums }
    $script:ArchiveSha = $line.Substring(0, 64)
    $script:Archive = $line.Substring(66)
    $found = $script:Archive.Substring('alexandrite-'.Length, $script:Archive.Length - 'alexandrite-'.Length - '.zip'.Length)
    if ($script:Version -and $found -ne $script:Version) { Fail 'wrong_archive' $script:Version $script:Archive }
    if ($found -notmatch '^[0-9A-Za-z][0-9A-Za-z.+_-]*$') { Fail 'no_version' $found }
    $script:Version = $found
}

function Get-SumsHash([string]$Name) {
    $line = $script:Sums | Where-Object { $_ -match "^[0-9a-fA-F]{64} [ *]$([regex]::Escape($Name))$" } | Select-Object -First 1
    if ($line) { $line.Substring(0, 64) } else { '' }
}

function Show-Environment {
    if (-not $Fancy) { return }
    $os = $(try { (Get-CimInstance Win32_OperatingSystem).Caption } catch { [Environment]::OSVersion.VersionString })
    Ok 'detected' 'x64'
    Show-Value 'key_os' $os
    Show-Value 'key_arch' $env:PROCESSOR_ARCHITECTURE
    Show-Value 'key_root' $Root
    Show-Value 'key_command' "$Root\bin\alexandrite.cmd"
    Show-Value 'key_installed' $(if ($Prev) { $Prev } else { Msg 'none' })
    Show-Value 'key_channel' $script:Channel
    if ($Pinned) { Show-Value 'key_release' $Version } else { Show-Value 'key_latest' $Version }
}

function Install-Release {
    $zip = Join-Path $Tmp $Archive
    try {
        Invoke-Step (Msg 'downloading' $Version) (Msg 'downloaded' $Archive) {
            Get-Url "$Releases/$script:Tag/download/$Archive" $zip
        }
    } catch {
        Fail 'cannot_download' $Archive
    }
    Assert-Checksum $zip $ArchiveSha $Archive
    $unpacked = Join-Path $Tmp 'release'
    try {
        Invoke-Step (Msg 'unpacking' $Archive) (Msg 'unpacked' $Archive) {
            Expand-Archive -LiteralPath $zip -DestinationPath $unpacked
        }
    } catch {
        Fail 'cannot_unpack' $Archive
    }
    $top = @(Get-ChildItem -LiteralPath $unpacked)
    if ($top.Count -ne 1 -or -not (Test-Path -LiteralPath (Join-Path $top[0].FullName 'bin\alexandrite.bat'))) {
        Fail 'no_binary' $Archive $Version
    }
    $target = Join-Path $Root "versions\$Version"
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    Move-Item -LiteralPath $top[0].FullName $target
    $hash = Get-SumsHash 'install.ps1'
    if ($hash) {
        Get-Url "$Releases/$script:Tag/download/install.ps1" (Join-Path $Tmp 'install.ps1')
        Assert-Checksum (Join-Path $Tmp 'install.ps1') $hash 'install.ps1'
    } elseif ($PSCommandPath -and (Split-Path -Leaf $PSCommandPath) -eq 'install.ps1') {
        Copy-Item -LiteralPath $PSCommandPath (Join-Path $Tmp 'install.ps1')
    }
}

function Test-Release {
    $new = Join-Path $Root "versions\$Version\bin\alexandrite.bat"
    $result = Invoke-App $new @('--version')
    if ($result.Code -ne 0) {
        Warn 'does_not_start' $Version
        [Console]::Error.WriteLine($result.Output)
        return $false
    }
    if (-not $result.Output.Contains("alexandrite $Version")) {
        Warn 'names_itself' $Version $result.Output
        return $false
    }
    Tick 'starts' $Version
    if (-not $Prev) { return $true }
    $check = Invoke-App $new @('config', 'check') -Locations
    if ($check.Code -eq 0) {
        Tick 'check_passes' $Version
        return $true
    }
    $old = Invoke-App (Join-Path $Root "versions\$Prev\bin\alexandrite.bat") @('config', 'check') -Locations
    if ($old.Code -ne 0) {
        Warn 'both_fail' $Version $Prev
        [Console]::Error.WriteLine($check.Output)
        return $true
    }
    Warn 'check_fails' $Version
    [Console]::Error.WriteLine($check.Output)
    return $false
}

function Write-Launcher {
    $bin = Join-Path $Root 'bin'
    New-Item -ItemType Directory -Force -Path $bin | Out-Null
    $launcher = @'
@echo off
setlocal
for %%i in ("%~dp0..") do set "ALEXANDRITE_INSTALL_DIR=%%~fi"
set /p ALEXANDRITE_CURRENT=<"%ALEXANDRITE_INSTALL_DIR%\current.txt"
if not defined ALEXANDRITE_JAVA_HOME if exist "%ALEXANDRITE_INSTALL_DIR%\jre\bin\java.exe" set "ALEXANDRITE_JAVA_HOME=%ALEXANDRITE_INSTALL_DIR%\jre"
if defined ALEXANDRITE_JAVA_HOME set "JAVA_HOME=%ALEXANDRITE_JAVA_HOME%"
call "%ALEXANDRITE_INSTALL_DIR%\versions\%ALEXANDRITE_CURRENT%\bin\alexandrite.bat" %*
exit /b %ERRORLEVEL%
'@
    $next = Join-Path $bin 'alexandrite.cmd.new'
    [IO.File]::WriteAllText($next, $launcher.Replace("`r`n", "`n").Replace("`n", "`r`n"))
    Move-Item -LiteralPath $next (Join-Path $bin 'alexandrite.cmd') -Force
}

# Adds the bin directory to the user's PATH unless -NoModifyPath; sets $script:OnPath to whether it was there before.
function Add-ToPath {
    $bin = Join-Path $Root 'bin'
    $key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment')
    try {
        $user = [string]$key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        $entries = @($user -split ';' | Where-Object { $_ })
        $script:OnPath = $entries -contains $bin
        if (-not $script:OnPath -and -not $NoModifyPath) {
            $key.SetValue('Path', ((@($entries) + $bin) -join ';'), [Microsoft.Win32.RegistryValueKind]::ExpandString)
        }
    } finally {
        $key.Close()
    }
    if (-not $script:OnPath) {
        if ($NoModifyPath) {
            Say 'not_on_path' $bin
            return
        }
        # Setting a variable through Environment tells running programs that the environment changed.
        [Environment]::SetEnvironmentVariable('ALEXANDRITE_PATH_CHANGED', '1', 'User')
        [Environment]::SetEnvironmentVariable('ALEXANDRITE_PATH_CHANGED', $null, 'User')
        Ok 'path_added' $bin
    }
    if (@($env:Path -split ';') -notcontains $bin) { $env:Path = "$env:Path;$bin" }
}

function Get-RunningTask {
    try {
        $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    } catch {
        $task = $null
    }
    if ($task -and $task.State -eq 'Running') { $TaskName } else { '' }
}

function Restart-Task {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    for ($i = 0; $i -lt 30 -and (Get-ScheduledTask -TaskName $TaskName).State -eq 'Running'; $i++) { Start-Sleep -Seconds 1 }
    Start-ScheduledTask -TaskName $TaskName
}

# Polls `alexandrite status` until the instance answers.
function Wait-Ready {
    $launcher = Join-Path $Root 'bin\alexandrite.cmd'
    for ($waited = 0; $waited -lt $ReadyTimeout; ) {
        $result = Invoke-App $launcher @('status') -Locations
        if ($result.Code -eq 0) { return $true }
        Start-Sleep -Seconds 1
        $waited++
    }
    return $false
}

# Switches back to $Prev after the failure the text $Key names with the arguments that follow.
function Undo-Switch([string]$Key) {
    Warn $Key @args
    if ($OldPrevious -eq $Version) { $script:OldPrevious = '' }
    Switch-To $Prev $OldPrevious
    if ($Service) {
        Restart-Task
        if (-not (Wait-Ready)) { Warn 'not_back' $Prev }
    }
    Remove-Item -LiteralPath (Join-Path $Root "versions\$Version") -Recurse -Force -ErrorAction SilentlyContinue
    Fail 'switched_back' $Prev
}

function Remove-OldVersions {
    $keep = @((Get-Pointer 'current'), (Get-Pointer 'previous'))
    Get-ChildItem -LiteralPath (Join-Path $Root 'versions') -Directory | Where-Object { $keep -notcontains $_.Name } |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
}

# Keeps this script for `alexandrite update`, with a byte order mark so Windows PowerShell reads it as UTF-8.
function Save-Installer {
    $bytes = [IO.File]::ReadAllBytes((Join-Path $Tmp 'install.ps1'))
    if (-not ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) {
        $bytes = [byte[]](0xEF, 0xBB, 0xBF) + $bytes
    }
    [IO.File]::WriteAllBytes((Join-Path $Root 'install.ps1.new'), $bytes)
    Move-Item -LiteralPath (Join-Path $Root 'install.ps1.new') (Join-Path $Root 'install.ps1') -Force
}

function Show-Summary {
    $launcher = Join-Path $Root 'bin\alexandrite.cmd'
    if (-not $Fancy) {
        if ($Prev) { Say 'installed_was' $Version $Prev } else { Say 'installed_at' $Version $launcher }
        return
    }
    Write-Host ''
    Ok 'installed' $Version
    if ($Prev) { Show-Value 'key_version' (Msg 'version_was' $Version $Prev) } else { Show-Value 'key_version' $Version }
    Show-Value 'key_location' $Root
    Show-Value 'key_command' $launcher
    Show-Value 'key_java' $(if ($JavaUsed) { $JavaUsed } else { Msg 'none' })
    if ($OnPath) {
        Point 'run' 'alexandrite'
    } elseif ($NoModifyPath) {
        Point 'run' $launcher
    } else {
        Point 'reload'
    }
}

# Runs `alexandrite init` in this console when no config file exists yet.
function Invoke-Init {
    $launcher = Join-Path $Root 'bin\alexandrite.cmd'
    $where = Invoke-App $launcher @('config', 'path') -Locations
    if ($where.Code -eq 0 -and $where.Output) {
        if (Test-Path -LiteralPath $where.Output.Trim()) { return }
    } elseif ($Prev) {
        return
    }
    if ([Console]::IsInputRedirected) {
        Say 'setup_skipped'
        return
    }
    Write-Host ''
    Point 'starting_setup'
    $all = @('init')
    if ($Config) { $all += @('--config', $Config) }
    if ($DataDir) { $all += @('--data-dir', $DataDir) }
    & $launcher @all
}

function Install-New {
    Show-Stage 2 'stage_java'
    Initialize-Java
    Show-Stage 3 'stage_download'
    Install-Release
    if (-not (Test-Release)) {
        Remove-Item -LiteralPath (Join-Path $Root "versions\$Version") -Recurse -Force -ErrorAction SilentlyContinue
        if ($Prev) { Fail 'not_installed_stays' $Version $Prev }
        Fail 'not_installed_new' $Version
    }
    Show-Stage 4 'stage_install'
    Write-Launcher
    $script:Service = if ($Prev) { Get-RunningTask } else { '' }
    Switch-To $Version $Prev
    Tick 'switched' $Version
    if ($Prev) {
        if ($Service) {
            Info 'restarting' $Version
            try { Restart-Task } catch {
                Undo-Switch 'no_restart' $Version $_
            }
            if (-not (Wait-Ready)) { Undo-Switch 'no_answer' $Version $ReadyTimeout }
            Tick 'restarted' $Version
        } else {
            Info 'no_service' $Version
        }
        Remove-OldVersions
    }
    if (Test-Path -LiteralPath (Join-Path $Tmp 'install.ps1')) { Save-Installer }
    Set-Pointer 'channel' $script:Channel
    Add-ToPath
    Show-Summary
}

function Invoke-Install {
    if ($env:OS -ne 'Windows_NT') { Fail 'only_windows' }
    $script:Channel = $Channel.ToLowerInvariant()
    if ($script:Channel -notin @('stable', 'ci')) { Fail 'no_channel' $Channel }
    $script:Version = $Version.TrimStart('v')
    $script:Pinned = [bool]$script:Version
    $script:OnPath = $true
    $script:JavaUsed = ''
    Show-Stage 1 'stage_environment'
    New-Item -ItemType Directory -Force -Path (Join-Path $Root 'versions') | Out-Null
    $lock = Join-Path $Root '.lock'
    try {
        New-Item -ItemType Directory -Path $lock | Out-Null
    } catch {
        Fail 'locked' $Root $lock
    }
    $script:Tmp = Join-Path $Root ".tmp.$PID"
    $initAfter = $false
    $javaHome = $env:JAVA_HOME
    try {
        New-Item -ItemType Directory -Path $Tmp | Out-Null
        $script:Prev = Get-Pointer 'current'
        $script:OldPrevious = Get-Pointer 'previous'
        $prevChannel = Get-Pointer 'channel'
        if (-not $prevChannel) { $prevChannel = 'stable' }
        if ($Update -and -not $Prev) { Fail 'not_installed' $Root }
        Resolve-Release
        Show-Environment
        if ($Version -eq $Prev -and (Test-Path -LiteralPath (Join-Path $Root "versions\$Prev\bin\alexandrite.bat"))) {
            Show-Stage 2 'stage_java'
            Initialize-Java
            Show-Stage 4 'stage_install'
            Write-Launcher
            Add-ToPath
            Set-Pointer 'channel' $script:Channel
            Ok 'installed_already' $Version
            $initAfter = -not $NoInit -and -not $Update
        } elseif ($Update -and -not $Pinned -and $script:Channel -eq $prevChannel -and (Test-VersionBefore $Version $Prev)) {
            Info 'newer' $Prev $Version
        } else {
            Install-New
            $initAfter = -not $NoInit -and -not $Update
        }
    } finally {
        $env:JAVA_HOME = $javaHome
        Remove-Item -LiteralPath $Tmp -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $lock -Force -ErrorAction SilentlyContinue
    }
    if ($initAfter) { Invoke-Init }
}

if ($Help) {
    Show-Usage
    return
}
try {
    Invoke-Install
} catch {
    $message = $_.Exception.Message
    if ($FancyErr) { $message = "  $([char]0x2717) $message" }
    [Console]::Error.WriteLine($message)
    if ($PSCommandPath) { exit 1 }
}
