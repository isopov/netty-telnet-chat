package com.sopovs.moradanen.netty.chat;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static com.sopovs.moradanen.netty.chat.TelnetServerHandler.*;
import static org.junit.jupiter.api.Assertions.*;


final class TelnetServerTest {
    private EventLoopGroup serverBossGroup;
    private EventLoopGroup serverWorkerGroup;
    private SocketAddress localAddress;

    @BeforeEach
    void setup() throws Exception {
        serverBossGroup = new NioEventLoopGroup(1);
        serverWorkerGroup = new NioEventLoopGroup();
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(serverBossGroup, serverWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new TelnetServerInitializer());

        localAddress = serverBootstrap.bind(0).sync().channel().localAddress();
        assertNotNull(localAddress);
    }

    private int getPort() {
        return ((InetSocketAddress) localAddress).getPort();
    }

    @AfterEach
    void teardown() {
        serverBossGroup.shutdownGracefully();
        serverWorkerGroup.shutdownGracefully();
    }

    @Test
    void testSimple() throws Exception {

        BlockingQueue<String> responsesQueue = new LinkedBlockingQueue<>();

        EventLoopGroup clientGroup = new NioEventLoopGroup();
        try {
            Bootstrap b = new Bootstrap();
            b.group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new TelnetClientInitializer(responsesQueue));

            List<ChannelFuture> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(
                        b.connect("127.0.0.1", getPort())
                                .await().channel().writeAndFlush("/login foo" + i + " bar" + i + "\n")
                                .channel().writeAndFlush("/join foo\n")
                                .await().channel().writeAndFlush("foobar" + i + "\n"));
            }

            for (int i = 0; i < 100; i++) {
                assertTrue(responsesQueue.take().contains("foobar"));
            }

            b.connect("127.0.0.1", getPort())
                    .await().channel().writeAndFlush("/login foo0 wrongpass\n")
                    .await().channel().writeAndFlush("/leave\n")
                    .channel().closeFuture().await();

            assertEquals(WRONG_PASSWORD_MESSAGE, responsesQueue.take());
            assertEquals(BYE_MESSAGE, responsesQueue.take());

            b.connect("127.0.0.1", getPort())
                    .await().channel().writeAndFlush("/login foo12 foo12\n")
                    .await().channel().writeAndFlush("/join foo\n")
                    .await().channel().writeAndFlush("/leave\n")
                    .channel().closeFuture().await();

            assertEquals(CHAT_IS_FULL_MESSAGE, responsesQueue.take());
            assertEquals(BYE_MESSAGE, responsesQueue.take());


            for (ChannelFuture future : futures) {
                future
                        .await().channel().writeAndFlush("/leave\n")
                        .channel().closeFuture().await();
            }

            for (int i = 0; i < 10; i++) {
                assertEquals(BYE_MESSAGE, responsesQueue.take());
            }
        } finally {
            clientGroup.shutdownGracefully();
        }
    }

    @RepeatedTest(10)
    void testChatSize() throws Exception {
        BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        EventLoopGroup clientGroup = new NioEventLoopGroup();
        try {
            Bootstrap b = new Bootstrap();
            TelnetClientInitializer clientInitializer = new TelnetClientInitializer(responses);
            b.group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(clientInitializer);

            List<ChannelFuture> futures = new ArrayList<>();
            for (int i = 0; i < Chat.CHAT_ROOM_CAPACITY * 2; i++) {
                futures.add(
                        b.connect("127.0.0.1", getPort())
                                .await().channel().writeAndFlush("/login foo" + i + " bar" + i + "\n")
                                .channel().writeAndFlush("/join foo\n")
                );
            }

            for (int i = 0; i < Chat.CHAT_ROOM_CAPACITY; i++) {
                assertEquals(CHAT_IS_FULL_MESSAGE, responses.take());
            }
            for (ChannelFuture future : futures) {
                future.channel().writeAndFlush("/leave\n")
                        .channel().closeFuture().await();
            }
        } finally {
            clientGroup.shutdownGracefully();
        }
    }

}

final class TelnetClientInitializer extends ChannelInitializer<SocketChannel> {

    private static final StringDecoder DECODER = new StringDecoder();
    private static final StringEncoder ENCODER = new StringEncoder();
    private final TelnetClientHandler clientHandler;

    TelnetClientInitializer(BlockingQueue<String> responses) {
        clientHandler = new TelnetClientHandler(responses);
    }

    @Override
    public void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));
        pipeline.addLast(DECODER);
        pipeline.addLast(ENCODER);
        pipeline.addLast(clientHandler);
    }
}

@ChannelHandler.Sharable
final class TelnetClientHandler extends SimpleChannelInboundHandler<String> {
    private final BlockingQueue<String> responses;

    TelnetClientHandler(BlockingQueue<String> responses) {
        this.responses = responses;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
        responses.add(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}

