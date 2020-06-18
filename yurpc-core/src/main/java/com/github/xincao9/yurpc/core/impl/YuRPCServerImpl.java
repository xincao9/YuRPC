/*
 * Copyright 2019 xincao9@gmail.com.
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

import com.github.xincao9.yurpc.core.YuRPCServer;
import com.github.xincao9.yurpc.core.codec.StringDecoder;
import com.github.xincao9.yurpc.core.codec.StringEncoder;
import com.github.xincao9.yurpc.core.config.ServerConfig;
import com.github.xincao9.yurpc.core.DiscoveryService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.xincao9.yurpc.core.protocol.Endpoint;

/**
 * 服务组件
 *
 * @author xincao9@gmail.com
 */
public class YuRPCServerImpl implements YuRPCServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(YuRPCServerImpl.class);

    private final Integer port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final Integer boss;
    private final Integer worker;
    private DiscoveryService discoveryService;

    /**
     * 构造器
     */
    public YuRPCServerImpl() {
        this(null);
    }

    /**
     * 构造器
     *
     * @param port             端口
     * @param discoveryService 服务组件
     */
    public YuRPCServerImpl(Integer port, DiscoveryService discoveryService) {
        this.port = port;
        this.boss = ServerConfig.ioThreadBoss;
        this.worker = ServerConfig.ioThreadWorker;
        this.discoveryService = discoveryService;
    }

    /**
     * 构造器
     *
     * @param discoveryService 服务组件
     */
    public YuRPCServerImpl(DiscoveryService discoveryService) {
        this.port = ServerConfig.port;
        this.boss = ServerConfig.ioThreadBoss;
        this.worker = ServerConfig.ioThreadWorker;
        this.discoveryService = discoveryService;
    }

    /**
     * 启动
     *
     * @throws Throwable
     */
    @Override
    public void start() throws Throwable {
        this.workerGroup = Epoll.isAvailable() ? new EpollEventLoopGroup(this.worker) : new NioEventLoopGroup(this.worker);
        ServerBootstrap bootstrap = new ServerBootstrap();
        ServerHandler serverHandler = new ServerHandler();
        serverHandler.setYuRPCServer(this);
        if (this.boss == 0) {
            bootstrap.group(this.workerGroup);
        } else {
            this.bossGroup = Epoll.isAvailable() ? new EpollEventLoopGroup(this.boss) : new NioEventLoopGroup(this.boss);
            bootstrap.group(this.bossGroup, this.workerGroup);
        }
        bootstrap.channel(Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(
                        new StringEncoder(),
                        new StringDecoder(),
                        serverHandler,
                        new IdleStateHandler(0, 0, 60, TimeUnit.SECONDS),
                        new HeartbeatHandler()
                    );
                }
            })
            .childOption(ChannelOption.SO_KEEPALIVE, false)
            .childOption(ChannelOption.TCP_NODELAY, true);
        ChannelFuture f = bootstrap.bind("0.0.0.0", port).addListener((Future<? super Void> future) -> {
            LOGGER.warn("start the yurpc service port = {}, cause = {}", this.port, future.cause());
        });
        f.channel().closeFuture().addListener((Future<? super Void> future) -> {
            LOGGER.warn("turn off yurpc service port = {}, cause = {}", this.port, future.cause());
        });
    }

    /**
     * 关闭
     *
     * @throws Throwable
     */
    @Override
    public void shutdown() throws Throwable {
        if (this.workerGroup != null) {
            this.workerGroup.shutdownGracefully();
        }
        if (this.bossGroup != null) {
            this.bossGroup.shutdownGracefully();
        }
    }

    private final Map<String, Object> componentes = new HashMap();

    /**
     * 服务注册
     *
     * @param <T> 组建类型
     * @param obj 服务组件
     */
    @Override
    public <T> void register(T obj) {
        Objects.requireNonNull(obj);
        Class<?>[] clazzes = obj.getClass().getInterfaces();
        if (clazzes == null || clazzes.length <= 0) {
            LOGGER.error("class = {} invalid format", obj.getClass().getCanonicalName());
            return;
        }
        for (Class clazz : clazzes) {
            componentes.put(clazz.getTypeName(), obj);
            if (discoveryService != null) {
                discoveryService.register(Endpoint.create(clazz.getTypeName()));
            }
        }
    }

    /**
     * 获取组建
     *
     * @param name 组建类型名
     * @return 服务组件
     */
    @Override
    public Object getBean(String name) {
        return componentes.get(name);
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
