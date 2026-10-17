# ============================================================
#  多阶段构建（Multi-stage build）
#
#  为什么要「多阶段」？
#  第一阶段的容器里有 Maven、有整个 JDK、还有一堆编译中间文件，体积 700MB+。
#  但运行的时候根本不需要这些 —— 只需要一个 JRE 和一个 jar。
#  所以第二阶段从一个干净的 JRE 镜像开始，只把 jar 拷进来。
#  最终镜像大约 200MB，而不是 700MB。
#
#  一句话：**用大镜像编译，用小镜像运行。**
# ============================================================

# ---------- 第一阶段：编译 ----------
FROM maven:3.8-openjdk-8 AS build

WORKDIR /app

# 注入阿里云 Maven 镜像。
# 为什么必须注入：Maven 中央仓库在国外，国内构建时要下几十 MB 依赖，
# 直连经常慢到十几分钟甚至超时。阿里云镜像通常几十秒就能下完。
RUN mkdir -p /root/.m2 && printf '%s' \
    '<settings><mirrors><mirror><id>aliyun</id><name>aliyun</name><url>https://maven.aliyun.com/repository/public</url><mirrorOf>central</mirrorOf></mirror></mirrors></settings>' \
    > /root/.m2/settings.xml

# 先只拷 pom.xml 单独跑一次依赖下载。
# 这是为了利用 Docker 的层缓存：只要 pom.xml 没变，重新构建时
# 这一步会命中缓存，不用重新下依赖（能省好几分钟）。
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 再拷源码编译
COPY src ./src
RUN mvn -B package -DskipTests

# ---------- 第二阶段：运行 ----------
FROM eclipse-temurin:8-jre

WORKDIR /app

# 从上一阶段的镜像里只取 jar
COPY --from=build /app/target/*.jar app.jar

# 时区设成上海，否则容器里是本机 UTC 时间
# 影响：订单的 create_time 会差 8 小时
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 声明容器监听 8080（只是文档作用，实际端口映射在 docker run / compose 里）
EXPOSE 8080

# ★ 必须带 -Dfile.encoding=UTF-8
# 本机是因为 Windows 代码页是 GBK 才需要；
# Linux 容器默认通常是 UTF-8，但显式指定不亏，避免不同基础镜像行为不一致。
# （不要在容器里用 -Dsun.jnu.encoding，那个参数会破坏文件名编码）
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
