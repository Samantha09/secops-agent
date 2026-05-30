FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

# 安装扫描工具（可选，生产环境建议单独扫描节点）
RUN apk add --no-cache nmap nmap-scripts

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
