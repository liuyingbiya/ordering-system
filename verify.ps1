# ============================================================
#  端到端验证脚本
#  作用：不靠浏览器，直接用 HTTP 请求把每个接口跑一遍，
#        确认页面真的渲染出来了、加购物车真的生效了。
#  用法：.\verify.ps1
# ============================================================

$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
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

Write-Host ''
Write-Host '=== 点餐系统端到端验证 ===' -ForegroundColor Cyan
Write-Host ''

# 同一个 session 才能记住购物车，所以必须用 WebSession
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

# ---------- 1. 首页 ----------
Write-Host '[1] 首页 GET /' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/" -WebSession $session -UseBasicParsing
    Check 'HTTP 200' ($r.StatusCode -eq 200) "实际 $($r.StatusCode)"
    Check '包含菜名「宫保鸡丁」' ($r.Content -like '*宫保鸡丁*')
    Check '包含菜名「提拉米苏」' ($r.Content -like '*提拉米苏*')
    # 先去掉 HTML 注释再检查：模板里的教学注释本身含 'th:' 字样，会误报
    $rendered = $r.Content -replace '(?s)<!--.*?-->', ''
    Check '不含未渲染的 th: 属性' (-not ($rendered -like '*th:text*'))
    Check '不含 JSP 残留 pageContext' (-not ($rendered -like '*pageContext*'))
    # 首页菜品数量：数一下 dish-card
    $cards = ([regex]::Matches($r.Content, 'class="dish-card"')).Count
    Check "菜品卡片数 = 20（实际 $cards）" ($cards -eq 20)
} catch {
    Check '请求首页' $false $_.Exception.Message
}

# ---------- 2. 菜品列表 ----------
Write-Host '[2] 菜单 GET /dish' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/dish" -WebSession $session -UseBasicParsing
    Check 'HTTP 200' ($r.StatusCode -eq 200)
    Check '包含「库存: 」字样' ($r.Content -like '*库存*')
} catch {
    Check '请求 /dish' $false $_.Exception.Message
}

# ---------- 3. 分类筛选 ----------
Write-Host '[3] 分类筛选 GET /dish?category=小吃' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/dish?category=%E5%B0%8F%E5%90%83" -WebSession $session -UseBasicParsing
    Check 'HTTP 200' ($r.StatusCode -eq 200)
    Check '包含「薯条」' ($r.Content -like '*薯条*')
    Check '不包含「宫保鸡丁」（主食不该出现）' (-not ($r.Content -like '*宫保鸡丁*'))
    $cards = ([regex]::Matches($r.Content, 'class="dish-card"')).Count
    Check "只显示小吃的 4 道（实际 $cards）" ($cards -eq 4)
} catch {
    Check '请求分类筛选' $false $_.Exception.Message
}

# ---------- 4. 菜品详情 ----------
Write-Host '[4] 菜品详情 GET /dish/3' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/dish/3" -WebSession $session -UseBasicParsing
    Check 'HTTP 200' ($r.StatusCode -eq 200)
    Check '显示的是「红烧肉」（id=3）' ($r.Content -like '*红烧肉*')
    Check '显示价格 48.00' ($r.Content -like '*48.00*')
} catch {
    Check '请求 /dish/3' $false $_.Exception.Message
}

# ---------- 5. 空的购物车 ----------
Write-Host '[5] 空购物车 GET /cart' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/cart" -WebSession $session -UseBasicParsing
    Check 'HTTP 200' ($r.StatusCode -eq 200)
    Check '显示「购物车还是空的」' ($r.Content -like '*购物车还是空的*')
} catch {
    Check '请求 /cart' $false $_.Exception.Message
}

# ---------- 6. 加购物车（POST）----------
Write-Host '[6] 加入购物车 POST /cart/add' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/cart/add" -Method Post -Body @{ dishId = 3; quantity = 2 } `
        -WebSession $session -UseBasicParsing -MaximumRedirection 5
    Check 'HTTP 200（重定向后）' ($r.StatusCode -eq 200)
} catch {
    Check 'POST /cart/add' $false $_.Exception.Message
}

# ---------- 7. 购物车内容变了 ----------
Write-Host '[7] 加完后 GET /cart' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/cart" -WebSession $session -UseBasicParsing
    Check 'HTTP 200' ($r.StatusCode -eq 200)
    Check '购物车里出现「红烧肉」' ($r.Content -like '*红烧肉*')
    Check '不串菜（不该出现 id=3 以外的菜：麻婆豆腐）' (-not ($r.Content -like '*麻婆豆腐*'))
    Check '数量 2 生效' ($r.Content -like '*>2<*')
    Check '合计 96.00（48 × 2）' ($r.Content -like '*96.00*')
    Check '显示去结算按钮' ($r.Content -like '*去结算*')
} catch {
    Check '请求 /cart' $false $_.Exception.Message
}

# ---------- 8. GET 访问写接口应该被拒绝 ----------
Write-Host '[8] 语义验证：GET /cart/add 应该报 405' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/cart/add?dishId=1" -WebSession $session -UseBasicParsing
    Check 'GET 被拒绝（405）' ($r.StatusCode -eq 405) "实际返回 $($r.StatusCode)"
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    Check 'GET 被拒绝（405 Method Not Allowed）' ($code -eq 405) "实际返回 $code"
}

# ---------- 9. 结算页 ----------
Write-Host '[9] 结算页 GET /order/checkout' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/order/checkout" -WebSession $session -UseBasicParsing
    Check 'HTTP 200' ($r.StatusCode -eq 200)
    Check '包含收货人表单' ($r.Content -like '*userName*')
    Check '显示合计 96.00' ($r.Content -like '*96.00*')
} catch {
    Check '请求结算页' $false $_.Exception.Message
}

# ---------- 10. 下单 ----------
Write-Host '[10] 提交订单 POST /order/create' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/order/create" -Method Post `
        -Body @{ userName = '测试用户'; userPhone = '13800000000'; userAddress = '测试地址 1 号' } `
        -WebSession $session -UseBasicParsing -MaximumRedirection 5
    Check 'HTTP 200（重定向到订单详情）' ($r.StatusCode -eq 200)
    Check '订单详情显示收货人' ($r.Content -like '*测试用户*')
    Check '订单详情显示「待确认」' ($r.Content -like '*待确认*')
    Check '订单详情显示 96.00' ($r.Content -like '*96.00*')
} catch {
    Check 'POST /order/create' $false $_.Exception.Message
}

# ---------- 11. 我的订单 ----------
Write-Host '[11] 我的订单 GET /order/my' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/order/my" -WebSession $session -UseBasicParsing
    Check 'HTTP 200' ($r.StatusCode -eq 200)
    # 注意：my-orders 页设计上只显示订单号/时间/状态/菜品，不显示收货人姓名
    # （收货信息在订单详情页看），所以这里查订单号和菜名
    Check '能看到刚下的订单（订单号）' ($r.Content -like '*订单号*')
    Check '订单里有红烧肉' ($r.Content -like '*红烧肉*')
    Check '状态为待确认' ($r.Content -like '*待确认*')
} catch {
    Check '请求 /order/my' $false $_.Exception.Message
}

# ---------- 12. 下单后购物车清空 ----------
Write-Host '[12] 下单后购物车应为空' -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest -Uri "$base/cart" -WebSession $session -UseBasicParsing
    Check '购物车已清空' ($r.Content -like '*购物车还是空的*')
} catch {
    Check '请求 /cart' $false $_.Exception.Message
}

# ---------- 13. 全部订单 ----------
Write-Host '[13] 订单管理 GET /order（需要管理员登录）' -ForegroundColor Yellow
try {
    # ★ 加鉴权之后，/order 必须先登录。这里先登录再访问。
    #   （专门的鉴权验证在 verify-auth.ps1 里，本脚本只验证「登录后功能正常」）
    Invoke-WebRequest -Uri "$base/admin/login" -Method Post `
        -Body @{ username = 'admin'; password = 'admin123'; redirect = '/order' } `
        -WebSession $session -UseBasicParsing | Out-Null

    $r = Invoke-WebRequest -Uri "$base/order" -WebSession $session -UseBasicParsing
    Check 'HTTP 200' ($r.StatusCode -eq 200)
    Check '是订单管理页面' ($r.Content -like '*订单管理*')
    Check '能看到订单' ($r.Content -like '*测试用户*')
} catch {
    Check '请求 /order' $false $_.Exception.Message
}

# ---------- 汇总 ----------
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
