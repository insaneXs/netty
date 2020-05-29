/*
 * Copyright 2012 The Netty Project
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
package io.netty.channel.socket;

import java.net.InetSocketAddress;

/**
 * A TCP/IP socket {@link Channel}.
 */

/**
 * 负责TCP/IP的一种Channel，
 * 它和jdk nio中的SocketChannel名字相同，但这是Netty中的概念，二者定义的行为是不一样的，
 */
public interface SocketChannel extends DuplexChannel {
    //获取对应的ServerSocketChannel
    @Override
    ServerSocketChannel parent();
    //获取相关配置
    @Override
    SocketChannelConfig config();
    //本地地址
    @Override
    InetSocketAddress localAddress();
    //远端地址
    @Override
    InetSocketAddress remoteAddress();
}
