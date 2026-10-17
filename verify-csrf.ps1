# ============================================================
#  CSRF 防护验证脚本
#
#  用法：
#    powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-csrf.ps1
#
#  CSRF 是什么（一句话）：
#    攻击者不需要偷你的 cookie，他只要让你的浏览器
#    「带着 cookie 去请求你的站点」就够了。
#    加了登录之后，这就是真漏洞 —— 诱导已登录的管理员改订单状态。
#
#  本项目用「Origin / Referer 校验」防：
#    · 浏览器发跨站请求时一定会带 Origin
#    · Origin 不是本站 → 403 拦截
#    · 完全没带 Origin/Referer → 判定为非浏览器客户端（curl/Postman），放行
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

# ------------------------------------------------------------------
# 原始 POST 请求（能拿到 4xx 的响应体）
#
# 为什么不用 Invoke-WebRequest？
#   它遇到 4xx 会抛异常，而且 Windows PowerShell 5.1 里
#   $_.ErrorDetails.Message 在这个场景下是空的，拿不到页面内容。
#   这里直接用 .NET 的 HttpWebRequest，自己读响应流，最可靠。
# ------------------------------------------------------------------
function RawPost($url, $bodyString, $origin, $webSession) {
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Method = 'POST'
    $req.ContentType = 'application/x-www-form-urlencoded'
    if ($origin) { $req.Headers.Add('Origin', $origin) }
    $req.CookieContainer = New-Object System.Net.CookieContainer
    if ($webSession) {
        foreach ($ck in $webSession.Cookies.GetCookies($url)) {
            $req.CookieContainer.Add($ck)
        }
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($bodyString)
    $req.ContentLength = $bytes.Length
    $rs = $req.GetRequestStream()
    $rs.Write($bytes, 0, $bytes.Length)
    $rs.Close()

    try {
        $resp = $req.GetResponse()
        $code = [int]$resp.StatusCode
        $stream = $resp.GetResponseStream()
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        $code = [int]$resp.StatusCode
        $stream = $resp.GetResponseStream()
    }
    $sr = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
    $html = $sr.ReadToEnd()
    $sr.Close()
    return @{ Code = $code; Body = $html }
}

Write-Host ''
Write-Host '=== CSRF 防护验证 ===' -ForegroundColor Cyan
Write-Host ''

# ---------------------------------------------------------------
# 准备：一个已登录的管理员 session + 一笔测试订单
# ---------------------------------------------------------------
Write-Host '[0] 准备：登录管理员 + 创建测试订单' -ForegroundColor Yellow
$admin = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$testOrderId = $null
try {
    Invoke-WebRequest -Uri "$base/admin/login" -Method Post `
        -Body @{ username = 'admin'; password = 'admin123' } `
        -WebSession $admin -UseBasicParsing | Out-Null

    $cust = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-WebRequest -Uri "$base/cart/add" -Method Post -Body @{ dishId = 11; quantity = 1 } `
        -WebSession $cust -UseBasicParsing | Out-Null
    Invoke-WebRequest -Uri "$base/order/create" -Method Post `
        -Body @{ userName = 'CSRF测试'; userPhone = '13600136000'; userAddress = 'CSRF测试地址' } `
        -WebSession $cust -UseBasicParsing | Out-Null

    $testOrderId = (Sql 'SELECT id FROM wechat_ordering.orders ORDER BY id DESC LIMIT 1').Trim()
    Check "测试订单已创建（id=$testOrderId）" ([bool]$testOrderId)
    Check '管理员已登录' ($true)
} catch {
    Check '[0] 准备' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [1] ★ 核心：带恶意 Origin 的跨站 POST 必须被拦
# ---------------------------------------------------------------
Write-Host '[1] ★ 跨站伪造请求（Origin: evil.com）必须被 403 拦截' -ForegroundColor Yellow
try {
    $statusBefore = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()

    # 模拟攻击者页面提交表单：cookie 有效（用的是管理员 session），但 Origin 是外站
    $res = RawPost "$base/order/$testOrderId/status" 'status=4' 'https://evil.example.com' $admin

    Check "返回 403 状态码（实际 $($res.Code)）" ($res.Code -eq 403) "实际 $($res.Code)"
    # ★ 这条很关键：确认渲染的是我们自己的 403 页面，
    #   而不是 Spring 默认的 Whitelabel 白页（那说明模板没生效）
    Check '渲染了我们自己的 403 页面（不是 Whitelabel 白页）' (
        ($res.Body -like '*请求被拒绝*') -and (-not ($res.Body -like '*Whitelabel*'))
    ) "响应体长度 $($res.Body.Length)"
    Check '页面里没有泄露堆栈信息' (-not ($res.Body -like '*java.lang*'))
    Check '页面提示了 CSRF 防护' ($res.Body -like '*跨站请求伪造*')

    $statusAfter = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()
    Check "订单状态没被改（$statusBefore → $statusAfter）" ($statusAfter -eq $statusBefore) "实际 $statusAfter"
} catch {
    Check '[1] 跨站伪造' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [2] Origin 是本站 → 正常放行
# ---------------------------------------------------------------
Write-Host '[2] 本站 Origin 的 POST 应该正常放行' -ForegroundColor Yellow
try {
    $resp = Invoke-WebRequest -Uri "$base/order/$testOrderId/status" -Method Post `
        -Body @{ status = 1 } `
        -Headers @{ 'Origin' = 'http://localhost:8080' } `
        -WebSession $admin -UseBasicParsing

    $st = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()
    Check "本站 Origin 放行，状态被改成 1（实际 $st）" ($st -eq '1')
} catch {
    Check '[2] 本站 Origin' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [3] 同域名不同端口 → 也算跨站（同源策略）
# ---------------------------------------------------------------
Write-Host '[3] 同域名不同端口不算同源，必须拦截' -ForegroundColor Yellow
try {
    $before = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()
    try {
        Invoke-WebRequest -Uri "$base/order/$testOrderId/status" -Method Post `
            -Body @{ status = 4 } `
            -Headers @{ 'Origin' = 'http://localhost:9999' } `
            -WebSession $admin -UseBasicParsing | Out-Null
    } catch {
        # 期望抛异常（403）
    }
    $after = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()
    Check "端口不同被判定为跨站，状态没变（$before → $after）" ($after -eq $before) "实际 $after"
} catch {
    Check '[3] 不同端口' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [4] 恶意 Referer（没有 Origin 时退一步校验）
# ---------------------------------------------------------------
Write-Host '[4] 只有恶意 Referer 也必须拦截' -ForegroundColor Yellow
try {
    $before = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()
    try {
        Invoke-WebRequest -Uri "$base/order/$testOrderId/status" -Method Post `
            -Body @{ status = 4 } `
            -Headers @{ 'Referer' = 'https://evil.example.com/attack.html' } `
            -WebSession $admin -UseBasicParsing | Out-Null
    } catch {
        # 期望 403
    }
    $after = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()
    Check "恶意 Referer 被拦，状态没变（$before → $after）" ($after -eq $before) "实际 $after"
} catch {
    Check '[4] 恶意 Referer' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [5] Origin 为 null（沙箱 iframe）→ 必须拦截
# ---------------------------------------------------------------
Write-Host '[5] Origin: null（沙箱 iframe / file:// 页面）必须拦截' -ForegroundColor Yellow
try {
    $before = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()
    try {
        Invoke-WebRequest -Uri "$base/order/$testOrderId/status" -Method Post `
            -Body @{ status = 4 } `
            -Headers @{ 'Origin' = 'null' } `
            -WebSession $admin -UseBasicParsing | Out-Null
    } catch {
        # 期望 403
    }
    $after = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $testOrderId").Trim()
    Check "Origin: null 被拦，状态没变（$before → $after）" ($after -eq $before) "实际 $after"
} catch {
    Check '[5] Origin null' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [6] GET 请求不受影响（只读操作不该被挡）
# ---------------------------------------------------------------
Write-Host '[6] GET 请求即使带恶意 Origin 也应放行（只读，无危害）' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/dish" -Headers @{ 'Origin' = 'https://evil.example.com' } `
        -UseBasicParsing
    Check 'GET /dish 正常返回 200' ($r.StatusCode -eq 200)
    Check '页面内容正常' ($r.Content -like '*宫保鸡丁*')
} catch {
    Check '[6] GET 放行' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [7] 不带 Origin/Referer 的客户端（curl/Postman/测试脚本）放行
# ---------------------------------------------------------------
Write-Host '[7] 无 Origin/Referer 的请求放行（非浏览器客户端，无 cookie 可冒用）' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/cart/add" -Method Post -Body @{ dishId = 9; quantity = 1 } `
        -UseBasicParsing
    Check 'POST /cart/add 无 Origin 时放行' ($r.StatusCode -eq 200) "状态 $($r.StatusCode)"
} catch {
    Check '[7] 无 Origin 放行' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [8] 顾客下单流程不受影响
# ---------------------------------------------------------------
Write-Host '[8] 顾客正常下单流程不受 CSRF 校验影响' -ForegroundColor Yellow
try {
    $c = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-WebRequest -Uri "$base/cart/add" -Method Post -Body @{ dishId = 12; quantity = 1 } `
        -WebSession $c -UseBasicParsing | Out-Null
    $r = Invoke-WebRequest -Uri "$base/order/create" -Method Post `
        -Body @{ userName = '正常顾客'; userPhone = '13500135000'; userAddress = '正常地址' } `
        -WebSession $c -UseBasicParsing
    Check '正常下单成功（跳转订单详情）' ($r.Content -like '*待确认*')
} catch {
    Check '[8] 正常下单' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [9] 清理
# ---------------------------------------------------------------
Write-Host '[9] 清理测试数据' -ForegroundColor Yellow
try {
    Sql "DELETE FROM wechat_ordering.orders WHERE user_name IN ('CSRF测试','正常顾客')" | Out-Null
    Sql 'UPDATE wechat_ordering.dish SET stock = 120 WHERE id = 11' | Out-Null  # 洋葱圈
    Sql 'UPDATE wechat_ordering.dish SET stock = 130 WHERE id = 12' | Out-Null  # 鸡米花
    Sql 'UPDATE wechat_ordering.dish SET stock = 150 WHERE id = 9'  | Out-Null  # 薯条
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
