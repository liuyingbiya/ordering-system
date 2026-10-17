package com.ordering.controller;

import com.ordering.model.Cart;
import com.ordering.model.Order;
import com.ordering.service.CartService;
import com.ordering.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.List;

/**
 * 订单 Controller —— 对应原来的 OrderServlet。
 *
 * 原来的 OrderServlet 是 4 个 Servlet 里最复杂的一个（243 行），
 * 用了两层 switch（doGet 里一个、doPost 里一个），处理 6 种 action。
 * 现在拆成 7 个小方法，每个方法只做一件事。
 *
 * 注意本类的 url 设计：
 *   GET  /order              看全部订单（管理端）
 *   GET  /order/my           看我的订单
 *   GET  /order/{id}         看订单详情
 *   GET  /order/checkout     结算页
 *   POST /order/create       提交订单
 *   POST /order/{id}/cancel  取消订单
 *   POST /order/{id}/status  改状态
 *
 * 一眼就能看出哪个是读、哪个是写，也一眼能看出操作的是哪个订单。
 * 老地址 /order?action=detail&id=5 就得读完参数才知道要干嘛。
 */
@Controller
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private CartService cartService;

    /** 全部订单。老地址：/order?action=list */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("orders", orderService.getAllOrders());
        return "order-list";
    }

    /**
     * 我的订单。老地址：/order?action=myorders
     *
     * ⚠️ 注意这里的路由顺序问题（重要的坑）：
     * 本方法映射 "/my"，下面详情方法映射 "/{id}"。
     * Spring 会优先匹配更「具体」的路径，所以 /order/my 会进这里而不是被当成 id="my"。
     * 如果你写成 /order/{id} 在前、/my 在后，Spring 依然能正确区分（它按具体度排序），
     * 但你自己写代码时要知道这个规则，否则容易踩坑。
     */
    @GetMapping("/my")
    public String myOrders(HttpSession session, Model model) {
        String userId = cartService.getOrCreateUserId(session);
        List<Order> orders = orderService.getOrdersByUserId(userId);
        model.addAttribute("orders", orders);
        return "my-orders";
    }

    /** 结算页：确认收货信息、提交订单前的最后一步。老地址：/order?action=create（GET） */
    @GetMapping("/checkout")
    public String checkout(HttpSession session, Model model) {
        Cart cart = cartService.getOrCreateCart(session);
        if (cart.getItems().isEmpty()) {
            // 购物车空了不该进结算页，回购物车
            return "redirect:/cart";
        }
        model.addAttribute("cart", cart);
        return "checkout";
    }

    /**
     * 提交订单。老地址：/order?action=create（POST）
     *
     * isBlank() 是 Java 11 的方法，这里项目是 Java 8，所以用 trim().isEmpty()。
     * 校验逻辑和老代码一致：收货人/电话/地址任一为空，就退回结算页重新填。
     */
    @PostMapping("/create")
    public String create(@RequestParam("userName") String userName,
                         @RequestParam("userPhone") String userPhone,
                         @RequestParam("userAddress") String userAddress,
                         HttpSession session,
                         Model model,
                         RedirectAttributes redirectAttributes) {

        Cart cart = cartService.getOrCreateCart(session);
        if (cart.getItems().isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "购物车为空");
            return "redirect:/cart";
        }

        /*
         * 校验三步。注意：这【不是】页面校验的重复劳动 ——
         * checkout.html 里的 required / pattern="[0-9]{11}" 只在浏览器端生效，
         * 用 Postman 直接 POST，或者在浏览器里改一下 HTML，就全绕过了。
         * 所有前端校验都不可信，服务端必须再校验一遍。这是 Web 安全的基本原则。
         *
         * 退回结算页时要【把用户填过的内容带回模板】（addAttribute 那三行），
         * 否则用户填了一半被打回来，得重打一遍，体验很差。
         */
        String error = validateReceiver(userName, userPhone, userAddress);
        if (error != null) {
            model.addAttribute("cart", cart);
            model.addAttribute("error", error);
            // 回填：把用户已填的内容带回去
            model.addAttribute("userName", userName);
            model.addAttribute("userPhone", userPhone);
            model.addAttribute("userAddress", userAddress);
            return "checkout";
        }

        String userId = cartService.getOrCreateUserId(session);

        // ★ 阶段 3 新增：处理库存不足。
        // OrderService.createOrder() 里如果扣库存失败会抛 IllegalStateException，
        // @Transactional 会把事务回滚（订单主表也不会留下脏数据）。
        // 但异常会一路抛到这里，如果不接，用户看到的是 Spring 默认的 500 错误页。
        // 所以这里接住它，转成一句人话 + 退回结算页，让用户改数量。
        Order order;
        try {
            order = orderService.createOrder(cart, userId, userName, userPhone, userAddress);
        } catch (IllegalStateException e) {
            model.addAttribute("cart", cart);
            model.addAttribute("error", e.getMessage() + "，请调整数量后重试");
            model.addAttribute("userName", userName);
            model.addAttribute("userPhone", userPhone);
            model.addAttribute("userAddress", userAddress);
            return "checkout";
        } catch (Exception e) {
            // 比如订单号撞了 UNIQUE 约束、数据库连接断了等
            model.addAttribute("cart", cart);
            model.addAttribute("error", "下单失败，请稍后重试");
            model.addAttribute("userName", userName);
            model.addAttribute("userPhone", userPhone);
            model.addAttribute("userAddress", userAddress);
            return "checkout";
        }

        // 下单成功后清空购物车
        cartService.clearCart(session);

        redirectAttributes.addFlashAttribute("message",
                "订单提交成功，订单号：" + order.getOrderNumber());
        return "redirect:/order/" + order.getId();
    }

    /**
     * 订单详情。新地址：/order/5
     *
     * ★ 改动：查不到时返回 404 状态码，而不是 302 重定向回列表。
     *   原来写 return "redirect:/order"，有两个问题：
     *     1. 语义上「资源不存在」应该是 404，不是 302
     *     2. order-detail.html 里那段 th:if="${order == null}" 的兜底分支永远执行不到（死代码）
     *   现在改成 setStatus(404) 但依然渲染模板，兜底分支就真的能用了。
     *   （用 @ResponseStatus 注解也行，但那样会同时改变页面显示逻辑，这里用手动设置更可控）
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable("id") Integer id, Model model,
                         HttpServletResponse response) {
        Order order = orderService.getOrderById(id);
        if (order == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "order-detail";
        }
        model.addAttribute("order", order);
        return "order-detail";
    }

    /**
     * 取消订单。老地址：/order?action=cancel&id=5（GET，同样是语义错误，现在改成 POST）
     *
     * ★ 改动：加上归属校验 —— 只能取消自己的订单。
     *   原写法 orderService.cancelOrder(id) 只要订单是「待确认」就允许取消，
     *   任何人猜到一个订单 id 就能替别人取消（IDOR 越权漏洞）。
     *   现在把当前 session 的 userId 传下去比对。
     */
    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable("id") Integer id,
                         HttpSession session,
                         RedirectAttributes redirectAttributes) {
        String currentUserId = cartService.getOrCreateUserId(session);
        boolean ok = orderService.cancelOrder(id, currentUserId);
        redirectAttributes.addFlashAttribute(ok ? "message" : "error",
                ok ? "订单已取消" : "该订单无法取消（只有自己的待确认订单可以取消）");
        return "redirect:/order/my";
    }

    /**
     * 改订单状态（管理端操作）。老地址：/order?action=updateStatus
     *
     * ⚠️ 这里有个【已知缺口】，你要知道并且如实讲：
     *    这个接口没有任何权限校验 —— 没登录的人也能改任意订单状态。
     *    正常做法是加登录 + 角色（只有管理员能调），或者至少加一个管理端口令。
     *
     *    为什么这次没加？因为加登录会牵动所有页面（要区分用户端/管理端），
     *    属于阶段 4 的范围。现在把它记在 README 的「已知问题」里，
     *    面试被问到就如实说「管理端鉴权还没做，这是我知道的缺口」。
     *
     *    ★ 记住一条：简历项目里「知道自己哪里没做、为什么没做」，
     *      比「假装什么都做了」安全得多。
     */
    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable("id") Integer id,
                               @RequestParam("status") Integer status,
                               RedirectAttributes redirectAttributes) {
        boolean ok = orderService.updateStatus(id, status);
        // 失败原因可能是：订单不存在、状态值非法（超出 0-5）、状态迁移不合法（比如已完成的改回已确认）
        redirectAttributes.addFlashAttribute(ok ? "message" : "error",
                ok ? "订单状态已更新" : "状态更新失败：订单不存在，或该状态流转不被允许");
        return "redirect:/order";
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 校验收货信息。返回 null 表示通过，否则返回错误文案。
     *
     * 为什么单独抽一个方法？
     *   1. create() 方法已经够长了，把校验细则挪出来，主流程更清楚
     *   2. 这个规则以后如果想复用（比如做一个「修改收货地址」的功能），直接调用
     *   3. 可以单独给它写单元测试
     *
     * 校验规则：
     *   - 姓名：非空，长度 <= 50（和数据库 VARCHAR(50) 对齐）
     *   - 手机：必须是 11 位数字（和页面的 pattern 一致，但这里是真正生效的那道）
     *   - 地址：非空，长度 <= 255（和数据库 VARCHAR(255) 对齐）
     *
     * ★ 「和数据库字段长度对齐」这一点很重要：
     *   如果不校验长度，超长的内容会在 INSERT 时报
     *   Data too long for column 'user_phone' —— 用户看到的是 500 错误。
     *   提前校验，就能给一句人能看懂的话。
     */
    private String validateReceiver(String userName, String userPhone, String userAddress) {
        if (isBlank(userName)) {
            return "请填写收货人姓名";
        }
        if (userName.trim().length() > 50) {
            return "姓名太长了（最多 50 个字）";
        }
        if (isBlank(userPhone)) {
            return "请填写手机号";
        }
        // 中国大陆手机号：11 位数字。这里做的是「格式」校验，无法校验号码是否真实存在
        if (!userPhone.trim().matches("^1[3-9]\\d{9}$")) {
            return "手机号格式不对，请填 11 位手机号";
        }
        if (isBlank(userAddress)) {
            return "请填写送餐地址";
        }
        if (userAddress.trim().length() > 255) {
            return "地址太长了（最多 255 个字）";
        }
        return null;
    }
}
