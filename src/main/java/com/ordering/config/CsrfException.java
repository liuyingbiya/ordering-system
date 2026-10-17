package com.ordering.config;

/**
 * CSRF 拦截异常 —— 请求来源不可信时抛出。
 *
 * 为什么单独定义一个异常类，而不是直接 sendError(403)？
 *
 *   1. sendError 会走 Servlet 容器的错误页（Spring Boot 默认是 Whitelabel 白页），
 *      体验很差、还会暴露框架信息。
 *   2. 抛出异常之后，可以被 GlobalExceptionHandler 统一接住，
 *      渲染成我们自己的 403 页面，和其他错误页风格一致。
 *   3. 拦截器里直接写 response 会和「异常统一处理」的设计打架，
 *      抛异常更符合分层的思路。
 *
 * 继承 RuntimeException 而不是 Exception：拦截器的方法签名允许抛 Exception，
 * 但这里没有「调用方必须处理」的语义，用非受检异常更省事。
 */
public class CsrfException extends RuntimeException {

    public CsrfException(String message) {
        super(message);
    }
}
