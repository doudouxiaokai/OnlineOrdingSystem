# 网上订餐系统（Online-Ordering-System）

基于 Spring Boot + UniApp 的网上订餐系统，实现「下单—支付—备餐—配送—评价」的完整订餐闭环，并在后端自研了一套 **Agent 运行时（显式规划 + 工具调用 + WebSocket 流式）**，支持 AI 自然语言点餐。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 17 · Spring Boot 3.2.5 · MyBatis-Plus 3.5.7 · MySQL 8 · WebSocket |
| 前端 | UniApp（Vue），可打包 Android / 微信小程序 |
| AI | 阿里云百炼 DashScope（OpenAI 兼容协议 + Function Calling，模型 qwen-plus） |
| 测试/评测 | JUnit 5 + Mockito · JMeter · Python（评测/造数/压测） |

一句话技术栈：Spring Boot、MyBatis-Plus、MySQL、UniApp、阿里百炼大模型（Function Calling）、WebSocket、Python、Spring Task。

## 核心链路

```
手机号快捷登录（无则自动注册）
  → 选餐厅 / 看菜单
  → 下单（SQL 原子扣库存，防超卖）
  → 模拟支付（10 分钟超时）
  → 备餐 → 配送（骑手入库）→ 完成
  → 评价（回写餐厅评分）

AI 点餐：自然语言 → 显式规划（DAG）→ 拓扑执行工具 → WebSocket 流式推送轨迹 → 自然语言回复
```

## 难点与我的做法

### 1. AI Agent：纯自研运行时（不依赖 LangChain / Spring AI）

这是项目核心卖点。我**没有**用现成 Agent 框架，而是从零实现了一套可测试、可回放、可断言、可局部重规划的 Agent 运行时：

- **工具层**：`@AgentTool` 注解 + `ToolRegistry` 反射扫描，把 8 个领域工具（`list_restaurants` / `list_dishes` / `create_order` / `get_order_history` / `check_stock` / `quote_order` / `pay_order` / `search_restaurants`）统一生成 OpenAI Function Calling 定义，按副作用分级为 `READ / WRITE / COMMIT`。
- **编排层（显式规划）**：`Planner` 让 LLM 输出 JSON 形式的执行计划（DAG），`PlanValidator` 校验工具存在/无环/依赖，`Executor` 按拓扑顺序执行并解析 `${stepId.items[0].restaurantId}` 占位符把前一步真实结果注入下一步（支持嵌套数组递归解析；**解析失败立即中断**，绝不把字面量当 ID 传给下游），`Replanner` 在失败时只重写未完成子图，`Guardrails` 对 COMMIT 类操作做副作用分级拦截。
- **复杂度自适应**：默认走显式规划；若计划只有 1 步且是 READ 工具则直接执行，让「计划长短」自动决定复杂度。
- **多 Agent 协作**：`CriticAgent` 在下单前独立复核、`MerchantAgent` 生成补货建议；`UserPrefService` 从历史订单抽取用户偏好做长期记忆。
- **可观测**：每次运行落库 `t_agent_run` / `t_agent_step`（计划快照 + 逐步执行记录），支持 trace 回放与成本统计。

### 2. WebSocket 流式推送 + 前端执行轨迹面板

点餐 Agent 的执行要几秒（LLM 调用），同步返回体验差。我用 `spring-boot-starter-websocket` 做了 `/ws/agent` 通道：客户端提交 `{userId, sessionId, text}`，服务端在编排过程中顺序推送 `plan`（计划 DAG）/ `step`（每步 RUNNING/DONE/FAILED）/ `done`（最终回复）事件；前端 `chat.vue` 用 `uni.connectSocket` 接收，渲染一个**深色执行轨迹面板**，实时展示每一步的工具、状态图标、依赖与输出。

### 3. 库存防超卖

并发下单时「先查再改」会产生超卖，我直接在 SQL 层做原子扣减：

```sql
UPDATE t_dish SET current_stock = current_stock - #{qty}, sales = sales + #{qty}
WHERE dish_id = #{id} AND current_stock >= #{qty}
```

靠更新返回行数（`updatedRows == 0`）判断库存不足，扣减和判断在一条 SQL 里原子完成，从数据库层面杜绝超卖。

### 4. 订单状态机 + 取消回滚库存

订单严格生命周期：`UNPAID → PREPARING → DELIVERING → COMPLETED`，用 `transitionToNextStatus` 控制流转，UNPAID 必须先支付才能流转。取消订单先恢复库存、再改状态，保证「扣了库存就一定能退回」。

### 5. 定时任务（Spring Task）

- 每 1 分钟：扫描超时未支付订单，自动取消并回滚库存；
- 每 5 分钟：扫描超时未接单订单，标记配送异常并输出风控日志；
- 每 10 分钟：扫描库存低于安全阈值的菜品，输出预警日志。

### 6. 支付与 Agent 的职责边界

资金操作不能交给模型代劳。早期版本 `pay_order` 就是个普通工具，用户问「刚下的单怎么付款」，Agent 顺手就把钱付了——用户还没选支付方式，订单状态已经变成备餐中。现在的约定是：

- 计划流程到 `create_order` 为止，**默认不自动支付**；只有用户明确说「直接支付」「帮我付了」才允许 `pay_order`；
- 下单成功后，前端在对话里渲染一张**待支付订单卡片**，「去支付」按钮跳 `pages/order/pay`，由用户在支付页选支付方式完成；
- 问价格、问库存、问支付方式这类**咨询不触发**任何资金工具。

同时给 `create_order` 加了入库前校验：菜品必须真实存在、必须属于指定餐厅、库存必须足够、数量必须大于 0。否则 LLM 编造一个不存在的菜品也能落成脏订单。

### 7. Python 评测 / 造数 / 压测三件套

用 Python 补齐 Agent 的质量保障（见 `eval/` 目录，依赖见 `requirements.txt`）：

- **`eval_agent.py`（评测）**：**81 个评测点**（78 条单轮 + 3 轮多轮 ID 流转），按**边界值 + 等价类**设计，覆盖 14 个维度 —— 意图识别（查餐厅/看菜单/查库存/历史/报价/下单）、输入形态边界（单字、纯标点、纯数字、纯符号、emoji、超长重复、长复合需求、纯空格）、噪声（错别字「猪脚反」、错字店名、拼音、方言「我要次」）、否定与比价、不存在的内容、闲聊越界、**提示词注入与越权**、歧义多意图、支付。支持 HTTP 同步与 WebSocket 流式双链路；断言含工具匹配、`forbid` 黑名单（安全用例校验「不得触达某工具」，比只看最终回复更严）、关键信息命中、ID 泄露检测。
- **`seed_data.py`（造数）**：pymysql + faker 生成用户/餐厅/菜品/历史订单，默认 dry-run、`--apply` 落库。
- **`load_test.py`（压测）**：ThreadPoolExecutor 并发压测，统计 QPS、P50/P95/P99 时延、成功率、token 成本。

**实测结果**：81 个评测点通过 80 个（**98.8%**），唯一失败项经查是 LLM 调用超时（token 记为 0），单独复测两次均正确通过；平均时延 5.85s、平均规划步数 1.70。压测 12 请求 / 并发 3，成功率 100%，P50 4.37s——**瓶颈在 LLM 调用时延与 token 成本，不在后端编排层**。

## 测试

建立了「单元测试 → 集成测试 → 系统测试」的分层测试体系，累计 89 条用例：

- 单元测试：JUnit 5 + Mockito 隔离 Mapper 层，覆盖订单创建、状态流转、评价等核心逻辑；
- 集成测试：Postman 串联用例，验证下单扣库存、评价评分重算、事务回滚的数据一致性；
- 系统测试：功能用例（核心链路 100% 通过）、性能、兼容、安全、回归用例；
- 性能测试：JMeter 50 并发核心接口平均 < 300ms、10 分钟耐久无内存泄漏；
- 安全测试：验证 SQL 注入防护，发现越权访问、鉴权缺失两类漏洞，完成 OWASP 风险评级。

## 构建运行

- 后端：JDK 17 + Maven，依赖 MySQL（`takeaway_db` 建库建表，见 `db/数据库重构.sql`）；LLM 密钥通过环境变量 `LLM_API_KEY` 注入（不落盘）
- **编译参数**：`pom.xml` 的 `maven-compiler-plugin` 必须带 `<parameters>true</parameters>`。缺了它 Spring 6 拿不到方法参数名，所有未显式命名的 `@RequestParam` / `@PathVariable` 接口会直接 500（本项目踩过，14 个接口同时挂掉，排查入口只是"个人中心弹了个失败"）
- 环境切换：`application.yml` 默认 `profiles.active=local`；IDEA 里已预置「本地启动 (local)」「远程启动 (remote)」两个 Run Configuration，`remote` 需先把 `application-remote.yml` 的数据库地址填掉
- 前端：HBuilderX 导入 `前端部分UniApp/ZJD1`；`utils/request.js` 的 `BASE_URL` 默认 `http://localhost:8080`，真机 / 小程序调试需换成电脑局域网 IP
- Python：`cd eval && python -m pip install -r requirements.txt`，脚本用 `python eval_agent.py` / `seed_data.py` / `load_test.py`

## 定位说明

本项目定位为软件工程实训原型：支付为模拟回调、鉴权为轻量 Token，未接入第三方商业 SDK，重点在于业务闭环、自研 Agent 运行时与工程规范性的完整实现。
