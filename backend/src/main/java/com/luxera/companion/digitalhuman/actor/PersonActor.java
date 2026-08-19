package com.luxera.companion.digitalhuman.actor;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * V10 §20 Person Actor: 单个数字人的逻辑单线程执行单元。
 *
 * 核心原则(V10 §20):
 * - 同一个 Person 的状态修改串行(mailbox 队列 + 单消费者线程);
 * - 不同 Person 并行(每个 Person 独立 Actor);
 * - 事件进入 Mailbox, 由 Actor 依次消费, 绝不并发触碰同一 Person 状态。
 *
 * 实现: 每 Person 一个 {@link BlockingQueue}(mailbox) + 一个守护消费者线程;
 * 空闲时阻塞等待, 不占 CPU; 优雅关闭时排空剩余任务。
 */
@Slf4j
public class PersonActor {

    private final String personId;
    private final BlockingQueue<Runnable> mailbox;
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong queueDepth = new AtomicLong();

    public PersonActor(String personId, Consumer<Throwable> errorSink) {
        this.personId = personId;
        this.mailbox = new LinkedBlockingQueue<>();
        this.worker = new Thread(() -> drain(errorSink), "person-actor-" + personId);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    private void drain(Consumer<Throwable> errorSink) {
        while (running.get() || !mailbox.isEmpty()) {
            try {
                Runnable task = mailbox.take();
                queueDepth.decrementAndGet();
                try {
                    task.run();
                    processedCount.incrementAndGet();
                } catch (Throwable t) {
                    if (errorSink != null) {
                        try {
                            errorSink.accept(t);
                        } catch (Exception ignored) {
                            log.warn("[PersonActor] {} errorSink 失败: {}", personId, ignored.getMessage());
                        }
                    } else {
                        log.error("[PersonActor] {} 任务执行失败: {}", personId, t.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!running.get()) break;
            }
        }
    }

    /** 向 mailbox 提交任务(异步, 立即返回; 同 Person 任务按提交顺序串行执行) */
    public void tell(Runnable task) {
        if (!running.get()) {
            log.warn("[PersonActor] {} 已关闭, 拒绝任务", personId);
            return;
        }
        queueDepth.incrementAndGet();
        mailbox.offer(task);
    }

    /** mailbox 积压深度(监控用) */
    public long queueDepth() {
        return queueDepth.get();
    }

    /** 已处理任务数(监控用) */
    public long processedCount() {
        return processedCount.get();
    }

    public String personId() {
        return personId;
    }

    /** 优雅关闭: 停止接收新任务, 排空剩余任务后退出 */
    public void shutdown() {
        running.set(false);
        worker.interrupt();
    }

    /** 是否存活 */
    public boolean alive() {
        return worker.isAlive();
    }
}
