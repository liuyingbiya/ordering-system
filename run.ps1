# ============================================================
#  一键启动脚本
#
#  用法（如果双击不管用，用这个）：
#      powershell -NoProfile -ExecutionPolicy Bypass -File .\run.ps1
#
#  为什么需要 -ExecutionPolicy Bypass？
#   Windows 默认禁止运行未签名的 .ps1 脚本，会报
#  「未对文件进行数字签名」。Bypass 只对这一次执行生效，不改系统设置。
# ============================================================

$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
Set-Location $projectDir

# --- 环境变量（这台机器的实际位置）---
$env:JAVA_HOME = 'E:\envriorment\java'
$mvn = 'E:\envriorment\maven\apache-maven-3.8.4\bin\mvn.cmd'
$jar = Join-Path $projectDir 'target\ordering-springboot-2.0.0.jar'

Write-Host ''
Write-Host '========================================' -ForegroundColor Cyan
Write-Host '  点餐系统 Spring Boot 版' -ForegroundColor Cyan
Write-Host '========================================' -ForegroundColor Cyan
Write-Host ''

# --- 检查 MySQL 服务（阶段 2 之后必须要有数据库）---
$mysqlSvc = Get-Service -Name 'MySQL80' -ErrorAction SilentlyContinue
if ($mysqlSvc -and $mysqlSvc.Status -ne 'Running') {
    Write-Host 'MySQL80 服务没启动，正在启动...' -ForegroundColor Yellow
    Start-Service MySQL80
    $mysqlSvc.Refresh()
}
if ($mysqlSvc -and $mysqlSvc.Status -eq 'Running') {
    Write-Host "MySQL80: Running" -ForegroundColor Green
} else {
    Write-Host 'MySQL80 未运行！页面会 500，请先启动数据库。' -ForegroundColor Red
}

# --- 检查 jar，没有就编译 ---
if (-not (Test-Path $jar)) {
    Write-Host ''
    Write-Host '目标 jar 不存在，开始编译（首次会下载依赖，需要几分钟）...' -ForegroundColor Yellow
    & $mvn -B package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Host '编译失败。把 .mvn\build.log 最后 30 行发给我。' -ForegroundColor Red
        exit 1
    }
}

# --- 准备运行时目录 ---
# Tomcat 启动时要写临时文件。这个目录必须存在且可写。
# 放在项目内，避免系统临时目录权限问题。
$runtimeDir = Join-Path $projectDir '.runtime'
if (-not (Test-Path $runtimeDir)) {
    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
}

Write-Host ''
Write-Host '启动中...' -ForegroundColor Green
Write-Host '  http://localhost:8080' -ForegroundColor White
Write-Host '停止服务：在这个窗口按 Ctrl + C' -ForegroundColor DarkGray
Write-Host ''

Start-Process 'http://localhost:8080' -ErrorAction SilentlyContinue

# ============================================================
#  ★ 两个关键 JVM 参数，缺一个就会出问题
#
#  1) -Dfile.encoding=UTF-8
#     这台机器的 Windows 代码页是 936（GBK），JDK 8 的 JVM 默认 file.encoding=GBK。
#     不加这个参数，从 MySQL 读出来的中文会变成「???」——
#     看起来像数据库坏了，其实是读的时候按 GBK 解码 UTF-8 字节。
#     注意：Java 18+ 默认就是 UTF-8，不用加；本项目用 Java 8，必须加。
#
#  2) -Djava.io.tmpdir=<项目内 .runtime>
#     Tomcat 启动时要创建临时目录。默认临时目录在系统 Temp 下，
#     某些受限环境下没有写权限，会报 "Unable to create tempDir"。
#
#  ⚠️ 不要加 -Dsun.jnu.encoding=UTF-8！
#     那个参数控制文件名编解码，设成 UTF-8 后 JVM 反而打不开路径里带中文的文件
#     （连自己的 jar 都读不了）。这个坑我踩过。
# ============================================================
& "$env:JAVA_HOME\bin\java.exe" `
    "-Dfile.encoding=UTF-8" `
    "-Djava.io.tmpdir=$runtimeDir" `
    -jar $jar
