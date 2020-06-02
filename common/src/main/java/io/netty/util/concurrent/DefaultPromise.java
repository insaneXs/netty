/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Promise的默认实现
 * @param <V>
 */
public class DefaultPromise<V> extends AbstractFuture<V> implements Promise<V> {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultPromise.class);
    private static final InternalLogger rejectedExecutionLogger =
            InternalLoggerFactory.getInstance(DefaultPromise.class.getName() + ".rejectedExecution");
    private static final int MAX_LISTENER_STACK_DEPTH = Math.min(8,
            SystemPropertyUtil.getInt("io.netty.defaultPromise.maxListenerStackDepth", 8));
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> RESULT_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class, Object.class, "result");
    private static final Object SUCCESS = new Object();
    private static final Object UNCANCELLABLE = new Object();
    private static final CauseHolder CANCELLATION_CAUSE_HOLDER = new CauseHolder(ThrowableUtil.unknownStackTrace(
            new CancellationException(), DefaultPromise.class, "cancel(...)"));

    private volatile Object result;
    //用来执行操作完成后监听器里的方法
    private final EventExecutor executor;
    /**
     * One or more listeners. Can be a {@link GenericFutureListener} or a {@link DefaultFutureListeners}.
     * If {@code null}, it means either 1) no listeners were added yet or 2) all listeners were notified.
     *
     * Threading - synchronized(this). We must support adding listeners when there is no EventExecutor.
     */
    //监听器对象
    private Object listeners;

    /**********Promise和juc下的Future不同，是利用对象锁而非AQS**********************/
    /**
     * Threading - synchronized(this). We are required to hold the monitor to use Java's underlying wait()/notifyAll().
     */
    private short waiters;

    /**
     * Threading - synchronized(this). We must prevent concurrent notification and FIFO listener notification if the
     * executor changes.
     */
    //表示正在通知监听器
    private boolean notifyingListeners;

    /**
     * Creates a new instance.
     *
     * It is preferable to use {@link EventExecutor#newPromise()} to create a new promise
     *
     * @param executor
     *        the {@link EventExecutor} which is used to notify the promise once it is complete.
     *        It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     *        The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     *        depth exceeds a threshold.
     *
     */
    public DefaultPromise(EventExecutor executor) {
        this.executor = checkNotNull(executor, "executor");
    }

    /**
     * See {@link #executor()} for expectations of the executor.
     */
    protected DefaultPromise() {
        // only for subclasses
        executor = null;
    }

    //将Promise设置为成功
    @Override
    public Promise<V> setSuccess(V result) {
        //设置结果，如果失败说明结果已经被设置，则抛出异常
        if (setSuccess0(result)) {
            //唤醒监听者
            notifyListeners();
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    //尝试将Promise设置为成功
    @Override
    public boolean trySuccess(V result) {
        if (setSuccess0(result)) {
            notifyListeners();
            return true;
        }
        return false;
    }

    //将Promise设置为失败
    @Override
    public Promise<V> setFailure(Throwable cause) {
        if (setFailure0(cause)) {
            notifyListeners();
            return this;
        }
        throw new IllegalStateException("complete already: " + this, cause);
    }

    //尝试将Promise设置为失败
    @Override
    public boolean tryFailure(Throwable cause) {
        if (setFailure0(cause)) {
            notifyListeners();
            return true;
        }
        return false;
    }

    //将IO操作设置为不可取消
    @Override
    public boolean setUncancellable() {
        //CAS更新result
        if (RESULT_UPDATER.compareAndSet(this, null, UNCANCELLABLE)) {
            return true;
        }
        //CAS失败，看是否已经被设置为UNCANCELLABLE
        //（未完成 或 未取消）+ （result != null） 推出已经是UNCANCELLABLE
        Object result = this.result;
        return !isDone0(result) || !isCancelled0(result);
    }

    //判断操作是否成功
    @Override
    public boolean isSuccess() {
        Object result = this.result;
        return result != null && result != UNCANCELLABLE && !(result instanceof CauseHolder);
    }

    //是否可以取消
    @Override
    public boolean isCancellable() {
        return result == null;
    }

    //返回异常
    @Override
    public Throwable cause() {
        Object result = this.result;
        return (result instanceof CauseHolder) ? ((CauseHolder) result).cause : null;
    }

    //添加监听
    @Override
    public Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        //同步锁保护 添加监听器
        synchronized (this) {
            addListener0(listener);
        }
        //如果操作完成 唤醒监听器
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    //添加多个监听器
    @Override
    public Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                addListener0(listener);
            }
        }

        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    //移除监听器
    @Override
    public Promise<V> removeListener(final GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        synchronized (this) {
            removeListener0(listener);
        }

        return this;
    }

    //移除多个监听器
    @Override
    public Promise<V> removeListeners(final GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                removeListener0(listener);
            }
        }

        return this;
    }

    //等待操作完成
    @Override
    public Promise<V> await() throws InterruptedException {
        //已经完成
        if (isDone()) {
            return this;
        }

        //线程被中断
        if (Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        checkDeadLock();

        //同步，自旋等待
        synchronized (this) {
            while (!isDone()) { //直到完成返回 或是当前线程被中断抛出异常
                //增加waiter的数量
                incWaiters();
                try {
                    //进入wait 释放对象锁
                    wait();
                } finally {
                    //醒来重新并获取对象锁，减少waiter的数量
                    decWaiters();
                }
            }
        }
        return this;
    }

    @Override
    public Promise<V> awaitUninterruptibly() {
        if (isDone()) {
            return this;
        }

        checkDeadLock();

        boolean interrupted = false;
        synchronized (this) {
            //自循环等待
            while (!isDone()) {
                incWaiters();
                try {
                    //释放对象锁
                    wait();
                } catch (InterruptedException e) {
                    //如果等待过程中 发生中断 则设置标记位
                    // Interrupted while waiting.
                    interrupted = true;
                } finally {
                    decWaiters();
                }
            }
        }

        //重新恢复中断标记位
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return this;
    }

    //带超时的等待
    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return await0(unit.toNanos(timeout), true);
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await0(MILLISECONDS.toNanos(timeoutMillis), true);
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        try {
            return await0(unit.toNanos(timeout), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        try {
            return await0(MILLISECONDS.toNanos(timeoutMillis), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    //非阻塞的获取返回值
    @SuppressWarnings("unchecked")
    @Override
    public V getNow() {
        Object result = this.result;
        if (result instanceof CauseHolder || result == SUCCESS) {
            return null;
        }
        return (V) result;
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    //取消任务
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        //如果result为null 说明可以取消
        if (RESULT_UPDATER.compareAndSet(this, null, CANCELLATION_CAUSE_HOLDER)) {
            //取消成功，唤醒等待者
            checkNotifyWaiters();
            //通知监听器
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled0(result);
    }

    @Override
    public boolean isDone() {
        return isDone0(result);
    }

    //sync与await的区别就是 sync会先通过await()等待操作完成，如果操作过程中遇到异常，在抛出异常
    @Override
    public Promise<V> sync() throws InterruptedException {
        await();
        rethrowIfFailed();
        return this;
    }

    //不抛出中断异常的同步操作
    @Override
    public Promise<V> syncUninterruptibly() {
        awaitUninterruptibly();
        rethrowIfFailed();
        return this;
    }

    @Override
    public String toString() {
        return toStringBuilder().toString();
    }

    protected StringBuilder toStringBuilder() {
        StringBuilder buf = new StringBuilder(64)
                .append(StringUtil.simpleClassName(this))
                .append('@')
                .append(Integer.toHexString(hashCode()));

        Object result = this.result;
        if (result == SUCCESS) {
            buf.append("(success)");
        } else if (result == UNCANCELLABLE) {
            buf.append("(uncancellable)");
        } else if (result instanceof CauseHolder) {
            buf.append("(failure: ")
                    .append(((CauseHolder) result).cause)
                    .append(')');
        } else if (result != null) {
            buf.append("(success: ")
                    .append(result)
                    .append(')');
        } else {
            buf.append("(incomplete)");
        }

        return buf;
    }

    /**
     * Get the executor used to notify listeners when this promise is complete.
     * <p>
     * It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     * The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     * depth exceeds a threshold.
     * @return The executor used to notify listeners when this promise is complete.
     */
    protected EventExecutor executor() {
        return executor;
    }

    protected void checkDeadLock() {
        EventExecutor e = executor();
        if (e != null && e.inEventLoop()) {
            throw new BlockingOperationException(toString());
        }
    }

    /**
     * Notify a listener that a future has completed.
     * <p>
     * This method has a fixed depth of {@link #MAX_LISTENER_STACK_DEPTH} that will limit recursion to prevent
     * {@link StackOverflowError} and will stop notifying listeners added after this threshold is exceeded.
     * @param eventExecutor the executor to use to notify the listener {@code listener}.
     * @param future the future that is complete.
     * @param listener the listener to notify.
     */
    protected static void notifyListener(
            EventExecutor eventExecutor, final Future<?> future, final GenericFutureListener<?> listener) {
        checkNotNull(eventExecutor, "eventExecutor");
        checkNotNull(future, "future");
        checkNotNull(listener, "listener");
        notifyListenerWithStackOverFlowProtection(eventExecutor, future, listener);
    }

    private void notifyListeners() {
        EventExecutor executor = executor();
        //确保是在EventLoop中执行
        if (executor.inEventLoop()) {
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            //栈深度判断
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                //入栈：栈深度+1
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    //唤醒监听
                    notifyListenersNow();
                } finally {
                    //出栈：恢复原来的栈深度
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        //提交给EventLoop执行
        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListenersNow();
            }
        });
    }

    /**
     * The logic in this method should be identical to {@link #notifyListeners()} but
     * cannot share code because the listener(s) cannot be cached for an instance of {@link DefaultPromise} since the
     * listener(s) may be changed and is protected by a synchronized operation.
     */
    private static void notifyListenerWithStackOverFlowProtection(final EventExecutor executor,
                                                                  final Future<?> future,
                                                                  final GenericFutureListener<?> listener) {
        if (executor.inEventLoop()) {
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    notifyListener0(future, listener);
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListener0(future, listener);
            }
        });
    }

    private void notifyListenersNow() {
        Object listeners;
        synchronized (this) {
            // Only proceed if there are listeners to notify and we are not already notifying listeners.
            if (notifyingListeners || this.listeners == null) {
                return;
            }
            notifyingListeners = true;
            listeners = this.listeners;
            this.listeners = null;
        }
        //自循环，直到所有监听器均已通知
        for (;;) {
            if (listeners instanceof DefaultFutureListeners) {
                notifyListeners0((DefaultFutureListeners) listeners);
            } else {
                notifyListener0(this, (GenericFutureListener<?>) listeners);
            }
            synchronized (this) {
                //修改状态，确定是否还有监听者
                if (this.listeners == null) {
                    // Nothing can throw from within this method, so setting notifyingListeners back to false does not
                    // need to be in a finally block.
                    notifyingListeners = false;
                    return;
                }
                listeners = this.listeners;
                this.listeners = null;
            }
        }
    }

    private void notifyListeners0(DefaultFutureListeners listeners) {
        GenericFutureListener<?>[] a = listeners.listeners();
        int size = listeners.size();
        for (int i = 0; i < size; i ++) {
            notifyListener0(this, a[i]);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyListener0(Future future, GenericFutureListener l) {
        try {
            l.operationComplete(future);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationComplete()", t);
            }
        }
    }

    //添加监听
    private void addListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners == null) {
            listeners = listener;
        } else if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).add(listener);
        } else {
            listeners = new DefaultFutureListeners((GenericFutureListener<?>) listeners, listener);
        }
    }

    //移除监听
    private void removeListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).remove(listener);
        } else if (listeners == listener) {
            listeners = null;
        }
    }

    //设置成功结果
    private boolean setSuccess0(V result) {
        return setValue0(result == null ? SUCCESS : result);
    }

    //设置失败结果
    private boolean setFailure0(Throwable cause) {
        return setValue0(new CauseHolder(checkNotNull(cause, "cause")));
    }

    //设置结果
    private boolean setValue0(Object objResult) {
        //CAS更新result,如果成功唤醒等待者
        if (RESULT_UPDATER.compareAndSet(this, null, objResult) ||
            RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, objResult)) {
            checkNotifyWaiters();
            return true;
        }
        return false;
    }

    //唤醒等待者
    private synchronized void checkNotifyWaiters() {
        if (waiters > 0) {
            notifyAll();
        }
    }

    //增加waiter
    private void incWaiters() {
        if (waiters == Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters: " + this);
        }
        ++waiters;
    }

    //减少waiter
    private void decWaiters() {
        --waiters;
    }

    //重新抛出异常
    private void rethrowIfFailed() {
        Throwable cause = cause();
        if (cause == null) {
            return;
        }

        PlatformDependent.throwException(cause);
    }

    //等待
    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
        //如果已经完成
        if (isDone()) {
            return true;
        }

        //超时时间到了
        if (timeoutNanos <= 0) {
            return isDone();
        }

        //检查线程是否被中断
        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        checkDeadLock();

        long startTime = System.nanoTime();
        long waitTime = timeoutNanos;
        boolean interrupted = false;
        try {
            //自循环等待，流程和不带超时时间的类似
            for (;;) {
                synchronized (this) {
                    if (isDone()) {
                        return true;
                    }
                    incWaiters();
                    try {
                        wait(waitTime / 1000000, (int) (waitTime % 1000000));
                    } catch (InterruptedException e) {
                        if (interruptable) {
                            throw e;
                        } else {
                            interrupted = true;
                        }
                    } finally {
                        decWaiters();
                    }
                }
                if (isDone()) {
                    return true;
                } else {
                    waitTime = timeoutNanos - (System.nanoTime() - startTime);
                    if (waitTime <= 0) {
                        return isDone();
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Notify all progressive listeners.
     * <p>
     * No attempt is made to ensure notification order if multiple calls are made to this method before
     * the original invocation completes.
     * <p>
     * This will do an iteration over all listeners to get all of type {@link GenericProgressiveFutureListener}s.
     * @param progress the new progress.
     * @param total the total progress.
     */
    /**
     * 处罚GenericProgressiveFutureListener
     * @param progress
     * @param total
     */
    @SuppressWarnings("unchecked")
    void notifyProgressiveListeners(final long progress, final long total) {
        final Object listeners = progressiveListeners();
        if (listeners == null) {
            return;
        }

        final ProgressiveFuture<V> self = (ProgressiveFuture<V>) this;

        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                notifyProgressiveListeners0(
                        self, (GenericProgressiveFutureListener<?>[]) listeners, progress, total);
            } else {
                notifyProgressiveListener0(
                        self, (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners, progress, total);
            }
        } else {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                final GenericProgressiveFutureListener<?>[] array =
                        (GenericProgressiveFutureListener<?>[]) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListeners0(self, array, progress, total);
                    }
                });
            } else {
                final GenericProgressiveFutureListener<ProgressiveFuture<V>> l =
                        (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListener0(self, l, progress, total);
                    }
                });
            }
        }
    }

    /**
     * Returns a {@link GenericProgressiveFutureListener}, an array of {@link GenericProgressiveFutureListener}, or
     * {@code null}.
     */
    private synchronized Object progressiveListeners() {
        Object listeners = this.listeners;
        if (listeners == null) {
            // No listeners added
            return null;
        }

        if (listeners instanceof DefaultFutureListeners) {
            // Copy DefaultFutureListeners into an array of listeners.
            DefaultFutureListeners dfl = (DefaultFutureListeners) listeners;
            int progressiveSize = dfl.progressiveSize();
            switch (progressiveSize) {
                case 0:
                    return null;
                case 1:
                    for (GenericFutureListener<?> l: dfl.listeners()) {
                        if (l instanceof GenericProgressiveFutureListener) {
                            return l;
                        }
                    }
                    return null;
            }

            GenericFutureListener<?>[] array = dfl.listeners();
            GenericProgressiveFutureListener<?>[] copy = new GenericProgressiveFutureListener[progressiveSize];
            for (int i = 0, j = 0; j < progressiveSize; i ++) {
                GenericFutureListener<?> l = array[i];
                if (l instanceof GenericProgressiveFutureListener) {
                    copy[j ++] = (GenericProgressiveFutureListener<?>) l;
                }
            }

            return copy;
        } else if (listeners instanceof GenericProgressiveFutureListener) {
            return listeners;
        } else {
            // Only one listener was added and it's not a progressive listener.
            return null;
        }
    }

    private static void notifyProgressiveListeners0(
            ProgressiveFuture<?> future, GenericProgressiveFutureListener<?>[] listeners, long progress, long total) {
        for (GenericProgressiveFutureListener<?> l: listeners) {
            if (l == null) {
                break;
            }
            notifyProgressiveListener0(future, l, progress, total);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyProgressiveListener0(
            ProgressiveFuture future, GenericProgressiveFutureListener l, long progress, long total) {
        try {
            l.operationProgressed(future, progress, total);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationProgressed()", t);
            }
        }
    }

    //是否取消
    private static boolean isCancelled0(Object result) {
        return result instanceof CauseHolder && ((CauseHolder) result).cause instanceof CancellationException;
    }

    //是否完成
    private static boolean isDone0(Object result) {
        return result != null && result != UNCANCELLABLE;
    }

    private static final class CauseHolder {
        final Throwable cause;
        CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }

    private static void safeExecute(EventExecutor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (Throwable t) {
            rejectedExecutionLogger.error("Failed to submit a listener notification task. Event loop shut down?", t);
        }
    }
}
