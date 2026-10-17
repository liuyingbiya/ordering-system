package com.ordering;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 整个程序的启动入口。
 *
 * 对比原来的项目：原来没有这个类。
 * 原来你写完 Servlet 之后，是把它丢给 Jetty 插件去跑（pom.xml 里的 jetty-maven-plugin），
 * 容器怎么启动、Servlet 什么时候被创建，全是容器的事，你插不上手。
 *
 * 现在这个 main 方法就是一切开始的地方：
 *   java -jar ordering-springboot-2.0.0.jar
 * 会执行到这里，Spring 启动、自动配置、内嵌 Tomcat 起来、监听 8080 端口。
 *
 * @SpringBootApplication 这一个注解干了三件事（面试常问，记一下）：
 *   1. @SpringBootConfiguration —— 声明这是一个配置类
 *   2. @EnableAutoConfiguration —— 开启自动配置：Spring 看你引入了哪些依赖，
 *      就自动帮你配好。比如看到 thymeleaf 依赖就自动配好模板引擎，
 *      看到 datasource 配置就自动建好数据库连接池。
 *   3. @ComponentScan —— 扫描「当前包及子包」下所有带 @Controller / @Service
 *      / @Repository / @Component 的类，把它们变成 Spring 管理的对象（Bean）。
 *      注意：这就是为什么这个类必须放在 com.ordering 这个最外层的包下面 ——
 *      放在子包里就扫不到其他子包了，这是新手最常踩的坑。
 *
 * ⚠️ 阶段 2 新增：@MapperScan("com.ordering.mapper")
 *   @ComponentScan 只认 @Component/@Controller/@Service/@Repository 这四类注解，
 *   它不会去扫 Mapper 接口。而我们的 DishMapper、OrderMapper 是接口不是类，
 *   需要 MyBatis 专门为它们生成代理对象。
 *   所以必须额外加 @MapperScan 告诉 MyBatis：「这个包下的接口都给我生成实现」。
 *
 *   另一种写法是在每个 Mapper 接口上加 @Mapper 注解（我们的接口上也加了），
 *   两个都加不冲突。但项目里 Mapper 多了以后，用 @MapperScan 一行顶 N 行更清爽，
 *   而且不用每个文件都记得加 @Mapper。
 */
@MapperScan("com.ordering.mapper")
@SpringBootApplication
public class OrderingApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderingApplication.class, args);
        System.out.println("========================================");
        System.out.println("点餐系统启动成功！请在浏览器打开：");
        System.out.println("  http://localhost:8080");
        System.out.println("========================================");
    }
}