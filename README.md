# AFTER_COMMIT 多监听器异常行为验证

回答原问题：**同一个请求、同一事件存在多个 `@TransactionalEventListener(AFTER_COMMIT)` 监听器，若其中一个抛出异常——**

- 事务已经提交，数据已落库（无法回滚）
- 异常监听器**之前的监听器已执行**
- 异常监听器**之后的监听器也会执行**（不是 default multicaster 的循环中断模型）
- 异常被 Spring 在 `TransactionSynchronizationUtils.invokeAfterCompletion` 处**静默吞掉并记录 ERROR 日志**
- 调用方**不会收到异常**，HTTP 请求**仍然返回 200**

---

## ⚠️ 重要事实更正

之前两轮回答里有两处错误，已经通过运行测试纠正：
1. ❌ ~~"默认 multicaster 让循环中断，B 之后的监听器不执行"~~ → ✅ **所有监听器都会执行**
2. ❌ ~~"ErrorHandler 配置后所有监听器都执行"~~ → ✅ **不加 ErrorHandler，所有监听器也都执行**

正确机制是：**每个 `@TransactionalEventListener` 方法都被 Spring 单独注册为一个 `TransactionalApplicationListenerSynchronization`**。`TransactionSynchronizationUtils.invokeAfterCompletion` 在外层循环里逐个调用每个 synchronization 的 `afterCompletion`，每次调用都包在自己的 try/catch 里。所以：

- 某个监听器抛异常 → 仅该 synchronization 抛出 → `invokeAfterCompletion` 的 try/catch 吞掉 → 其他 synchronization **不受影响，继续执行** → 全部监听器都跑了
- 异常一路向上冒泡，最终被 `TransactionSynchronizationUtils` 捕获并以 ERROR 级别日志输出，但**不会**返回给调用方

对于 `@TransactionalEventListener` 来说，配置 `SimpleApplicationEventMulticaster.setErrorHandler()` **不会改变任何可观察的行为**（multicaster 级别的 errorHandler 在事务监听链路上被旁路）。它只对 `@EventListener`（非事务）有意义。

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
        └── ErrorHandlerMulticasterTest.java     # 启用 ErrorHandler profile
```

---

## 如何运行

环境：JDK 17 + Maven 3.9+（本机已装在 `D:\Environment\`）。

```bash
cd D:\Workspace\MiniMax\after-commit-test
mvn test
```

观察日志：
- `[Service] committed-order id=1, publishing event 1`
- `[ListenerA] DONE event=order-1`
- `[ListenerB] START event=order-1 -- about to throw`
- Spring 输出 `TransactionSynchronization.afterCompletion threw exception` ERROR 日志（含 ListenerB 完整堆栈）
- `[ListenerC] START event=order-1`  ← **依然执行了**
- `[ListenerC] DONE event=order-1`

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

### `ErrorHandlerMulticasterTest`

| 断言 | 期望 | 实测结果 |
|------|------|---------|
| `placeOrder()` 正常返回 | ✅ | ✅ |
| 事务已提交 | ✅ | ✅ |
| ListenerA 已执行 | ✅ | ✅ |
| ListenerC **也**执行了 | ✅ | ✅ |
| 异常被 Spring 记录到日志 | ✅ | ✅ |

**两个测试结论完全一致**——对于 `@TransactionalEventListener`，配置 errorHandler 不会改变任何可观察行为。

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
TransactionalApplicationListenerSynchronization.processEventWithCallbacks   ← line 65
  ↓
TransactionalApplicationListenerSynchronization$PlatformSynchronization.afterCompletion   ← line 118
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

| 问题点 | 正确答案 |
|--------|---------|
| 请求失败? | **不会**(异常被吞,HTTP 200) |
| 其他监听是否执行? | **都会执行**(每个监听器独立同步,互不影响) |
| 数据是否落库? | **是**(AFTER_COMMIT 在 commit 之后才触发) |
| 事务能回滚吗? | **不能**(commit 已完成) |

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