# Aquora Java 后端

Aquora 应用的 Java 后端，使用 Java 21、Spring Boot 3.5.16、Spring JDBC、PostgreSQL 和 Flyway。仓库包含认证、用户资料、记录、护理计划、固定智能服务、Agent 网关和管理接口的源码，以及数据库迁移和单元测试。

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
| `AGENT_BASE_URL` | 独立 Agent 服务地址 | `http://127.0.0.1:8093` |
| `INTERNAL_TOKEN` | 内部调用令牌，需与固定智能服务、Agent 服务配置一致 | 空字符串 |
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

## Agent 网关

`POST /api/agent/run` 需要已登录会话，请求只接受 `message`（最多 2000 字）和 `scenario`（`home`、`outdoor`、`knowledge`，默认 `home`）。

```json
{"message": "我今天喝了100ml水", "scenario": "home"}
```

后端从当前会话确定用户身份，装配该用户最近的对话、过去 24 小时记录及护理计划，转发至独立 Agent 服务。拒绝浏览器传入用户身份、路径、历史和工具配置。网关限制每用户每分钟 8 次、全局每分钟 80 次请求。

Agent 返回的饮水等候选信息只用于预填卡片，仍需用户通过现有记录接口确认保存。固定场景智能接口继续使用 `AI_BASE_URL`；Agent 单独使用 `AGENT_BASE_URL`。两个 Python 服务不包含在此 Java 后端仓库中。

`AgentScopeTest` 检查客户端身份注入、未知场景和工具配置在调用服务前被拒绝；与现有图片候选测试一起运行 `mvn test`。

## 目录

- `src/main/java/cn/aquora`：Java 应用源码
- `src/main/resources/application.properties`：默认应用配置
- `src/main/resources/db/migration`：数据库迁移
- `src/test/java`：单元测试

本仓库仅包含 Java 后端。前端、Python 智能服务和生产环境凭据需单独管理。
