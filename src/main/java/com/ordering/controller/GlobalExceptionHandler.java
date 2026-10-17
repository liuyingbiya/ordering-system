package com.ordering.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 全局异常处理 —— 把各种异常统一转成人能看的页面，而不是 Spring 默认的白页。
 *
 * ============================ 为什么需要它 ============================
 *
 * 举一个具体的例子：访问 http://localhost:8080/order/abc
 *
 * 我们的 Controller 方法签名是：
 *     public String detail(@PathVariable("id") Integer id, ...)
 * 这里 id 声明成了 Integer，但 URL 里传的是 "abc"，Spring 转换失败，抛
 * MethodArgumentTypeMismatchException。
 *
 * 没有全局处理时，用户看到的是 Spring Boot 的 Whitelabel Error Page：
 *     白底黑字写着「Whitelabel Error Page / This application has no explicit
 *     mapping for /error, so you are seeing this as a fallback.」
 * 还有一段英文堆栈 —— 极不专业。
 *
 * 有了这个类，就变成我们自己的 404 页面。
 *
 * ============================ @ControllerAdvice + @ExceptionHandler ============================
 * 这两个注解组合起来的含义：
 *   「本项目所有 Controller 抛出的异常，都先到这里按类型匹配一下，
 *     匹配到了就用这个方法处理，不再往上抛。」
 *
 * 这正好呼应阶段 1 讲过的 @ControllerAdvice 三种用途：
 *   1. 全局数据（GlobalModelAdvice 的 @ModelAttribute 购物车角标）
 *   2. 全局异常处理（本类）
 *   3. 全局数据绑定（@InitBinder）
 *
 * ============================ 面试可以讲的一个点 ============================
 * 「我加了全局异常处理，把参数类型错误统一映射成 404，
 *  避免用户看到框架默认的报错页，同时也避免把堆栈信息暴露给外部。」
 * 后半句是安全性考虑：错误页里泄露堆栈，等于告诉攻击者你用了什么框架什么版本。
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理「路径参数类型不对」。
     * 触发场景：/order/abc、/dish/xyz
     *
     * 处理方式：返回 404 + 我们自己写的错误页。
     * 为什么不返回 400？严格说「参数格式错误」是 400 Bad Request，
     * 但从用户视角，「你要找的东西不存在」= 404 更符合直觉。
     * 两种都有道理，这里选 404，并且把真实原因写在日志里。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ModelAndView handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        log.warn("参数类型错误: uri={}, 参数={}, 值={}",
                request.getRequestURI(), e.getName(), e.getValue());

        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        ModelAndView mv = new ModelAndView("error-404");
        mv.addObject("message", "请求的地址格式不对："
                + request.getRequestURI());
        return mv;
    }

    /**
     * 处理找不到路由：/abc/def 这种完全没有 Controller 接的地址。
     * （注意：要生效需要开启 spring.mvc.throw-exception-if-no-handler-found，
     *   不过 Spring Boot 默认会走 /error 的 404 页，本来也不会太难看，这里作为兜底。）
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ModelAndView handleNoHandler(NoHandlerFoundException e,
                                        HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        ModelAndView mv = new ModelAndView("error-404");
        mv.addObject("message", "页面不存在：" + e.getRequestURL());
        return mv;
    }

    /**
     * 处理「方法不允许」：比如用 GET 请求一个只接受 POST 的接口。
     *
     * ⚠️ 这个必须显式处理，否则会被最下面的 catch-all 抓走变成 500。
     *    这是我踩过的一个真问题：
     *    加了 @ExceptionHandler(Exception.class) 之后，原本 Spring 会正确返回
     *    405 Method Not Allowed 的场景，被兜底处理器接住、变成了 500。
     *    **兜底处理器会「吃掉」框架本身的行为** —— 加之前一定要想清楚
     *    哪些异常应该让框架自己处理。
     *
     *    正确做法：把状态码明确的框架异常单独接住，保持语义。
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ModelAndView handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e,
            HttpServletRequest request,
            HttpServletResponse response) {
        log.warn("请求方法不支持: uri={}, method={}", request.getRequestURI(), request.getMethod());

        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);   // 405
        ModelAndView mv = new ModelAndView("error-404");
        mv.addObject("message", "该地址不支持 " + request.getMethod()
                + " 请求，请检查请求方式");
        return mv;
    }

    /**
     * 处理 CSRF 拦截（跨站请求伪造）。
     *
     * 返回 403 Forbidden —— 语义上表示「我知道你是谁，但不允许你这么做」，
     * 和 401 Unauthorized（「我还不知道你是谁」）区分开。
     *
     * ⚠️ 注意这个 handler 必须在 Exception.class 兜底之前——
     *    Spring 会按「异常类型最具体」来匹配，跟声明顺序无关，
     *    所以这里不受影响。但你自己读代码时要知道这个规则。
     */
    @ExceptionHandler(com.ordering.config.CsrfException.class)
    public ModelAndView handleCsrf(com.ordering.config.CsrfException e,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        log.warn("CSRF 拦截: uri={}, method={}, msg={}",
                request.getRequestURI(), request.getMethod(), e.getMessage());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);   // 403
        ModelAndView mv = new ModelAndView("error-403");
        mv.addObject("message", e.getMessage());
        return mv;
    }

    /**
     * 兜底的异常处理：任何没被上面接住的异常都到这里。
     *
     * ⚠️ 两个必须知道的原则：
     *   1. 【不要在页面上显示 e.getMessage() 或堆栈】——
     *      那会把内部实现细节暴露给攻击者（比如数据库连接字符串、SQL 语句）。
     *      所以这里给人看的是友好文案，真实的异常只写日志。
     *   2. 日志级别要用 error（而不是 debug/info），
     *      因为这是真的出了问题，运维需要能第一时间发现。
     */
    @ExceptionHandler(Exception.class)
    public ModelAndView handleAll(Exception e,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        log.error("未处理的异常: uri={}", request.getRequestURI(), e);

        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        ModelAndView mv = new ModelAndView("error-500");
        // ★ 故意不把 e.getMessage() 放进去 —— 见上面原则 1
        mv.addObject("uri", request.getRequestURI());
        return mv;
    }
}
