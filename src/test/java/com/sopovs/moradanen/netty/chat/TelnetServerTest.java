package com.sopovs.moradanen.netty.chat;

import static com.sopovs.moradanen.netty.chat.TelnetServerHandler.CHAT_IS_FULL_MESSAGE;
import static com.sopovs.moradanen.netty.chat.TelnetServerHandler.WRONG_PASSWORD_MESSAGE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public final class TelnetServerTest {
	private EventLoopGroup serverBossGroup;
	private EventLoopGroup serverWorkerGroup;
	private SocketAddress localAddress;

	@Before
	public void setup() throws Exception {
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

	@After
	public void teardown() {
		serverBossGroup.shutdownGracefully();
		serverWorkerGroup.shutdownGracefully();
	}

	@Test
	public void test() throws Exception {
		Collection<String> responses = TelnetServerTester.test(getPort());
		assertEquals(12, responses.stream().filter("Bye!"::equals).count());
		assertEquals(1, responses.stream().filter(WRONG_PASSWORD_MESSAGE::equals).count());
		assertEquals(1, responses.stream().filter(CHAT_IS_FULL_MESSAGE::equals).count());
		// Каждый из 10 клиентов должен получить все 10 сообщений - либо как
		// историю, либо как нормальные сообщения после того, как подключился
		assertEquals(100, responses.stream().filter(r -> r.contains("foobar")).count());

	}

}

final class TelnetServerTester {

	static Collection<String> test(int port) throws InterruptedException {
		ConcurrentLinkedQueue<String> responces = new ConcurrentLinkedQueue<>();

		EventLoopGroup clientGroup = new NioEventLoopGroup();
		try {
			Bootstrap b = new Bootstrap();
			b.group(clientGroup)
					.channel(NioSocketChannel.class)
					.handler(new TelnetClientInitializer(responces));

			List<ChannelFuture> futures = new ArrayList<>();
			for (int i = 1; i <= 10; i++) {
				futures.add(
						b.connect("127.0.0.1", port)
								.await().channel().writeAndFlush("/login foo" + i + " bar" + i + "\n")
								.channel().writeAndFlush("/join foo\n")
								.await().channel().writeAndFlush("foobar" + i + "\n"));
			}

			b.connect("127.0.0.1", port)
					.await().channel().writeAndFlush("/login foo1 wrongpass\n")
					.await().channel().writeAndFlush("/leave\n")
					.channel().closeFuture().await();

			b.connect("127.0.0.1", port)
					.await().channel().writeAndFlush("/login foo12 foo12\n")
					.await().channel().writeAndFlush("/join foo\n")
					.await().channel().writeAndFlush("/leave\n")
					.channel().closeFuture().await();

			for (ChannelFuture future : futures) {
				future
						.await().channel().writeAndFlush("/leave\n")
						.channel().closeFuture().await();
			}

			return responces;

		} finally {
			clientGroup.shutdownGracefully();
		}
	}
}

final class TelnetClientInitializer extends ChannelInitializer<SocketChannel> {

	private static final StringDecoder DECODER = new StringDecoder();
	private static final StringEncoder ENCODER = new StringEncoder();
	private final TelnetClientHandler clientHandler;

	TelnetClientInitializer(Collection<String> responces) {
		clientHandler = new TelnetClientHandler(responces);
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
	private final Collection<String> responses;

	TelnetClientHandler(Collection<String> responses) {
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

