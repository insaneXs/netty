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

/**
 * The helper classes with fluent API which enable an easy implementation of
 * typical client side and server side channel initialization.
 */
/**
 * 这个包是启动引导类相关的包，用户创建应用的主入口
 * Bootstrap根据服务端还是客户端分成ServerBootstrap和Bootstrap两类，AbstractBootstrap是这两类的抽象父类
 * 与之对应的其配置项对象也分成两类ServerBootstrapConfig和BootstrapConfig两类，AbstractBootstrapConfig是这两类的抽象父类
 */
package io.netty.bootstrap;
