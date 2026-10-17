# ============================================================
#  阶段 2/3 验证脚本：证明数据真的来自 MySQL
#
#  用法：
#    powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-db.ps1
#
#  和 verify.ps1 的区别：
#    verify.ps1     验证阶段 1（接口、渲染、HTTP 语义）
#    verify-db.ps1  验证阶段 2/3（MyBatis 真的连了数据库、事务真的回滚、状态机真的生效）
#
#  ★ 最关键的几项：
#     [2] 直接在数据库里改一个菜名，页面跟着变 → 证明数据源是数据库，不是内存
#     [7] 把库存改成 1，下单 → 证明事务回滚，库存没被扣坏、订单没留脏数据
#     [8] 负数 / 超库存 加购 → 证明服务端数量校验真的生效
#     [10] 已取消的订单再改状态 → 证明状态机真的拦住了非法迁移
#     [12] 换 session 取消别人的订单 → 证明越权校验生效
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

# 直接执行 SQL（密码走环境变量，避免命令行明文告警）
$env:MYSQL_PWD = 'root'
function Sql($q) {
    $out = & $mysqlExe -uroot --default-character-set=utf8mb4 -N -B -e $q 2>&1
    return ($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' }) -join "`n"
}

Write-Host ''
Write-Host '=== 阶段 2/3 数据库验证 ===' -ForegroundColor Cyan
Write-Host ''

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

# ---------------------------------------------------------------
# [1] 启动即连通数据库
# ---------------------------------------------------------------
Write-Host '[1] 首页菜品来自数据库' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/" -WebSession $session -UseBasicParsing
    $cards = ([regex]::Matches($r.Content, 'class="dish-card"')).Count
    $dbCount = [int](Sql 'SELECT COUNT(*) FROM wechat_ordering.dish WHERE active = true')
    Check "页面卡片数($cards) = 数据库行数($dbCount)" ($cards -eq $dbCount) "页面 $cards / 库 $dbCount"
} catch {
    Check '请求首页' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [2] ★ 改数据库，页面立刻变 —— 这是「数据来自 DB」的铁证
# ---------------------------------------------------------------
Write-Host '[2] ★ 改数据库里的菜名，页面应该跟着变' -ForegroundColor Yellow
try {
    # ------------------------------------------------------------------
    # ⚠️ 这里有个坑必须避开：不能让中文「经过 PowerShell 管道往回写」。
    #
    # 这台机器的 PowerShell 输出编码是 GBK，而 mysql 客户端按 UTF-8 收。
    # 如果写成：
    #     $before = Sql "SELECT name FROM dish WHERE id=1"    # 读出来是 GBK 字节
    #     Sql "UPDATE dish SET name='$before' WHERE id=1"     # 再按 UTF-8 解析 → 双编码乱码
    # 就会把「宫保鸡丁」写成一堆乱码，而且看起来像是数据库坏了。
    #
    # 正确做法：整个「改 → 还原」都在 SQL 里完成，Python/PS 只读布尔结果。
    # 这也是通用经验：**不要在脚本语言里搬运需要保真的字符数据**，
    # 要么全程 sql 侧，要么显式指定编码。
    # ------------------------------------------------------------------
    Sql "UPDATE wechat_ordering.dish SET name = CONCAT('【验证中】', name) WHERE id = 1" | Out-Null

    $r = Invoke-WebRequest -Uri "$base/dish/1" -WebSession $session -UseBasicParsing
    Check '页面显示了刚改的名字' ($r.Content -like '*【验证中】*')

    # 还原：直接把前缀替换掉，不经过 PowerShell
    Sql "UPDATE wechat_ordering.dish SET name = REPLACE(name, '【验证中】', '') WHERE id = 1" | Out-Null

    $r2 = Invoke-WebRequest -Uri "$base/dish/1" -WebSession $session -UseBasicParsing
    Check '改回后临时标记消失' (-not ($r2.Content -like '*【验证中】*'))
    Check '还原后仍是正确的「宫保鸡丁」' ($r2.Content -like '*宫保鸡丁*')
} catch {
    Check '[2] 数据库驱动验证' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [3] 下单一笔，检查订单真的入库
# ---------------------------------------------------------------
Write-Host '[3] 下单 → 检查 orders / order_item 表真写了数据' -ForegroundColor Yellow
$orderId = $null
try {
    $stockBefore = [int](Sql 'SELECT stock FROM wechat_ordering.dish WHERE id = 3')  # 红烧肉

    Invoke-WebRequest -Uri "$base/cart/add" -Method Post -Body @{ dishId = 3; quantity = 2 } `
        -WebSession $session -UseBasicParsing | Out-Null
    $r = Invoke-WebRequest -Uri "$base/order/create" -Method Post `
        -Body @{ userName = '测试用户'; userPhone = '13800000000'; userAddress = '数据库验证地址' } `
        -WebSession $session -UseBasicParsing -MaximumRedirection 5

    Check '下单后跳转到订单详情' ($r.Content -like '*待确认*')

    $row = Sql 'SELECT id FROM wechat_ordering.orders ORDER BY id DESC LIMIT 1'
    Check '数据库里有新订单' ([bool]$row) "查询结果：$row"
    if ($row) { $orderId = $row.Trim() }

    $itemCount = [int](Sql "SELECT COUNT(*) FROM wechat_ordering.order_item WHERE order_id = $orderId")
    Check "order_item 有 1 条明细（实际 $itemCount）" ($itemCount -eq 1)

    $stockAfter = [int](Sql 'SELECT stock FROM wechat_ordering.dish WHERE id = 3')
    Check "库存从 $stockBefore 扣到 $stockAfter（应为 -2）" ($stockAfter -eq ($stockBefore - 2)) "实际 $stockAfter"

    $savedTotal = (Sql "SELECT total_price FROM wechat_ordering.orders WHERE id = $orderId").Trim()
    Check "订单总价入库 = 96.00（实际 $savedTotal）" ($savedTotal -eq '96.00')
} catch {
    Check '[3] 下单入库' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [4] 订单明细通过 collection 嵌套查询带出来
# ---------------------------------------------------------------
Write-Host '[4] 订单详情页能带出明细（验证 resultMap 的 collection）' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/order/$orderId" -WebSession $session -UseBasicParsing
    Check '详情页显示菜名「红烧肉」' ($r.Content -like '*红烧肉*')
    Check '详情页显示数量 x2' ($r.Content -like '*x2*')
    Check '详情页显示小计 96.00' ($r.Content -like '*96.00*')
} catch {
    Check '[4] 订单明细' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [5] 换 session 依然能查到订单 —— 证明不是内存
# ---------------------------------------------------------------
Write-Host '[5] 换一个新 session，订单依然能从数据库查到' -ForegroundColor Yellow
try {
    $newSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $r = Invoke-WebRequest -Uri "$base/order/$orderId" -WebSession $newSession -UseBasicParsing
    Check '换 session 仍能看到这笔订单（因为它存在数据库里）' ($r.Content -like '*红烧肉*')
} catch {
    Check '[5] 跨 session 持久化' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [6] 我的订单按 userId 过滤
# ---------------------------------------------------------------
Write-Host '[6] 我的订单按 userId 过滤' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/order/my" -WebSession $session -UseBasicParsing
    Check '当前 session 能看到自己的订单' ($r.Content -like '*订单号*')
    Check '显示状态待确认' ($r.Content -like '*待确认*')
} catch {
    Check '[6] 我的订单' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [7] ★ 库存不足 → 事务回滚（最关键的一项）
# ---------------------------------------------------------------
Write-Host '[7] ★ 库存不足时下单应该失败，并且订单不能留脏数据' -ForegroundColor Yellow
try {
    $ordersBefore = [int](Sql 'SELECT COUNT(*) FROM wechat_ordering.orders')

    # ------------------------------------------------------------------
    # 怎么构造「加购时够、下单时不够」？
    #
    # 注意：CartService 在【加购】阶段就校验了数量不超库存。
    # 所以不能直接「库存 1、加购 2」—— 那样在加购时就被拦下，走不到下单。
    #
    # 真实场景是这样：用户看到库存 100，加了 2 份进购物车；
    # 在他填收货地址的过程中，别人把这菜买到只剩 1 份。
    # 这时点「提交订单」，才是【加购时够、下单时不够】。
    #
    # 测试步骤：
    #   ① 库存 100 时加购 2 份（加购成功）
    #   ② 后台把库存改成 1（模拟别人抢购）
    #   ③ 提交订单 → decreaseStock 的 WHERE stock >= 2 匹配不到
    #      → OrderService 抛 IllegalStateException → @Transactional 回滚
    # ------------------------------------------------------------------
    Sql 'UPDATE wechat_ordering.dish SET stock = 100 WHERE id = 5' | Out-Null   # 鱼香肉丝

    $s2 = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-WebRequest -Uri "$base/cart/add" -Method Post -Body @{ dishId = 5; quantity = 2 } `
        -WebSession $s2 -UseBasicParsing | Out-Null

    # 模拟「别人抢走了」：库存降到 1，已不够这 2 份
    Sql 'UPDATE wechat_ordering.dish SET stock = 1 WHERE id = 5' | Out-Null

    $r = Invoke-WebRequest -Uri "$base/order/create" -Method Post `
        -Body @{ userName = '测试'; userPhone = '13800138000'; userAddress = '库存测试' } `
        -WebSession $s2 -UseBasicParsing

    Check '页面提示库存不足' ($r.Content -like '*库存不足*')
    Check '仍停留在结算页（表单还在）' (($r.Content -like '*userName*') -and ($r.Content -like '*提交订单*'))
    Check '错误提示里带了菜名' ($r.Content -like '*鱼香肉丝*')

    $ordersAfter = [int](Sql 'SELECT COUNT(*) FROM wechat_ordering.orders')
    Check "订单表没有新增脏数据（$ordersBefore → $ordersAfter）" ($ordersAfter -eq $ordersBefore) "实际：$ordersBefore → $ordersAfter"

    $stockNow = [int](Sql 'SELECT stock FROM wechat_ordering.dish WHERE id = 5')
    Check "库存没被扣（应是 1，实际 $stockNow）" ($stockNow -eq 1) "实际 $stockNow"

    $orphan = [int](Sql 'SELECT COUNT(*) FROM wechat_ordering.order_item oi LEFT JOIN wechat_ordering.orders o ON oi.order_id = o.id WHERE o.id IS NULL')
    Check "没有孤儿明细（外键数据一致）" ($orphan -eq 0) "孤儿明细 $orphan 条"

    Sql 'UPDATE wechat_ordering.dish SET stock = 100 WHERE id = 5' | Out-Null
} catch {
    Check '[7] 事务回滚' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [8] 加购数量非法 → 应该被拒绝（负数刷库存漏洞）
# ---------------------------------------------------------------
Write-Host '[8] ★ 负数量 / 超库存 加购应该被拒绝' -ForegroundColor Yellow
try {
    $s3 = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $stockBefore = [int](Sql 'SELECT stock FROM wechat_ordering.dish WHERE id = 7')  # 酸辣土豆丝 120

    Invoke-WebRequest -Uri "$base/cart/add" -Method Post -Body @{ dishId = 7; quantity = -100 } `
        -WebSession $s3 -UseBasicParsing -MaximumRedirection 5 | Out-Null
    $cartPage = Invoke-WebRequest -Uri "$base/cart" -WebSession $s3 -UseBasicParsing
    Check '负数量被拒绝（购物车仍为空）' ($cartPage.Content -like '*购物车还是空的*')

    Invoke-WebRequest -Uri "$base/cart/add" -Method Post -Body @{ dishId = 7; quantity = 0 } `
        -WebSession $s3 -UseBasicParsing -MaximumRedirection 5 | Out-Null
    $cartPage2 = Invoke-WebRequest -Uri "$base/cart" -WebSession $s3 -UseBasicParsing
    Check '数量 0 被拒绝（购物车仍为空）' ($cartPage2.Content -like '*购物车还是空的*')

    Invoke-WebRequest -Uri "$base/cart/add" -Method Post -Body @{ dishId = 7; quantity = 99999 } `
        -WebSession $s3 -UseBasicParsing -MaximumRedirection 5 | Out-Null
    $cartPage3 = Invoke-WebRequest -Uri "$base/cart" -WebSession $s3 -UseBasicParsing
    Check '超库存被拒绝（购物车仍为空）' ($cartPage3.Content -like '*购物车还是空的*')

    $stockAfter = [int](Sql 'SELECT stock FROM wechat_ordering.dish WHERE id = 7')
    Check "数据库库存未受影响（$stockBefore → $stockAfter）" ($stockBefore -eq $stockAfter)

    Invoke-WebRequest -Uri "$base/cart/add" -Method Post -Body @{ dishId = 7; quantity = 2 } `
        -WebSession $s3 -UseBasicParsing -MaximumRedirection 5 | Out-Null
    $cartPage4 = Invoke-WebRequest -Uri "$base/cart" -WebSession $s3 -UseBasicParsing
    Check '正常数量 2 可以加购' ($cartPage4.Content -like '*酸辣土豆丝*')
} catch {
    Check '[8] 数量校验' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [9] 取消订单
# ---------------------------------------------------------------
Write-Host '[9] 取消订单 → 数据库状态变成已取消(5)' -ForegroundColor Yellow
try {
    Invoke-WebRequest -Uri "$base/order/$orderId/cancel" -Method Post `
        -WebSession $session -UseBasicParsing -MaximumRedirection 5 | Out-Null
    $st = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $orderId").Trim()
    Check "数据库里状态 = 5（实际 $st）" ($st -eq '5')
} catch {
    Check '[9] 取消订单' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [10] 状态机
# ---------------------------------------------------------------
Write-Host '[10] ★ 状态机：已取消是终态，不能再改成制作中' -ForegroundColor Yellow
try {
    # ★ 加鉴权之后，改订单状态属于管理操作，必须先登录成管理员。
    #   否则请求会被重定向到登录页，状态根本改不了 —— 这正是我们想要的行为。
    Invoke-WebRequest -Uri "$base/admin/login" -Method Post `
        -Body @{ username = 'admin'; password = 'admin123' } `
        -WebSession $session -UseBasicParsing | Out-Null

    $resp = Invoke-WebRequest -Uri "$base/order/$orderId/status" -Method Post -Body @{ status = 2 } `
        -WebSession $session -UseBasicParsing -MaximumRedirection 5
    $st = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $orderId").Trim()
    Check "状态仍是 5，非法迁移被拒绝（实际 $st）" ($st -eq '5')
    Check '页面给出了失败提示' (($resp.Content -like '*不被允许*') -or ($resp.Content -like '*失败*'))
} catch {
    Check '[10] 状态机拒绝非法迁移' $false $_.Exception.Message
}

Write-Host '[11] 状态机：非法状态值（99）应该被拒绝' -ForegroundColor Yellow
try {
    Invoke-WebRequest -Uri "$base/order/$orderId/status" -Method Post -Body @{ status = 99 } `
        -WebSession $session -UseBasicParsing -MaximumRedirection 5 | Out-Null
    $st = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $orderId").Trim()
    Check "非法值 99 没写进数据库（实际 $st）" ($st -eq '5')
} catch {
    Check '[11] 非法状态值' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [12] 越权取消
# ---------------------------------------------------------------
Write-Host '[12] ★ 越权：换一个 session 去取消别人的订单，应该失败' -ForegroundColor Yellow
try {
    $sOther = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-WebRequest -Uri "$base/cart/add" -Method Post -Body @{ dishId = 9; quantity = 1 } `
        -WebSession $sOther -UseBasicParsing | Out-Null
    Invoke-WebRequest -Uri "$base/order/create" -Method Post `
        -Body @{ userName = '别人'; userPhone = '13900139000'; userAddress = '越权测试' } `
        -WebSession $sOther -UseBasicParsing -MaximumRedirection 5 | Out-Null
    $otherOrder = (Sql 'SELECT id FROM wechat_ordering.orders ORDER BY id DESC LIMIT 1').Trim()

    $sAttacker = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-WebRequest -Uri "$base/order/$otherOrder/cancel" -Method Post `
        -WebSession $sAttacker -UseBasicParsing -MaximumRedirection 5 | Out-Null

    $st = (Sql "SELECT status FROM wechat_ordering.orders WHERE id = $otherOrder").Trim()
    Check "别人的订单没被取消（仍是 0 待确认，实际 $st）" ($st -eq '0')

    Sql "DELETE FROM wechat_ordering.orders WHERE id = $otherOrder" | Out-Null
    Sql 'UPDATE wechat_ordering.dish SET stock = 150 WHERE id = 9' | Out-Null
} catch {
    Check '[12] 越权取消' $false $_.Exception.Message
}

# ---------------------------------------------------------------
# [13] 清理测试数据
# ---------------------------------------------------------------
Write-Host '[13] 清理本次验证产生的测试数据' -ForegroundColor Yellow
try {
    if ($orderId) { Sql "DELETE FROM wechat_ordering.orders WHERE id = $orderId" | Out-Null }
    Sql 'UPDATE wechat_ordering.dish SET stock = 80 WHERE id = 3' | Out-Null   # 红烧肉
    Sql 'UPDATE wechat_ordering.dish SET stock = 100 WHERE id = 5' | Out-Null  # 鱼香肉丝
    Sql 'UPDATE wechat_ordering.dish SET stock = 120 WHERE id = 7' | Out-Null  # 酸辣土豆丝
    $orders = [int](Sql 'SELECT COUNT(*) FROM wechat_ordering.orders')
    Check "清理完成（剩余 $orders 笔订单）" ($orders -ge 0)
} catch {
    Check '[13] 清理' $false $_.Exception.Message
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
