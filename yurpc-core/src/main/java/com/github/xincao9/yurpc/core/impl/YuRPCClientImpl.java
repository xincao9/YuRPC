/*
 * Copyright 2020 xincao9@gmail.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.xincao9.yurpc.core.impl;

import com.github.xincao9.yurpc.core.YuRPCClient;
import com.github.xincao9.yurpc.core.config.ClientConfig;
import com.github.xincao9.yurpc.core.protocol.Request;
import com.github.xincao9.yurpc.core.protocol.Response;
import com.github.xincao9.yurpc.core.codec.StringDecoder;
import com.github.xincao9.yurpc.core.codec.StringEncoder;
import com.github.xincao9.yurpc.core.constant.ResponseCode;
import com.github.xincao9.yurpc.core.protocol.Endpoint;
import com.github.xincao9.yurpc.core.DiscoveryService;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端
 *
 * @author xincao9@gmail.com
 */
public class YuRPCClientImpl implements YuRPCClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(YuRPCClientImpl.class);
    private final Map<Long, Request> requests = new ConcurrentHashMap();
    private final Bootstrap bootstrap = new Bootstrap();
    private final Map<String, Channel> addressChannel = new HashMap();
    private EventLoopGroup workerGroup;
    private ClientInvocationHandler clientInvocationHandler;
    private DiscoveryService discoveryService;

    /**
     * 构造器
     *
     */
    public YuRPCClientImpl() {
        this(null);
    }

    /**
     * 构造器
     *
     * @param discoveryService 服务组件
     */
    public YuRPCClientImpl(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    /**
     * 启动
     *
     * @throws java.lang.Throwable 异常
     */
    @Override
    public void start() throws Throwable {
        this.workerGroup = Epoll.isAvailable() ? new EpollEventLoopGroup(Runtime.getRuntime().availableProcessors()) : new NioEventLoopGroup(Runtime.getRuntime().availableProcessors());
        ClientHandler clientHandler = new ClientHandler();
        clientHandler.setYuRPCClient(this);
        this.bootstrap.group(this.workerGroup).channel(Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ClientConfig.connectionTimeoutMS)
                .option(ChannelOption.SO_SNDBUF, 65535)
                .option(ChannelOption.SO_RCVBUF, 65535)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(
                                new StringEncoder(),
                                new StringDecoder(),
                                clientHandler,
                                new IdleStateHandler(0, 0, 60, TimeUnit.SECONDS),
                                new HeartbeatHandler()
                        );
                    }
                });
        clientInvocationHandler = new ClientInvocationHandler();
        clientInvocationHandler.setYuRPCClient(this);
    }

    /**
     * 关闭
     *
     * @throws java.lang.Throwable 异常
     */
    @Override
    public void shutdown() throws Throwable {
        this.addressChannel.entrySet().forEach((entry) -> {
            String address = entry.getKey();
            Channel channel = entry.getValue();
            if (channel != null) {
                channel.close().addListener((Future<? super Void> future) -> {
                    LOGGER.warn("close the connection to the yurpc service address = {}, cause = {}", address, future.cause());
                });
            }
        });
        if (this.workerGroup != null) {
            try {
                this.workerGroup.shutdownGracefully().sync();
            } catch (InterruptedException ex) {
                LOGGER.error(ex.getMessage());
            }
        }
    }

    /**
     * 调用方法
     *
     * @param <T> 类型
     * @param request 请求
     * @return 调用结果
     * @throws Throwable 异常
     */
    @Override
    public <T> Response<T> invoke(Request request) throws Throwable {
        Objects.requireNonNull(request);
        if (discoveryService != null && !request.isDirect()) {
            List<Endpoint> nodes = discoveryService.query(StringUtils.substringBeforeLast(request.getMethod(), "."));
            if (nodes != null && !nodes.isEmpty()) {
                Endpoint node = nodes.get(RandomUtils.nextInt(0, nodes.size()));
                request.setHost(node.getHost());
                request.setPort(node.getPort());
            }
        }
        Channel channel = getChannel(request.getHost(), request.getPort());
        if (channel == null) {
            return Response.createResponse(request.getId(), ResponseCode.CONNECTION_FAILURE, ResponseCode.CONNECTION_FAILURE_MSG);
        }
        this.requests.put(request.getId(), request);
        channel.writeAndFlush(request.toString()).addListener((ChannelFutureListener) (ChannelFuture f) -> {
            if (f.isSuccess()) {
                request.setSendOk(Boolean.TRUE);
                return;
            }
            request.setSendOk(Boolean.FALSE);
            this.requests.remove(request.getId());
            request.putResponse(null);
            LOGGER.error("yurpc.invoke() request = {} failure exception = {}", request, f.cause());
        });
        try {
            return request.waitResponse(ClientConfig.invokeTimeoutMS, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            LOGGER.error(e.getMessage());
        }
        this.requests.remove(request.getId());
        request.putResponse(null);
        LOGGER.error("yurpc.invoke() request = {} timeout (wait time > {} ms)", request, ClientConfig.invokeTimeoutMS);
        return Response.createResponse(request.getId(), ResponseCode.INVOKE_TIMEOUT, ResponseCode.INVOKE_TIMEOUT_MSG);
    }

    /**
     * 获取通道
     *
     * @param host 主机
     * @param port 端口
     * @return 通道
     */
    private Channel getChannel(String host, int port) {
        String address = String.format("%s:%d", host, port);
        if (!this.addressChannel.containsKey(address)) {
            synchronized (this) {
                if (!this.addressChannel.containsKey(address)) {
                    ChannelFuture channelFuture = this.bootstrap.connect(new InetSocketAddress(host, port));
                    if (channelFuture.awaitUninterruptibly(ClientConfig.connectionTimeoutMS)) {
                        if (channelFuture.channel().isActive()) {
                            YuRPCClientImpl.this.addressChannel.put(address, channelFuture.channel());
                        }
                    }
                }
            }
        }
        Channel channel = this.addressChannel.get(address);
        if (channel == null) {
            LOGGER.error("getChannel() host = {} port = {} channel is null", host, port);
            return null;
        }
        if (!channel.isActive()) {
            this.addressChannel.remove(address);
            LOGGER.error("getChannel() host = {} port = {} channel is inactive", host, port);
            return null;
        }
        return channel;
    }

    /**
     * 获得请求对象容器
     *
     * @return 请求对象容器
     */
    @Override
    public Map<Long, Request> getRequests() {
        return this.requests;
    }

    /**
     * 获得接口的代理
     *
     * @param <T> 类型
     * @param clazz 接口
     * @return 代理对象
     */
    @Override
    public <T> T proxy(Class<T> clazz) {
        return this.clientInvocationHandler.proxy(clazz);
    }

    /**
     * 修改器
     *
     * @param discoveryService 服务发现和注册组件
     */
    @Override
    public void setDiscoveryService(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

}
