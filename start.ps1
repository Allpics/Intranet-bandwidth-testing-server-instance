# ============================================================================
#  内网测速服务 —— 一键启动（Node.js 优先，Java 备用）
#  用法:
#     .\start.ps1                     # 默认端口 8000，后台运行
#     .\start.ps1 -Port 8081          # 指定端口
#     .\start.ps1 -Runtime java       # 用 Java 版实现
#     .\start.ps1 -Foreground         # 当前窗口前台运行（Ctrl+C 停止）
#     .\start.ps1 -Firewall           # 尝试添加防火墙入站规则（需管理员）
#     .\start.ps1 -Stop               # 停止服务
#     .\start.ps1 -Clean              # 清掉编译产物后重新编译（仅 java 运行时）
# ============================================================================
[CmdletBinding()]
param(
    [int]    $Port = 8000,
    [string] $Bind = '0.0.0.0',
    [ValidateSet('node', 'java')]
    [string] $Runtime = 'node',
    [switch] $Foreground,
    [switch] $Firewall,
    [switch] $Stop,
    [switch] $Clean
)

$ErrorActionPreference = 'Stop'
$root    = $PSScriptRoot
$classDir = Join-Path $root 'classes'
$pidFile = Join-Path $root '.server.pid'
$logFile = Join-Path $root 'server.log'
$errFile = Join-Path $root 'server.err.log'

function Write-Head($t) {
    Write-Host ''
    Write-Host " $t" -ForegroundColor Cyan
    Write-Host (' ' + ('-' * 62)) -ForegroundColor DarkGray
}

function Get-LanIPv4 {
    # 优先按网卡枚举，可以排除 WSL / Hyper-V / VPN / 代理（Clash 等）的虚拟网卡，
    # 否则会把 172.x、198.18.x 这类地址也当成"内网访问地址"打印出来，误导使用者。
    $found = New-Object System.Collections.Generic.List[string]
    try {
        foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
            if ($nic.OperationalStatus -ne 'Up') { continue }
            if ($nic.NetworkInterfaceType -in 'Loopback', 'Tunnel') { continue }
            $props = $nic.GetIPProperties()
            foreach ($ua in $props.UnicastAddresses) {
                $addr = $ua.Address
                if ($addr.AddressFamily -ne 'InterNetwork') { continue }
                $ip = $addr.IPAddressToString
                if ($ip -like '127.*' -or $ip -like '169.254.*') { continue }
                # 198.18.0.0/15 是基准测试保留段，实践中几乎只有 Clash 之类的 fake-ip 代理在用
                if ($ip -like '198.18.*' -or $ip -like '198.19.*') { continue }
                $found.Add($ip)
            }
        }
    } catch { }
    if ($found.Count) { return @($found | Select-Object -Unique) }

    # 兜底：按主机名解析（虚拟网卡多的时候可能不准，但总比没有强）
    try {
        $ips = [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
               Where-Object { $_.AddressFamily -eq 'InterNetwork' }
        $list = @($ips | ForEach-Object { $_.IPAddressToString } |
                  Where-Object { $_ -notlike '127.*' -and $_ -notlike '169.254.*' -and $_ -notlike '198.1[89].*' })
        if ($list.Count) { return @($list | Select-Object -Unique) }
    } catch { }
    return @('127.0.0.1')
}

function Find-Node {
    # PATH 里可能没有（新装的运行时通常要重开终端），所以也直接找常见安装位置
    $cmd = Get-Command node -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cands = @(
        "$env:ProgramFiles\nodejs\node.exe",
        "${env:ProgramFiles(x86)}\nodejs\node.exe",
        "$env:LOCALAPPDATA\Programs\nodejs\node.exe",
        "$env:LOCALAPPDATA\fnm_multishells\default\node.exe",
        "$env:USERPROFILE\scoop\apps\nodejs\current\node.exe",
        "$env:LOCALAPPDATA\Microsoft\WinGet\Links\node.exe"
    )
    foreach ($c in $cands) { if (Test-Path $c) { return $c } }
    return $null
}

# ---------------------------------------------------------------- 停止服务
if ($Stop) {
    $stopped = $false
    if (Test-Path $pidFile) {
        $oldPid = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if ($oldPid -and (Get-Process -Id $oldPid -ErrorAction SilentlyContinue)) {
            Stop-Process -Id $oldPid -Force
            Write-Host " 已停止测速服务 (PID $oldPid)" -ForegroundColor Yellow
            $stopped = $true
        } else {
            Write-Host " PID $oldPid 已不在运行" -ForegroundColor DarkGray
        }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }
    if (-not $stopped) {
        Write-Host " 未找到运行中的服务，按端口 $Port 兜底查找…" -ForegroundColor DarkGray
        try {
            Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
                ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
        } catch { }
    }
    return
}

# ---------------------------------------------------------------- 挑运行时
Write-Head '运行时检查'

$nodeExe = $null
$javaExe = $null
if ($Runtime -eq 'node') {
    $nodeExe = Find-Node
    if (-not $nodeExe) {
        Write-Host ' 没有找到 node。' -ForegroundColor Yellow
        Write-Host '  · 刚装完 Node 请重开一个终端，或用完整路径运行本脚本' -ForegroundColor DarkGray
        Write-Host '  · 或改用 Java 版： .\start.ps1 -Runtime java' -ForegroundColor DarkGray
        exit 1
    }
    Write-Host " 运行时 : Node.js" -ForegroundColor Green
    Write-Host " node   : $nodeExe"
    Write-Host (" 版本   : " + (& $nodeExe --version))
} else {
    $javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
    if (-not $javaExe) {
        Write-Host ' 没有找到 java，请改用 Node 版： .\start.ps1' -ForegroundColor Red
        exit 1
    }
    Write-Host " 运行时 : Java" -ForegroundColor Green
    Write-Host " java   : $javaExe"
    Write-Host (" 版本   : " + ((& $javaExe -version 2>&1 | Select-Object -First 1)))

    $javac = (Get-Command javac -ErrorAction SilentlyContinue).Source
    $src   = Join-Path $root 'server\SpeedTestServer.java'
    New-Item -ItemType Directory -Force -Path $classDir | Out-Null
    if ($Clean -and (Test-Path $classDir)) { Remove-Item $classDir -Recurse -Force; New-Item -ItemType Directory -Force -Path $classDir | Out-Null }
    $cls = Join-Path $classDir 'SpeedTestServer.class'
    if ((-not (Test-Path $cls)) -or ((Get-Item $src).LastWriteTime -gt (Get-Item $cls).LastWriteTime)) {
        if (-not $javac) { Write-Host ' 需要 JDK（ji 含 javac）才能编译 Java 版' -ForegroundColor Red; exit 1 }
        Write-Head '编译 Java 版'
        & $javac -encoding UTF-8 -d $classDir $src
        if ($LASTEXITCODE -ne 0) { Write-Host ' 编译失败' -ForegroundColor Red; exit 1 }
        Write-Host ' 编译完成' -ForegroundColor Green
    }
}

# ---------------------------------------------------------------- 防火墙
if ($Firewall) {
    Write-Head '防火墙'
    try {
        $ruleName = "LAN Speed Test ($Port)"
        if (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue) {
            Write-Host " 规则已存在：$ruleName" -ForegroundColor DarkGray
        } else {
            New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow `
                -Protocol TCP -LocalPort $Port -Profile Private,Domain | Out-Null
            Write-Host " 已添加入站放行：$ruleName (TCP $Port)" -ForegroundColor Green
        }
    } catch {
        Write-Host " 添加失败（通常需要管理员）：$($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host ' 如其他设备打不开，请以管理员身份运行： .\start.ps1 -Firewall' -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------- 端口占用
$inUse = $null
try { $inUse = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue } catch { }
if ($inUse) {
    Write-Host ''
    Write-Host " 端口 $Port 已被占用（PID $($inUse[0].OwningProcess)）。" -ForegroundColor Red
    Write-Host " 先停止： .\start.ps1 -Stop     或换端口： .\start.ps1 -Port 8081" -ForegroundColor Red
    exit 2
}

# ---------------------------------------------------------------- 启动
if ($Runtime -eq 'node') {
    $script = Join-Path $root 'server\server.js'
    $argList = @("`"$script`"", "$Port", $Bind)
    $exe = $nodeExe
} else {
    $exe = $javaExe
    $argList = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                 '-cp', "`"$classDir`"", 'SpeedTestServer', "$Port", $Bind)
}

if ($Foreground) {
    Write-Head "启动服务（前台，Ctrl+C 停止） $Runtime / 端口 $Port"
    if ($Runtime -eq 'node') { & $exe @($script, "$Port", $Bind) }
    else { & $exe @('-Dfile.encoding=UTF-8', '-cp', $classDir, 'SpeedTestServer', "$Port", $Bind) }
    return
}

Write-Head "启动服务 $Runtime / 端口 $Port"
$proc = Start-Process -FilePath $exe -ArgumentList $argList -WorkingDirectory $root -PassThru `
    -WindowStyle Hidden -RedirectStandardOutput $logFile -RedirectStandardError $errFile
$serverPid = $proc.Id
$serverPid | Set-Content -Path $pidFile -Encoding ascii
Start-Sleep -Milliseconds 1500

if ($proc.HasExited) {
    Write-Host " 服务启动失败（退出码 $($proc.ExitCode)）" -ForegroundColor Red
    if (Test-Path $errFile) { Get-Content $errFile | Select-Object -First 10 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red } }
    if (Test-Path $logFile) { Get-Content $logFile | Select-Object -First 10 | ForEach-Object { Write-Host "  $_" } }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    exit 3
}

Write-Host " 服务已启动，PID $serverPid" -ForegroundColor Green
Write-Host ''
Write-Host ' 本机访问：' -NoNewline
Write-Host " http://127.0.0.1:$Port/" -ForegroundColor White
foreach ($ip in Get-LanIPv4) {
    Write-Host ' 内网访问：' -NoNewline
    Write-Host " http://${ip}:$Port/" -ForegroundColor Green
}
Write-Host ''
Write-Host ' 其他设备（手机/平板/别的电脑）用上面「内网访问」地址。' -ForegroundColor DarkGray
Write-Host " 日志： server.log / server.err.log" -ForegroundColor DarkGray
Write-Host " 停止： .\start.ps1 -Stop" -ForegroundColor DarkGray
Write-Host ''
