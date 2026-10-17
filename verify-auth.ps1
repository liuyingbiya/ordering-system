# ============================================================
#  管理端鉴权验证脚本
#
#  用法：
#    powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-auth.ps1
#
#  验证什么：
#    加登录之前，任何人在浏览器打开 /order 就能看到所有顾客的
#    姓名、电话、地址，还能改任意订单的状态。
#    这个脚本验证「现在真的拦住了」。
#
#  ★ 断言方式说明（这一点很重要）：
#    不用 -MaximumRedirection 0 去检查 302 状态码和 Location 头。
#    原因：Windows PowerShell 5.1 在那个模式下拿不到
#    $_.Exception.Response，写出来的断言又脆又难维护。
#    改用「跟随重定向后看最终页面内容」——更贴近真实用户体验，
#    也更不容易因为框架版本变化而误报。
# ============================================================

$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$mysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$pass = 0
$fail = 0

function Check($name, $cond, $detail) {
    if ($cond) {
        Write-Host "  [OK]   $name" -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host "  [FAIL] $name" -ForegroundColor Red
        if ($detail) { Write-Host "         $detail" -ForegroundColor DarkYellow }
        $script:fail++
    }
}

$env:MYSQL_PWD = 'root'
function Sql($q) {
    $out = & $mysqlExe -uroot --default-character-set=utf8mb4 -N -B -e $q 2>&1
    return ($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' }) -join "`n"
}

Write-Host ''
Write-Host '=== 管理端鉴权验证 ===' -ForegroundColor Cyan
Write-Host ''

# ---------------------------------------------------------------
# [1] 未登录访问管理端 → 被重定向到登录页
# ---------------------------------------------------------------
Write-Host '[1] 未登录访问 /order 必须被拦截' -ForegroundColor Yellow
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
try {
    $r = Invoke-WebRequest -Uri "$base/order" -WebSession $anon -UseBasicParsing
    Check '被重定向到登录页（说明拦住了）' ($r.Content -like '*管理员登录*')
    Check '没有泄露顾客信息（页面上没有订单数据）' (-not ($r.Content -like '*订单号*'))
} catch {
    Check '[1] 请求 /order' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [2] 登录页本身要能访问（否则死循环）
# ---------------------------------------------------------------
Write-Host '[2] 登录页可访问（不能也被拦，否则死循环）' -ForegroundColor Yellow
try {
    $lp = Invoke-WebRequest -Uri "$base/admin/login" -UseBasicParsing
    Check '登录页 HTTP 200' ($lp.StatusCode -eq 200)
    Check '有用户名输入框' ($lp.Content -like '*name="username"*')
    Check '有密码输入框' ($lp.Content -like '*type="password"*')
} catch {
    Check '[2] 登录页' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [3] 错误密码必须被拒
# ---------------------------------------------------------------
Write-Host '[3] 错误密码必须被拒绝' -ForegroundColor Yellow
$s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
try {
    $bad = Invoke-WebRequest -Uri "$base/admin/login" -Method Post `
        -Body @{ username = 'admin'; password = 'wrong-password' } `
        -WebSession $s -UseBasicParsing
    Check '页面提示用户名或密码错误' ($bad.Content -like '*用户名或密码错误*')
    Check '没有登录成功（还在登录页）' ($bad.Content -like '*管理员登录*')

    $still = Invoke-WebRequest -Uri "$base/order" -WebSession $s -UseBasicParsing
    Check '错误密码后仍进不去 /order' ($still.Content -like '*管理员登录*')
} catch {
    Check '[3] 错误密码' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [4] 正确密码 → 能进管理端
# ---------------------------------------------------------------
Write-Host '[4] 正确密码登录后能访问管理端' -ForegroundColor Yellow
try {
    Invoke-WebRequest -Uri "$base/admin/login" -Method Post `
        -Body @{ username = 'admin'; password = 'admin123'; redirect = '/order' } `
        -WebSession $s -UseBasicParsing | Out-Null

    $ord = Invoke-WebRequest -Uri "$base/order" -WebSession $s -UseBasicParsing
    Check '登录后 /order 返回 200' ($ord.StatusCode -eq 200)
    Check '是订单管理页面' ($ord.Content -like '*订单管理*')
    Check '有退出登录按钮' ($ord.Content -like '*退出登录*')
} catch {
    Check '[4] 正确密码登录' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [5] 未登录不能改订单状态（最关键）
# ---------------------------------------------------------------
Write-Host '[5] 未登录不能修改订单状态（最关键的一项）' -ForegroundColor Yellow
$testOrderId = $null
try {
    $cust = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-WebRequest -Uri "$base/cart/add" -Method Post -Body @{ dishId = 10; quantity = 1 } `
        -WebSession $cust -UseBasicParsing | Out-Null
    Invoke-WebRequest -Uri "$base/order/create" -Method Post `
        -Body @{ userName = '鉴权测试'; userPhone = '13700137000'; userAddress = '鉴权测试地址' } `
        -WebSession $cust -UseBasicParsing | Out-Null

    $testOrderId = (Sql 'SELECT id FROM wechat_ordering.orders ORDER BY id DESC LIMIT 1').Trim()
    Check "测试订单已创建（id=$testOrderId）" ([bool]$testOrderId)

    $statusBefore = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()

    $attacker = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-WebRequest -Uri "$base/order/$testOrderId/status" -Method Post -Body @{ status = 4 } `
        -WebSession $attacker -UseBasicParsing | Out-Null

    $statusAfter = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()
    Check "未登录改状态没生效（$statusBefore → $statusAfter）" ($statusAfter -eq $statusBefore) "实际 $statusAfter"
} catch {
    Check '[5] 未登录改状态' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [6] 已登录可以改状态
# ---------------------------------------------------------------
Write-Host '[6] 已登录管理员可以修改订单状态' -ForegroundColor Yellow
try {
    $statusBefore = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()
    Invoke-WebRequest -Uri "$base/order/$testOrderId/status" -Method Post -Body @{ status = 1 } `
        -WebSession $s -UseBasicParsing | Out-Null
    $statusAfter = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()
    Check "管理员改状态生效（$statusBefore → $statusAfter，期望 1）" ($statusAfter -eq '1') "实际 $statusAfter"
} catch {
    Check '[6] 管理员改状态' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [7] 顾客端不能被误拦（拦截范围要精确）
# ---------------------------------------------------------------
Write-Host '[7] 顾客端页面不能被误拦（拦截范围必须精确）' -ForegroundColor Yellow
try {
    $h = Invoke-WebRequest -Uri "$base/" -WebSession $anon -UseBasicParsing
    Check '首页不需要登录' ($h.StatusCode -eq 200)

    $d = Invoke-WebRequest -Uri "$base/dish" -WebSession $anon -UseBasicParsing
    Check '菜单页不需要登录' ($d.StatusCode -eq 200)

    $dd = Invoke-WebRequest -Uri "$base/dish/3" -WebSession $anon -UseBasicParsing
    Check '菜品详情不需要登录' ($dd.StatusCode -eq 200)

    $my = Invoke-WebRequest -Uri "$base/order/my" -WebSession $anon -UseBasicParsing
    Check '「我的订单」不需要登录（它是顾客端，不是管理端）' ($my.Content -like '*我的订单*')

    $det = Invoke-WebRequest -Uri "$base/order/$testOrderId" -WebSession $anon -UseBasicParsing
    Check '订单详情不需要登录' ($det.Content -like '*订单详情*')
} catch {
    Check '[7] 顾客端' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [8] 退出登录
# ---------------------------------------------------------------
Write-Host '[8] 退出登录后再访问管理端又被拦' -ForegroundColor Yellow
try {
    Invoke-WebRequest -Uri "$base/admin/logout" -Method Post -WebSession $s `
        -UseBasicParsing | Out-Null

    $after = Invoke-WebRequest -Uri "$base/order" -WebSession $s -UseBasicParsing
    Check '退出后被重定向到登录页' ($after.Content -like '*管理员登录*')
    Check '退出后看不到订单数据' (-not ($after.Content -like '*订单号*'))
} catch {
    Check '[8] 退出登录' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [9] 清理
# ---------------------------------------------------------------
Write-Host '[9] 清理测试数据' -ForegroundColor Yellow
try {
    if ($testOrderId) {
        Sql "DELETE FROM wechat_ordering.orders WHERE id = $testOrderId" | Out-Null
        Sql 'UPDATE wechat_ordering.dish SET stock = 100 WHERE id = 10' | Out-Null  # 炸鸡腿
    }
    $n = [int](Sql 'SELECT COUNT(*) FROM wechat_ordering.orders')
    Check "清理完成（剩余 $n 笔订单）" ($n -ge 0)
} catch {
    Check '[9] 清理' $false $_.Exception.Message
}

Write-Host ''
Write-Host '========================================' -ForegroundColor Cyan
if ($fail -eq 0) {
    Write-Host "  全部通过：$pass 项" -ForegroundColor Green
} else {
    Write-Host "  通过 $pass 项，失败 $fail 项" -ForegroundColor Red
}
Write-Host '========================================' -ForegroundColor Cyan
Write-Host ''
exit $(if ($fail -eq 0) { 0 } else { 1 })
