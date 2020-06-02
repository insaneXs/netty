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

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Abstract {@link Future} implementation which does not allow for cancellation.
 *
 * @param <V>
 */

/**
 * Future接口的抽象实现，实现了get()方法
 * @param <V>
 */
public abstract class AbstractFuture<V> implements Future<V> {

    //阻塞等待
    @Override
    public V get() throws InterruptedException, ExecutionException {
        //等待操作完成
        await();
        //获取操作是否有异常
        Throwable cause = cause();
        //没有异常返回操作结果，否则抛出异常
        if (cause == null) {
            return getNow();
        }
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        //等待操作完成
        if (await(timeout, unit)) {
            //操作过程中是否发生异常
            Throwable cause = cause();
            //没有 则返回结果
            if (cause == null) {
                return getNow();
            }
            //否则抛出异常
            if (cause instanceof CancellationException) {
                throw (CancellationException) cause;
            }
            throw new ExecutionException(cause);
        }//超时 抛出超时一场
        throw new TimeoutException();
    }
}
