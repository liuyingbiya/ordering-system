# 点餐系统 · Spring Boot 版

> 从原生 Servlet + JSP + JDBC 迁移到 Spring Boot + Thymeleaf + MyBatis + MySQL。
> 原项目保留在 `C:\Users\影和\Desktop\新建文件夹 (2)\新建文件夹\WeChatOrdering` 和本工作区的 `原项目-副本-只读参考/`（只读），**不要改动**。

---

## 一、这是什么

一个 Web 点餐应用，业务闭环：**浏览菜品 → 加入购物车 → 结算下单 → 查看订单 → 管理订单状态**。

| | 老版本 | 本版本 |
|---|---|---|
| 框架 | 原生 Servlet/JSP | **Spring Boot 2.7.18** |
| 启动方式 | Jetty 插件 | `java -jar` |
| 页面 | JSP（9 个） | **Thymeleaf**（8 个业务页 + 2 个错误页） |
| 请求处理 | 4 个 Servlet + `?action=` 分派 | **5 个 Controller + RESTful URL** |
| 业务逻辑 | 全写在 Servlet 里 | **3 个 Service 层** |
| 数据访问 | 手写 JDBC（**且从未被调用**） | **MyBatis Mapper，真正读写 MySQL** |
| 订单存储 | 内存 ArrayList，重启即丢 | **MySQL `orders` + `order_item` 表** |
| 库存 | 从来不扣，可以无限下单 | **下单扣库存，防超卖，失败自动回滚** |
| 数据源数量 | **5 套硬编码，内容还不一致**（bug 根源） | **1 套：MySQL** |
| 异常处理 | 打印堆栈了事 | **全局异常处理，友好错误页** |

---

## 二、怎么跑起来

### 环境要求

| 项 | 要求 | 这台机器实际 |
|---|---|---|
| JDK | 8 或以上 | `E:\envriorment\java` = 1.8.0_301 ✅ |
| Maven | 3.6+ | `E:\envriorment\maven\apache-maven-3.8.4` ✅ |
| MySQL | 8.0（**必须启动**，现在离了数据库页面直接 500） | MySQL80 服务运行中 ✅（root / root） |

### 第一次跑：先建库

```powershell
Get-Content .\build-database.sql -Raw -Encoding utf8 |
  & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' -uroot -proot --default-character-set=utf8mb4
```

> ⚠️ **不要用** `原项目-副本-只读参考/database/init.sql`！那份**少了 `icon` 列**，
> 导进去以后所有菜品查询都会报 `Unknown column 'icon'`，首页直接 500。
> 请只用本目录下的 `build-database.sql`（已包含 icon 列和 20 条数据）。

验证：

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' -uroot -proot -e "USE wechat_ordering; SHOW TABLES; SELECT COUNT(*) FROM dish;"
```
预期：3 张表，20 条菜品。

### 启动

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\run.ps1
```

然后浏览器打开 <http://localhost:8080>。

### 一键验证（四套，都要跑）

```powershell
# 阶段 1：接口、页面渲染、HTTP 语义 —— 40 项
powershell -NoProfile -ExecutionPolicy Bypass -File .\verify.ps1

# 阶段 2/3：MyBatis、数据库、事务回滚、状态机、越权 —— 32 项
powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-db.ps1

# 管理端鉴权：未登录拦截、错误密码、退出、顾客端不误拦 —— 22 项
powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-auth.ps1

# CSRF：跨站伪造被 403 拦截、同源放行、403 页面正确渲染 —— 16 项
powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-csrf.ps1
```

当前状态：**40 + 32 + 22 + 16 = 110 项断言全部通过**。

几个「铁证」级别的断言：
- `verify-db` **[2]** 直接在数据库里把菜名改掉，页面跟着变 → 证明数据来自 MySQL，不是内存
- `verify-db` **[7]** 加购时库存够、下单时被抢光 → 验证事务回滚，库存没扣坏、订单没留脏数据
- `verify-db` **[8]** 传 -100 / 0 / 99999 加购 → 全被拒绝，库存不受影响
- `verify-auth` **[5]** 未登录去改订单状态 → 数据库里的状态没变
- `verify-csrf` **[1]** 已登录管理员被跨站页面诱导改状态 → 403，且**验证 403 页面是我们自己的、不是 Whitelabel 白页**

### 跑完想恢复干净数据

```powershell
Get-Content .\reset-data.sql -Raw -Encoding utf8 |
  & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' -uroot -proot --default-character-set=utf8mb4
```

### 在 IntelliJ IDEA 里运行

1. `File → Open`，选择本目录
2. 等 Maven 下载完依赖
3. 打开 `src/main/java/com/ordering/OrderingApplication.java`，点 `main` 左边的绿三角

---

## 三、数据库

共 **3 张表**（自包含建库脚本：`build-database.sql`）：

| 表 | 用途 | 关键设计 |
|---|---|---|
| `dish` | 菜品 | 软删除：`active = false` 表示下架；`icon` 存页面图标 |
| `orders` | 订单主表 | `order_number` 有 **UNIQUE** 约束（防撞号产生重复订单） |
| `order_item` | 订单明细 | 外键 `order_id`，`ON DELETE CASCADE`；**冗余存菜名和单价**（保留下单时的快照） |

> **简历口径纠正**：之前写的是「5 张表」，实际是 **3 张**。
> 购物车（Cart / CartItem）是**存在 session 里的 Java 对象**，没有建表。
> 这点面试被追问会很尴尬，已在简历里改掉。

---

## 四、技术要点（面试会问的，都在代码注释里展开讲了）

### 4.1 MyBatis 层

| 要点 | 在哪儿 |
|---|---|
| Mapper 接口没有实现类，靠 namespace + id 对应 | `mapper/DishMapper.java` 类注释 |
| `#{}` vs `${}`：预编译防注入 vs 字符串拼接 | `mapper/DishMapper.xml` `findActiveByCategory` 上方 |
| `useGeneratedKeys` 插入后回填自增主键 | `mapper/OrderMapper.xml` `insert` 上方 |
| `<foreach>` 批量插入（一条 SQL 插多行） | `mapper/OrderMapper.xml` `insertItems` |
| `<collection>` 嵌套查询 + **N+1 问题** | `mapper/OrderMapper.xml` `OrderMap` 上方 |
| `map-underscore-to-camel-case` 自动映射 | `application.properties` |

### 4.2 事务

| 要点 | 在哪儿 |
|---|---|
| `@Transactional(rollbackFor = Exception.class)` 保证订单+明细+库存原子性 | `OrderService.createOrder` |
| 事务失效的三个场景（非 public / 自调用 / 异常被吞） | `OrderService` 类注释 |
| **事务范围不含 session 里的购物车**（精确表述，别夸大） | 同上 |

### 4.3 防超卖

`DishMapper.xml` 的 `decreaseStock`：
```sql
UPDATE dish SET stock = stock - #{quantity}
WHERE id = #{id} AND stock >= #{quantity}
```
一条带条件的 UPDATE，让「判断库存够不够」和「扣减」在数据库里原子完成，
避免「先查后改」的竞态条件。`OrderService` 发现返回 0 就抛异常 → 事务回滚。

### 4.4 安全与健壮性（审查后补的）

| 问题 | 修法 | 在哪儿 |
|---|---|---|
| **负数量能刷库存、把订单金额做成负数** | 服务端校验 `quantity >= 1` 且不超库存 | `CartService.addToCart` |
| **开放重定向**（Referer 可控，能被跳到外站） | 只放行站内相对路径的白名单 | `CartController.safeInternalPath` |
| **越权取消订单**（IDOR，能取消别人的订单） | 取消时校验 `order.userId == 当前用户` | `OrderService.cancelOrder` |
| **订单状态可任意跳转**（已完成的能改回已确认） | 加状态机，只允许合法迁移 | `OrderService.isValidTransition` |
| **非法状态值**（传 -1 / 99 直接写库） | 校验 `status ∈ [0, 5]` | 同上 |
| **`/order/abc` 报 Whitelabel 白页** | 全局异常处理 → 友好 404 页 | `controller/GlobalExceptionHandler` |
| **查不到资源返回 302 而非 404** | 设置 404 状态码 + 渲染兜底模板 | `OrderController.detail` |
| **手机号只在前端校验**（改 HTML 就绕过） | 服务端正则校验 + 长度对齐数据库列 | `OrderController.validateReceiver` |
| **校验失败要重填全部内容** | 用 `th:value` 回填 | `checkout.html` |

---

## 五、接口一览（和老地址的对照）

| 方法 | 新地址 | 老地址 | 说明 |
|---|---|---|---|
| GET | `/` `/index` | `/` `/index` | 首页 |
| GET | `/dish` | `/dish?action=list` | 菜品列表 |
| GET | `/dish?category=主食` | `/dish?action=category&category=主食` | 分类筛选 |
| GET | `/dish/{id}` | `/dish?action=detail&id=3` | 菜品详情 |
| GET | `/cart` | `/cart?action=view` | 看购物车 |
| POST | `/cart/add` | `/cart?action=add`（GET！） | **加入购物车** |
| POST | `/cart/update` | `/cart?action=update`（GET！） | 改数量 |
| POST | `/cart/remove` | `/cart?action=remove`（GET！） | 移除某项 |
| POST | `/cart/clear` | `/cart?action=clear`（GET！） | 清空 |
| GET | `/order` | `/order?action=list` | 全部订单（⚠️ 无鉴权，见已知问题） |
| GET | `/order/my` | `/order?action=myorders` | 我的订单 |
| GET | `/order/{id}` | `/order?action=detail&id=5` | 订单详情 |
| GET | `/order/checkout` | `/order?action=create`（GET） | 结算页 |
| POST | `/order/create` | `/order?action=create`（POST） | **提交订单** |
| POST | `/order/{id}/cancel` | `/order?action=cancel`（GET！） | 取消订单（**已加归属校验**） |
| POST | `/order/{id}/status` | `/order?action=updateStatus` | 改状态（**已加状态机**） |

**标了「GET！」的都是老项目里用 GET 做写操作的语义错误，已全部改为 POST。**

---

## 六、目录结构

```
点餐系统-SpringBoot/
├── pom.xml                      # jar 打包 + spring-boot-maven-plugin + mybatis starter
├── run.ps1                      # 一键启动（需 -ExecutionPolicy Bypass）
├── verify.ps1                   # 阶段1 验证（40 项）
├── verify-db.ps1                # 阶段2/3 验证（32 项）
├── verify-auth.ps1              # 管理端鉴权验证（22 项）
├── verify-csrf.ps1              # CSRF 防护验证（16 项）
├── build-database.sql           # ★ 自包含建库脚本（含 icon 列），只用这个
├── reset-data.sql               # 清空测试订单、恢复库存
├── Dockerfile                   # 多阶段构建镜像
├── docker-compose.yml           # 一条命令起「应用 + MySQL」
├── .dockerignore
│
├── README.md                    # 本文件
├── 阶段1-讲解.md                 # ★ 阶段 1 学习文档（Spring Boot 骨架）
├── 阶段2-讲解.md                 # ★ 阶段 2/3 学习文档（MyBatis + 事务 + 防超卖）
├── 阶段3-讲解.md                 # ★ 阶段 4 学习文档（登录鉴权 + CSRF）
├── 面试话术卡.md                 # ★★ 面试前 10 分钟看这个
├── 部署指南.md                   # 四种部署方案 + 故障排查
│
├── .mvn/
│   ├── maven.config             # 让 Maven 自动用下面的 settings
│   └── settings.xml             # 阿里云镜像 + 项目内本地仓库
└── src/main/
    ├── java/com/ordering/
    │   ├── OrderingApplication.java   # 启动入口（含 @MapperScan）
    │   ├── config/
    │   │   ├── AdminInterceptor.java  # ★ 管理端登录拦截器
    │   │   ├── CsrfInterceptor.java   # ★ CSRF：Origin/Referer 同源校验
    │   │   ├── CsrfException.java
    │   │   └── WebConfig.java         # 注册拦截器，精确指定拦截路径与顺序
    │   ├── controller/
    │   │   ├── HomeController.java
    │   │   ├── DishController.java
    │   │   ├── CartController.java    # 含开放重定向修复
    │   │   ├── OrderController.java   # 含收货信息校验
    │   │   ├── AdminController.java   # ★ 管理员登录/退出
    │   │   ├── GlobalModelAdvice.java # 全局注入购物车角标
    │   │   └── GlobalExceptionHandler.java  # 全局异常处理
    │   ├── service/
    │   │   ├── DishService.java       # 菜品（从 DB 查）
    │   │   ├── CartService.java       # 购物车（session）+ 数量校验
    │   │   └── OrderService.java      # 订单（事务 + 状态机 + 归属校验）
    │   ├── mapper/
    │   │   ├── DishMapper.java
    │   │   └── OrderMapper.java
    │   ├── model/                     # 5 个实体
    │   └── util/OrderNumberGenerator.java  # 线程安全版
    └── resources/
        ├── application.properties     # 数据源 + 连接池 + MyBatis + 管理员账号
        ├── mapper/
        │   ├── DishMapper.xml
        │   └── OrderMapper.xml
        └── templates/                 # 8 个业务页 + admin-login + error-403/404/500
```

---

## 七、部署

**四种方案的完整说明（含成本对比、systemd 配置、故障排查）见 [部署指南.md](部署指南.md)。**

最短路径：

```powershell
# 方式一：本地跑（开发 / 面试演示）
powershell -NoProfile -ExecutionPolicy Bypass -File .\run.ps1

# 方式二：Docker（装好 Docker 之后，一条命令起「应用 + MySQL」）
docker compose up --build
```

> ⚠️ **GitHub Pages 部署不了这个项目** —— Pages 只能托管静态文件，
> Spring Boot 需要 JVM 进程在跑。之前那个 `my-first-project` 能上 Pages，
> 是因为它是纯静态页。
>
> 想让人看到这个项目，正确做法是：**代码推 GitHub**（让人读源码）+
> **内网穿透做临时演示**（cpolar / ngrok）。详见部署指南。

---

## 八、安全与鉴权

### 8.1 管理端鉴权

#### 为什么要加

加之前，任何人在浏览器打开 `http://localhost:8080/order` 就能看到
**所有顾客的姓名、电话、送餐地址**，还能改任意订单的状态。
这不是「功能没做完」，是**个人信息泄露**。

### 怎么实现的

**没有用 Spring Security**，用的是 `HandlerInterceptor`（手写拦截器）。原因：只有一个管理员角色，不必要引入完整权限体系。

| 哪一步 | 在哪 |
|---|---|
| 登录页 | `templates/admin-login.html` → `/admin/login` |
| 登录/退出逻辑 | `controller/AdminController.java` |
| 拦截器 | `config/AdminInterceptor.java` |
| 拦截器注册 | `config/WebConfig.java` |

拦截范围**精确到两个路径**：

```java
registry.addInterceptor(adminInterceptor)
        .addPathPatterns("/order", "/order/")     // 管理端订单列表
        .addPathPatterns("/order/*/status")       // 管理端改状态
        .excludePathPatterns("/admin/**");        // 排除登录页，否则死循环
```

⚠️ **不能图省事写 `/order/**`** —— 那会把「我的订单」「订单详情」也拦掉，顾客就看不了自己的订单了。

### 演示账号

```
用户名：admin
密码：  admin123
```
在 `application.properties` 的 `app.admin.username` / `app.admin.password` 里改。
**部署到公网前必须改掉。**

### 这个实现的边界（面试要如实说）

| 做到了 | 没做（生产必需） |
|---|---|
| 管理端需要登录才能访问 | 密码是**明文**比较（应存 BCrypt 哈希） |
| 未登录改状态会被拦 | 登录成功**没换 sessionId**（会话固定攻击） |
| 登录失败计数（内存，5 次上限） | 没有「记住我」、没有密码找回 |
| 登录后跳回原页面（防开放重定向） | CSRF 用 Origin 校验而非 Token（见 8.2） |

> 面试问「你的登录安全吗」，标准答法：
> 「我做了最小可用版本：拦截器 + session 标记，登录后跳回原页面且做了站内白名单。
> 但我很清楚它不是生产级——密码明文存配置、没换 sessionId。
> 真实项目应该用 Spring Security，它有 BCrypt、CSRF Token、会话固定防护这些都是开箱的。」

---

### 8.2 CSRF 防护

#### 为什么加了登录之后才必须做

CSRF（跨站请求伪造）的本质：**攻击者不需要偷你的 cookie，
他只要让你的浏览器「带着 cookie 去请求你的站点」就够了。**

```html
<!-- 你（管理员）点开的恶意网页里有这么一段 -->
<form action="http://你的站点/order/5/status" method="post">
    <input name="status" value="4">
</form>
<script>document.forms[0].submit()</script>
```

浏览器提交时**自动带上你在本站的 cookie**，服务器看到 cookie 有效就执行了。
没有登录态的时候危害有限（没有身份可冒用）；**有了登录态，这就是真漏洞**。

#### 三种做法，本项目选了第二种

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **CSRF Token** | 服务端发随机 token，表单带回，服务端比对 | 最严格，业界标准 | **每个表单都要改**（本项目 14 个），漏一个就一个洞 |
| **Origin / Referer 校验** ← 采用 | 校验写请求的来源是不是本站 | **零表单改动**，拦截点集中 | 比 Token 略弱；挡不住同源 XSS |
| SameSite Cookie | 给 cookie 加 `SameSite=Lax` | 简单 | 靠浏览器配合，不是服务端自查 |

**为什么选 Origin 校验**：14 个表单逐个加 token，改动面大且容易漏。
一个拦截器集中处理更不容易出错。**面试如实说：知道 Token 更标准，
选 Origin 是因为改动面小；真实项目应该用框架内置的 Token 方案。**

#### 实现

| 组件 | 作用 |
|---|---|
| `config/CsrfInterceptor.java` | 校验 POST/PUT/DELETE/PATCH 的 Origin/Referer |
| `config/CsrfException.java` | 来源不可信时抛出，交给全局异常处理 |
| `GlobalExceptionHandler` | 映射成 **403** + `error-403.html` |
| `WebConfig` | 注册拦截器（**先于**登录校验执行） |

**校验规则**：

| 情况 | 处理 | 理由 |
|---|---|---|
| GET / HEAD / OPTIONS | 放行 | 只读，无危害 |
| `Origin` = 本站 | 放行 | 同源 |
| `Origin` = 外站 | **403 拦截** | 跨站伪造 |
| `Origin` = `null` | **403 拦截** | 沙箱 iframe / `file://`，不可信 |
| `Origin` 缺失、`Referer` 是本站 | 放行 | 回退校验 |
| `Origin` 缺失、`Referer` 是外站 | **403 拦截** | — |
| 两个头都缺失 | 放行 | 非浏览器客户端（curl/Postman），不会自动带 cookie，不构成 CSRF 面 |

⚠️ **「两个头都缺失就放行」是有前提的取舍**：
浏览器发起跨站请求时**一定**会带 `Origin`（现代浏览器对 POST 一律带）。
这条前提在 2020 年后的浏览器上成立。如果不放行，你自己用 Postman 调接口
和跑测试脚本会全被挡，那不是真实攻击场景。

⚠️ **必须排除 `/error`**：异常后容器会 forward 到 `/error`，拦截器再拦一次会递归。

#### 同源是怎么判断的

比对 **协议 + 域名 + 端口** 三者，任一项不同就算跨站。
所以 `http://localhost:9999` 和 `http://localhost:8080` **不是同源**——
只比域名会被「同域名不同端口」绕过。这就是浏览器的**同源策略**。

---

## 九、已知问题 / 后续阶段

| # | 问题 | 严重度 | 计划 |
|---|---|---|---|
| ~~1~~ | ~~管理端 `/order` 和 `/{id}/status` 完全没有鉴权~~ | ~~高~~ | ✅ **已修复**：session 登录 + `AdminInterceptor`，见 8.1 |
| ~~2~~ | ~~没有 CSRF 防护~~ | ~~中高~~ | ✅ **已修复**：Origin/Referer 校验 + 403 页面，见 8.2 |
| 3 | 管理员密码**明文存在配置文件里**，登录成功**没有更换 sessionId**（会话固定攻击风险） | 中 | 生产环境必须换 BCrypt + 登录后 rotate session |
| 4 | 购物车在 session（`ArrayList`），同一 session 的并发请求可能有 `ConcurrentModificationException`；重启即丢 | 中 | 阶段 5（可选做第 4 张表） |
| 5 | 订单明细用 `<collection>` 嵌套查询，存在 **N+1 查询**问题 | 中 | 改 JOIN + 嵌套结果映射 |
| 6 | 除主键外没有建索引，`orders.user_id`、`dish.category` 查询会全表扫描 | 中 | 按实际查询加索引 |
| 7 | 取消订单**不恢复库存** | 低 | 业务决策，需先定规则 |
| 8 | `OrderNumberGenerator.formatPrice` / `formatDate` 是死代码 | 低 | 用不到就删 |
| 9 | `DishService.decreaseStock` 没有调用者（`OrderService` 直接用了 Mapper） | 低 | 要么删，要么统一走 Service |
| 10 | 订单状态 CSS class 用了中文（`status-待确认`） | 低 | 改成 `status-0/1/2` 数字 |
| 11 | 分类写死在 `DishService.getCategories()` | 低 | 可改为查 `DISTINCT category`（有取舍，注释里写了） |

> 💡 **面试时这张表是你的资产，不是负担。**
> 被问「项目有什么不足」，从这里挑 3 条，每条都说清「影响 + 怎么改」。
> 具体话术见 [面试话术卡.md](面试话术卡.md) 第 5 节。

---

## 十、踩过的坑（留档，别重复踩）

1. **`mysql:mysql-connector-java` 必须写 `<version>`**
   Spring Boot 2.7 父 POM 管的是新坐标 `com.mysql:mysql-connector-j`，
   老坐标不在管理列表，不写版本报 `dependency.version is missing`。

2. **中文用户名 → Maven 本地仓库必须换路径**
   现象：`...\spring-boot-starter-thymeleaf-2.7.18.pom.part.lock (系统找不到指定的路径)`
   原因：用户名是中文（`影和`），Maven 处理 `C:\Users\影和\.m2\repository` 时编码出错。
   解决：`.mvn/settings.xml` 里把 `localRepository` 指到项目内的 ASCII 路径。

3. **`${session.rootDirectory}` 在 settings.xml 里不会被替换！**
   settings.xml 是**在 Maven 构建会话建立之前**读取的，那时 session 属性还不存在。
   写了占位符的结果是：Maven 真的在项目里建了一个**名字叫 `${session.rootDirectory}` 的目录**，
   依赖全下到那里去了，而 `.mvn-repo` 里只有一部分。
   **必须写绝对路径。**

4. **`--defaults-extra-file` 在这台机器上对 MySQL 无效**
   导入 SQL 请用管道：`Get-Content x.sql -Raw | mysql -uroot -p...`

5. **PowerShell 执行策略会拦住 .ps1**
   用 `-ExecutionPolicy Bypass`。另外本目录所有 `.ps1` 都存成 **UTF-8 带 BOM**，
   因为 Windows PowerShell 5.1 读无 BOM 的 UTF-8 会按 GBK 解析，中文全乱码。

6. **`web_fetch` 不允许访问 localhost**
   验证本机服务只能用 PowerShell 的 `Invoke-WebRequest`，见 verify 脚本。

7. **`Invoke-WebRequest` 拿不到 4xx 的响应体**
   PowerShell 5.1 遇到 403 会抛异常，而 `$_.ErrorDetails.Message` 在这个场景是空的。
   想读 4xx 页面内容，得用 .NET 的 `HttpWebRequest` 自己读响应流
   （见 `verify-csrf.ps1` 里的 `RawPost` 函数）。

8. **CSRF 拦截器必须排除 `/error`**
   异常后容器会 forward 到 `/error`，那又是一次请求分发，
   拦截器再拦一次、再抛异常……会递归。

---

## 十一、学完之后你应该能讲出来的话

- 「我把一个 Servlet/JSP 项目迁移到了 Spring Boot，用的是 Controller + Service + Mapper 分层。」
- 「原项目有 5 处硬编码的菜品数据，首页那套和菜单页那套还不一致，导致加购物车会串菜；我统一成单一数据源（MySQL）后彻底消除了。」
- 「原项目的加购物车是 GET 请求，违反幂等语义，会被浏览器预抓取误触发；我改成 POST 并改成 RESTful 路由。」
- 「下单我用 `@Transactional` 保证订单、明细、库存三处数据库写入的原子性，并配合 `WHERE stock >= n` 的条件更新防止超卖。」
- 「我做了全局异常处理，把参数类型错误映射成 404，并且不在错误页暴露堆栈。」
- 「管理端我用 HandlerInterceptor 做了登录拦截，拦截路径精确到接口级，不影响顾客端；不是用 Spring Security，因为只有一个角色。」
- 「CSRF 我用 Origin/Referer 校验，比对协议+域名+端口是否同源；选它而不是 Token 是因为改动面小，但我知道 Token 更标准。」
- 「上面这些我都写了端到端验证脚本，一共 110 项断言，包括『未登录改状态后直接查数据库确认没变』。」

> 📌 **上面这些只是骨架。完整的面试说法（30 秒版 / 2 分钟版 / 14 个追问 / 缺点怎么答 / 红线清单）
> 全部在 [面试话术卡.md](面试话术卡.md) 里，面试前务必过一遍。**
