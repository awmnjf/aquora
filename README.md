# Aquora Java 后端

Aquora 应用的 Java 后端，使用 Java 21、Spring Boot 3.5.16、Spring JDBC、PostgreSQL 和 Flyway。仓库包含认证、用户资料、记录、护理计划、智能服务接口和管理接口的源码，以及数据库迁移和单元测试。

## 环境要求

- JDK 21
- Maven 3.6.3 或更高版本
- PostgreSQL，以及已创建的 `aquora` 数据库和具有建表权限的数据库用户
- 使用智能功能时，需要单独运行 Aquora Python 智能服务

## 构建和测试

```sh
mvn verify
```

构建产物为 `target/aquora-core-1.0.0.jar`。

## 配置

| 环境变量 | 用途 | 默认值 |
| --- | --- | --- |
| `DB_PASSWORD` | 数据库密码，启动前必须设置 | 无 |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC 地址 | `jdbc:postgresql://127.0.0.1:5432/aquora` |
| `SPRING_DATASOURCE_USERNAME` | 数据库用户 | `aquora` |
| `AI_BASE_URL` | Python 智能服务地址 | `http://127.0.0.1:8091` |
| `INTERNAL_TOKEN` | 调用智能服务时使用的内部令牌，需与智能服务配置一致 | 空字符串 |
| `SERVER_ADDRESS` | 后端监听地址 | `127.0.0.1` |
| `SERVER_PORT` | 后端监听端口 | `8090` |

请通过部署环境提供密码和令牌。首次启动时，Flyway 会执行 `src/main/resources/db/migration` 中的迁移，初始化数据库表。

## 启动

Linux / macOS：

```sh
export DB_PASSWORD='替换为本地数据库密码'
java -jar target/aquora-core-1.0.0.jar
```

PowerShell：

```powershell
$env:DB_PASSWORD = '替换为本地数据库密码'
java -jar target/aquora-core-1.0.0.jar
```

开发时也可在设置环境变量后运行 `mvn spring-boot:run`。

服务默认监听 `http://127.0.0.1:8090`，健康检查路径为 `/api/health`。线上 `/aquora/api/` 前缀由反向代理映射到后端的 `/api/`。

## 目录

- `src/main/java/cn/aquora`：Java 应用源码
- `src/main/resources/application.properties`：默认应用配置
- `src/main/resources/db/migration`：数据库迁移
- `src/test/java`：单元测试

本仓库仅包含 Java 后端。前端、Python 智能服务和生产环境凭据需单独管理。
