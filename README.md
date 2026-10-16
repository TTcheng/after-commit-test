# AFTER_COMMIT 多监听器异常行为验证

## 问题

在 Spring Boot 项目中，同一个请求中的同一个事件存在多个 `@TransactionalEventListener(AFTER_COMMIT)` 监听器，若其中一个监听抛出异常：

1. **请求是否会失败？**
2. **其他监听是否会执行？**

## 答案

1. **请求不会失败。** 异常被 Spring 在 `TransactionSynchronizationUtils.invokeAfterCompletion` 处静默吞掉，只记录一条 ERROR 日志；调用方收不到异常，HTTP 仍然返回 200。此时事务早已提交，**数据已落库、无法回滚**。

2. **其他监听全部会执行。** 包括异常监听器后面的那些。每个 `@TransactionalEventListener` 方法都被单独注册为一个 `TransactionSynchronization`，而 `invokeAfterCompletion` 对每一次 `afterCompletion` 调用各自包了一层 try/catch，所以一个监听抛异常不会阻断其他监听。

**配 errorHandler 有用吗？没用。** 异常在事务同步层就被吞掉了，根本没回到 multicaster，`setErrorHandler()` 不会被调用。

| 结论 | 验证它的测试 |
|---|---|
| 请求不会失败（HTTP 200） | `OrderControllerWebTest` |
| 其他监听都会执行 | `DefaultMulticasterTest` |
| 数据已落库、无法回滚 | `DefaultMulticasterTest` |
| errorHandler 被旁路 | `ErrorHandlerMulticasterTest` |

---

## 证据一：字节码

不依赖日志推断，直接看 `spring-tx-6.1.6.jar` 中 `TransactionSynchronizationUtils.invokeAfterCompletion` 的字节码：

```
32: invokeinterface  TransactionSynchronization.afterCompletion:(I)V   ← try 只包住这一次调用
40: astore 4                                                          ← catch
45: ldc "TransactionSynchronization.afterCompletion threw exception"
54: goto 11                                                           ← 跳回 hasNext()，继续下一个

Exception table:
   from    to  target type
      30    37    40   Class java/lang/Throwable
```

try/catch **只包单次调用**、捕获 `Throwable`（什么都吞）、记完日志 `goto 11` **跳回循环**——所以「ListenerC 仍然执行」是机制必然，不是巧合。

## 证据二：一次 HTTP 请求的完整日志

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

`publishEvent` 时监听器只是**注册**同步器，并未执行；真正的调用发生在提交时。`[Controller] returning 200` 排在 ListenerC 之后——异常根本没机会影响响应码。

> `saved (NOT yet committed)` 是刻意的：`GenerationType.IDENTITY` 会立即 INSERT，id 已经生成，但此刻事务尚未提交、仍可回滚。

---

## 横向对照

| 场景 | 调用方收到异常 | 后续监听器执行 | 事务提交 |
|------|--------------|--------------|---------|
| `@TransactionalEventListener(AFTER_COMMIT)` 默认 | **否**(被吞) | **是** | 是 |
| `@TransactionalEventListener(AFTER_COMMIT)` + ErrorHandler | 否 | 是 | 是 |
| `@EventListener`(非事务) 默认 | **是**(循环中断) | **否** | — |
| `@EventListener` + ErrorHandler | 否 | **是** | — |

分水岭在于隔离粒度：非事务 `@EventListener` 的所有监听器共享同一次 multicaster 循环，异常会中断循环；而 `@TransactionalEventListener` 每个方法各自独立，异常被隔离在单个同步器内。

---

## 怎么跑

```bash
mvn test        # 需 JDK 17；预期 Tests run: 4, Failures: 0 → BUILD SUCCESS
```

三个测试：`DefaultMulticasterTest`（默认行为）、`ErrorHandlerMulticasterTest`（启用 `with-error-handler` profile）、`OrderControllerWebTest`（HTTP 层，含 400 对照组）。

关键代码就是三个监听器：

```
src/main/java/com/example/aftercommit/listeners/
├── ListenerA.java   @Order(1)  正常
├── ListenerB.java   @Order(2)  抛 RuntimeException
└── ListenerC.java   @Order(3)  正常
```
