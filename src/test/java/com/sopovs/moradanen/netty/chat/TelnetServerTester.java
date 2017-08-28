package com.sopovs.moradanen.netty.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

public class TelnetServerTester {

	public static Collection<String> test(int port) throws InterruptedException {
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

	public static void main(String[] args) throws InterruptedException {
		for (String response : test(TelnetServer.PORT)) {
			System.out.println(response);
		}
	}
}

class TelnetClientInitializer extends ChannelInitializer<SocketChannel> {

	private static final StringDecoder DECODER = new StringDecoder();
	private static final StringEncoder ENCODER = new StringEncoder();
	private final TelnetClientHandler clientHandler;

	public TelnetClientInitializer(Collection<String> responces) {
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

@Sharable
class TelnetClientHandler extends SimpleChannelInboundHandler<String> {
	private final Collection<String> responces;

	public TelnetClientHandler(Collection<String> responces) {
		this.responces = responces;
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
		responces.add(msg);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		cause.printStackTrace();
		ctx.close();
	}
}
