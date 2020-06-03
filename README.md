# Netty Source Read Project

Netty 源码阅读计划

## 项目各模块说明 
项目各模块主要功能说明
 * `io.netty.all` 
 * `io.netty.buffer` 字节缓冲区
 * `io.netty.codec` 特殊的Handler，负责处理半包/粘包，编解码(字节<->协议映射)等
 * `io.netty.codec.dns`
 * `io.netty.codec.haproxy`
 * `io.netty.codec.http`
 * `io.netty.codec.http2`
 * `io.netty.codec.memcache`
 * `io.netty.codec.mqtt`
 * `io.netty.codec.redis`
 * `io.netty.codec.smtp`
 * `io.netty.codec.socks`
 * `io.netty.codec.stomp`
 * `io.netty.codec.xml`
 * `io.netty.common` 通用模块，提供特殊线程池，工具类等
 * `io.netty.handler` 消息处理器，负责在`pipeline`中加工消息
 * `io.netty.handler.proxy` 
 * `io.netty.resolver` 解析器，负责解析IP/HOST
 * `io.netty.resolver.dns`
 * `io.netty.transport` 传输层，Netty的核心组件
 * `io.netty.transport.epoll` (`native` omitted - reserved keyword in Java)
 * `io.netty.transport.kqueue` (`native` omitted - reserved keyword in Java)
 * `io.netty.transport.unix.common` (`native` omitted - reserved keyword in Java)
 * `io.netty.transport.rxtx`
 * `io.netty.transport.sctp`
 * `io.netty.transport.udt`

## 主要阅读模块及已完成明细
主要要阅读的模块基本上涵盖和Netty-all中的内容，其中部分模块名称略有不同，读者可以自己对应下。
### io.netty.buffer  
    - io.netty.buffer
        - AbstractByteBuf
        - AbstractReferenceCountedByteBuf
        - ByteBuf  
        - UnpooledDirectByteBuf
        - UnpooledHeapByteBuf

### io.netty.codec  

### io.netty.common  
    - io.netty.util
        - concurrent
            - AbstractFuture
            - DefaultPromis
            - Future
            - Promise
        - ReferenceCounted

### io.netty.handler  

### io.netty.transport
    - io.netty
            - bootstrap 
                - AbstractBootstap
                - AbstractBootstrapConfig
                - Bootstrap
                - BootstrapConfig
                - ServerBootstrap
                - ServerBootstrapConfig
            - channel
                - nio
                    - AbstractNioByteChannel
                    - AbstractNioChannel  
                    - AbstractNioMessageChannel
                - socket
                    - nio
                        - NioSocketChannel
                        - NioSocketServerChannel
                    - DuplexChannel  
                    - SocketChannel 
                - AbstractChannel
                - AbstractChannelHandlerContext
                - AbstractServerChannel
                - Channel 
                - ChannelFuture 
                - ChannelHandlerContext
                - ChannelInboundInvoker
                - ChannelOutboundInvoker
                - ChannelPipeline 
                - ChannelPromise 
                - DefaultChannelHandlerContext
                - DefaultChannelPipeline
                - DefaultChannelPromise
                - ReflectiveChannelFactory
                        
