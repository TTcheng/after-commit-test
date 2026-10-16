# AFTER_COMMIT 多监听器异常行为验证

## 问题

在 Spring Boot 项目中，同一个请求中的同一个事件存在多个 `@TransactionalEventListener(AFTER_COMMIT)` 监听器，若其中一个监听抛出异常：

1. **请求是否会失败？**
2. **其他监听是否会执行？**

## 答案

1. **请求不会失败。** 异常被 Spring 在 `TransactionSynchronizationUtils.invokeAfterCompletion` 处静默吞掉，只记录一条 ERROR 日志；调用方收不到异常，HTTP 仍然返回 200。此时事务早已提交，数据已落库，无法回滚。

2. **其他监听全部会执行。** 包括异常监听器后面的那些。每个 `@TransactionalEventListener` 方法都被单独注册为一个 `TransactionSynchronization`，而 `invokeAfterCompletion` 对每一次 `afterCompletion` 调用各自包了一层 try/catch，所以一个监听抛异常不会阻断其他监听。

---

## ⚠️ 重要事实更正

前两轮回答里有两处错误，已通过运行测试纠正：

1. ❌ ~~"默认 multicaster 让循环中断，B 之后的监听器不执行"~~ → ✅ **所有监听器都会执行**
2. ❌ ~~"ErrorHandler 配置后所有监听器都执行"~~ → ✅ **不加 ErrorHandler，所有监听器也都执行**

另有一个独立结论：**给 multicaster 配置 `SimpleApplicationEventMulticaster.setErrorHandler()` 对 `@TransactionalEventListener` 无效**。异常在事务同步层就被吞掉了，根本没回到 multicaster，errorHandler 不会被调用——它只对非事务的 `@EventListener` 有意义。

### 字节码层面的直接证据

不依赖日志推断，直接看 `spring-tx-6.1.6.jar` 中
`TransactionSynchronizationUtils.invokeAfterCompletion` 的字节码：

```
32: invokeinterface  TransactionSynchronization.afterCompletion:(I)V   ← try 只包住这一次调用
40: astore 4                                                          ← catch
45: ldc "TransactionSynchronization.afterCompletion threw exception"
54: goto 11                                                           ← 跳回 hasNext()，继续下一个

Exception table:
   from    to  target type
      30    37    40   Class java/lang/Throwable
```

三点一目了然：try/catch **只包单次调用**；捕获类型是 `Throwable`（什么都吞）；记完日志 `goto 11` **跳回循环**。所以"C 仍然执行"是机制必然，不是巧合。

---

## 项目结构

```
after-commit-test/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/example/aftercommit/
    │   │   ├── AfterCommitApplication.java
    │   │   ├── DemoEvent.java
    │   │   ├── Order.java
    │   │   ├── OrderRepository.java
    │   │   ├── OrderService.java           # @Transactional, 发布事件
    │   │   ├── OrderController.java        # POST /orders?product=...&quantity=...
    │   │   ├── EventMulticasterConfig.java # @Profile("with-error-handler")
    │   │   └── listeners/
    │   │       ├── ListenerA.java          # @Order(1), 正常
    │   │       ├── ListenerB.java          # @Order(2), 抛 RuntimeException
    │   │       └── ListenerC.java          # @Order(3), 正常
    │   └── resources/application.yml
    └── test/java/com/example/aftercommit/
        ├── DefaultMulticasterTest.java          # 默认行为
        ├── ErrorHandlerMulticasterTest.java     # 启用 ErrorHandler profile
        └── OrderControllerWebTest.java          # HTTP 层：200 结论 + 请求级错误对照
```

---

## 如何运行

需要 JDK 17 与 Maven 3.9+。本机**没有把 `java` / `mvn` 加进 PATH**，且预置的本地仓库在
`D:\Environment\Maven\maven-repo`（Maven 默认会去空的 `~/.m2/repository`，导致离线解析父 POM 失败）。可直接用：

```powershell
cd D:\Workspace\MiniMax\after-commit-test
$env:JAVA_HOME = 'D:\Environment\Java\jdk-17.0.20.1+1'
& 'D:\Environment\Maven\apache-maven-3.9.16\bin\mvn.cmd' -o "-Dmaven.repo.local=D:\Environment\Maven\maven-repo" test
```

> 注意 `-D` 参数要用**双引号整体包住**。用单引号时 PowerShell 会原样传给 Maven，
> `.repo.local=D` 会被当成 goal 前缀而报 `No plugin found for prefix`。

若 `java` / `mvn` 已在 PATH 且走默认仓库，则直接 `mvn test` 即可。

预期：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`。

### 观察到的日志（一次 HTTP 请求的完整时序）

```
[Service]     saved (NOT yet committed) order id=1, publishing event 1
[Service]     publishEvent returned -- listeners only registered, commit still pending
[ListenerA]   START event=order-1 (transaction already committed)
[ListenerA]   DONE event=order-1
[ListenerB]   START event=order-1 -- about to throw
ERROR o.s.t.s.TransactionSynchronizationUtils : TransactionSynchronization.afterCompletion threw exception
[ListenerC]   START event=order-1        ← 依然执行
[ListenerC]   DONE event=order-1
[Controller]  returning 200 for order id=1
```

注意 `[Controller] returning 200` 出现在 **ListenerC 之后**——异常根本没机会影响响应码。

> `OrderService` 里特意打印 "saved (NOT yet committed)"：`GenerationType.IDENTITY` 会立即 INSERT，
> 所以 id 已经生成，但**此刻事务尚未提交、仍可回滚**。提交发生在 `@Transactional` 代理退出时。

---

## 验证结果（实测）

### `DefaultMulticasterTest`

| 断言 | 期望 | 实测结果 |
|------|------|---------|
| `placeOrder()` 正常返回（异常被吞） | ✅ | ✅ |
| 事务已提交（count 增加） | ✅ | ✅ |
| ListenerA 已执行 | ✅ | ✅ |
| ListenerC **也**执行了 | ✅ | ✅ |
| 异常被 Spring 记录到日志 | ✅ | ✅ |

监听器断言放在 `placeOrder()` 返回后**立即**执行、不做任何 sleep —— 这本身就是"AFTER_COMMIT
监听器在提交线程上同步完成"的正面证据。

### `ErrorHandlerMulticasterTest`

| 断言 | 期望 | 实测结果 |
|------|------|---------|
| `placeOrder()` 正常返回 | ✅ | ✅ |
| 事务已提交 | ✅ | ✅ |
| ListenerA / ListenerC 都执行了 | ✅ | ✅ |
| 异常被 Spring 记录到日志 | ✅ | ✅ |
| **具名 bean `applicationEventMulticaster` 确实生效且被容器使用** | ✅ | ✅ |
| **`[ErrorHandler]` 日志行始终不出现**（证明被旁路而非闲置） | ✅ | ✅ |

后两条是这个测试**能够失败**的关键：删掉 `EventMulticasterConfig` 或摘掉 profile，
上下文启动就会失败，测试不会再"空转通过"。

### `OrderControllerWebTest`

| 断言 | 期望 | 实测结果 |
|------|------|---------|
| ListenerB 抛异常时 `POST /orders` | **HTTP 200** | ✅ |
| 响应体含正确的 id / product / quantity | ✅ | ✅ |
| 数据确实已落库（不是"回滚后假装成功"） | ✅ | ✅ |
| 同一次请求内 A / B / C 三个监听器都跑过 | ✅ | ✅ |
| 请求本身非法（缺 product） | **HTTP 400** | ✅ |

最后一行是对照组：**只有监听器异常被静默吞掉，请求级错误仍会正常冒泡。**

**三个测试结论完全一致**——对于 `@TransactionalEventListener`，配置 errorHandler 不会改变任何可观察行为。

---

## 三种行为的对比

| 场景 | 调用方收到异常 | 后续监听器是否执行 | 事务提交 |
|------|--------------|------------------|---------|
| `@TransactionalEventListener(AFTER_COMMIT)` 默认 | **否**(被吞) | **是** | 是 |
| `@TransactionalEventListener(AFTER_COMMIT)` + ErrorHandler | 否 | 是 | 是 |
| `@EventListener`(非事务) 默认 | **是**(循环中断) | **否** | — |
| `@EventListener` + ErrorHandler | 否 | **是** | — |

---

## 异常抛出路径（来自测试日志的实证）

```
ListenerB.handle 抛 RuntimeException
  ↓
ApplicationListenerMethodAdapter.doInvoke
  ↓
ApplicationListenerMethodAdapter.processEvent
  ↓
TransactionalApplicationListenerSynchronization.processEventWithCallbacks
  ↓
TransactionalApplicationListenerSynchronization$PlatformSynchronization.afterCompletion
  ↓
TransactionSynchronizationUtils.invokeAfterCompletion  ← 这里 try/catch 吞掉，记 ERROR 日志
  ↓
AbstractPlatformTransactionManager.invokeAfterCompletion
  ↓
AbstractPlatformTransactionManager.triggerAfterCompletion
  ↓
AbstractPlatformTransactionManager.processCommit
  ↓
... 回到调用方，没有异常冒泡
```

注意 ListenerC 的同步路径**完全独立**，在 `invokeAfterCompletion` 的下一次循环迭代里正常执行，所以完全不受 ListenerB 异常影响。

---

## 与原问题的精确对应

> **问**:在 springboot 项目中,同一个请求中的同一个事件存在多个 aftercommit 监听,若其中一个监听抛出异常,请求是否会失败,其他监听是否会执行

| 问题点 | 正确答案 | 验证它的测试 |
|--------|---------|-------------|
| 请求失败? | **不会**(异常被吞,HTTP 200) | `OrderControllerWebTest` |
| 其他监听是否执行? | **都会执行**(每个监听器独立同步,互不影响) | `DefaultMulticasterTest` |
| 数据是否落库? | **是**(AFTER_COMMIT 在 commit 之后才触发) | `DefaultMulticasterTest` |
| 事务能回滚吗? | **不能**(commit 已完成) | `DefaultMulticasterTest` |
| 配 errorHandler 有用吗? | **没用**(在事务链路上被旁路) | `ErrorHandlerMulticasterTest` |

---

## 附:两个容易踩的版本坑

1. **`ApplicationEventMulticaster` 的包名在 Spring 6 变过。**
   Spring 5.x 是 `org.springframework.context.ApplicationEventMulticaster`；
   Spring Framework 6.0 起移到 `org.springframework.context.event.ApplicationEventMulticaster`
   （与 `SimpleApplicationEventMulticaster` 同包）。沿用旧 import 会直接编译失败。

2. **Boot 3.x 已经没有 `SimpleApplicationEventMulticasterAutoConfiguration` 了。**
   实测 `spring-boot-autoconfigure-3.2.5.jar` 中不存在任何 `*Multicaster*` 类，
   `autoconfigure.context` 包只剩 8 个类。因此**不存在"覆盖 Boot 默认多播器"这回事**——
   没有 `ApplicationEventMulticaster` bean 时，`AbstractApplicationContext` 会自行
   `new SimpleApplicationEventMulticaster()` 兜底（且不带 errorHandler）。
   自定义 `@Bean applicationEventMulticaster` 依然有效（容器按类型取用），
   但理由和 Boot 2.x 时已经不同了。

---

## 工程启示

`@TransactionalEventListener(AFTER_COMMIT)` 是经典的"**静默丢消息**"陷阱:
- 异常**没有任何信号**返回给调用方
- 调用方完全不知道下游处理失败
- 只能通过日志/监控/落表发现

生产实践:
1. **监听器内部 `try-catch`**——最稳,自己负责兜底
2. **落库到 outbox 表**——后台 worker 异步补偿,推荐做法
3. **加 ERROR 监控告警**——监听 `TransactionSynchronization.afterCompletion threw exception` 关键字
4. **不要只依赖 ErrorHandler**——对 `@TransactionalEventListener` 无效
