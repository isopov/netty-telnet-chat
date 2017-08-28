package com.sopovs.moradanen.netty.chat;

import static com.sopovs.moradanen.netty.chat.TelnetServerHandler.CHAT_IS_FULL_MESSAGE;
import static com.sopovs.moradanen.netty.chat.TelnetServerHandler.WRONG_PASSWORD_MESSAGE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
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
		assertEquals(12, responses.stream().filter(r -> "Bye!".equals(r)).count());
		assertEquals(1, responses.stream().filter(r -> WRONG_PASSWORD_MESSAGE.equals(r)).count());
		assertEquals(1, responses.stream().filter(r -> CHAT_IS_FULL_MESSAGE.equals(r)).count());
		// Каждый из 10 клиентов должен получить все 10 сообщений - либо как
		// историю, либо как нормальные сообщения после того, как подключился
		assertEquals(100, responses.stream().filter(r -> r.contains("foobar")).count());

	}

}
