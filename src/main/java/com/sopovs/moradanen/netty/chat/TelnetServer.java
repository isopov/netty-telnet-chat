package com.sopovs.moradanen.netty.chat;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.google.common.base.Splitter;
import com.google.common.collect.EvictingQueue;
import com.google.common.collect.Queues;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.ThreadLocalRandom;

public final class TelnetServer {

	static final int PORT = 4567;

	public static void main(String[] args) throws Exception {
		EventLoopGroup bossGroup = new NioEventLoopGroup(1);
		EventLoopGroup workerGroup = new NioEventLoopGroup();
		try (AutoCloseable bossGroupClose = bossGroup::shutdownGracefully;
				AutoCloseable woorkerGroupClose = workerGroup::shutdownGracefully) {
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
					.handler(new LoggingHandler(LogLevel.INFO)).childHandler(new TelnetServerInitializer());

			b.bind(PORT).sync().channel().closeFuture().sync();
		}
	}
}

@Sharable
final class TelnetServerHandler extends SimpleChannelInboundHandler<String> {
	static final String WRONG_PASSWORD_MESSAGE = "Wrong password!";
	static final String CHAT_IS_FULL_MESSAGE = "Chat is full!";
	static final String LOGIN_MESSAGE = "Please login!";
	static final String JOIN_CHAT_MESSAGE = "Please join some chat!";
	static final AttributeKey<String> USER_NAME = AttributeKey.valueOf("username");
	static final AttributeKey<String> CHAT_NAME = AttributeKey.valueOf("chatname");

	private final ConcurrentHashMap<String, Chat> chats = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();

	@Override
	public void channelRead0(ChannelHandlerContext ctx, String request) throws Exception {
		String username = ctx.channel().attr(USER_NAME).get();
		String channelName = ctx.channel().attr(CHAT_NAME).get();

		if (request.startsWith("/")) {
			handleChatCommand(ctx, request, username, channelName);
		} else if (username == null) {
			ctx.write(LOGIN_MESSAGE + "\n");
		} else if (channelName == null) {
			ctx.write(JOIN_CHAT_MESSAGE + "\n");
		} else {
			chats.get(channelName).sendMessage(username, request);
		}
	}

	private void handleChatCommand(ChannelHandlerContext ctx, String request, String username, String chatName) {
		if ("/leave".equals(request)) {
			leaveChatIfAny(ctx, chatName);
			ctx.write("Bye!\n").addListener(ChannelFutureListener.CLOSE);
		} else if (username == null && !request.startsWith("/login ")) {
			ctx.write(LOGIN_MESSAGE + "\n");
		} else if (request.startsWith("/login ")) {
			leaveChatIfAny(ctx, chatName);
			handleLogin(ctx, request);
		} else if (chatName == null && !request.startsWith("/join ")) {
			ctx.write(JOIN_CHAT_MESSAGE + "\n");
		} else if (request.startsWith("/join ")) {
			handleJoin(ctx, request, chatName);
		} else if ("/users".equals(request)) {
			ctx.write(chats.get(chatName).listAllUsers());
		} else {
			ctx.write("Unknown command!\n");
		}
	}

	private void handleJoin(ChannelHandlerContext ctx, String request, String chatName) {
		List<String> split = Splitter.on(' ').splitToList(request);
		if (split.size() != 2) {
			ctx.write("Use '/join chatname' to join chat\n");
		} else {
			leaveChatIfAny(ctx, chatName);
			chatName = split.get(1);
			Chat chat = chats.computeIfAbsent(chatName, Chat::new);
			if (!chat.join(ctx.channel())) {
				ctx.write(CHAT_IS_FULL_MESSAGE + "\n");
			}
		}
	}

	private void handleLogin(ChannelHandlerContext ctx, String request) {
		List<String> split = Splitter.on(' ').splitToList(request);
		if (split.size() != 3) {
			ctx.write("Use '/login username password' to login\n");
		} else {
			String loginUsername = split.get(1);
			String password = split.get(2);
			User user = users.computeIfAbsent(loginUsername, name -> new User(password));
			if (user.validPassword(password)) {
				ctx.channel().attr(USER_NAME).set(loginUsername);
			} else {
				ctx.write(WRONG_PASSWORD_MESSAGE + "\n");
			}
		}
	}

	private void leaveChatIfAny(ChannelHandlerContext ctx, String channelName) {
		if (channelName != null) {
			chats.get(channelName).leave(ctx.channel());
		}
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) {
		ctx.flush();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		cause.printStackTrace();
		ctx.close();
	}
}

final class User {
	private final byte[] salt = new byte[16];
	private final byte[] passwordHash;

	public User(String password) {
		ThreadLocalRandom.current().nextBytes(salt);
		passwordHash = hash(password);
	}

	public boolean validPassword(String password) {
		byte[] attemptHash = hash(password);
		// possible timing attack?
		return Arrays.equals(passwordHash, attemptHash);
	}

	private byte[] hash(String password) {
		KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 128);
		try {
			SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
			return f.generateSecret(spec).getEncoded();
		} catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

}

final class Chat {
	private static final int CHAT_HISTORY_SIZE = 10;
	private static final int CHAT_ROOM_CAPACITY = 10;
	final String name;
	final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
	final Queue<String> lastMessages = Queues.synchronizedQueue(EvictingQueue.create(CHAT_HISTORY_SIZE));

	public Chat(String name) {
		this.name = name;
	}

	public void sendMessage(String username, String message) {
		String messageWithAuthor = "[" + username + "]: " + message + "\n";
		synchronized (lastMessages) {
			channels.writeAndFlush(messageWithAuthor);
			lastMessages.add(messageWithAuthor);
		}
	}

	public boolean join(Channel channel) {
		if (channels.size() >= CHAT_ROOM_CAPACITY) {
			return false;
		}
		channel.attr(TelnetServerHandler.CHAT_NAME).set(name);
		// see javadoc to Queues.synchronizedQueue
		synchronized (lastMessages) {
			channels.add(channel);
			for (String message : lastMessages) {
				channel.write(message);
			}
		}
		return true;
	}

	public void leave(Channel channel) {
		channels.remove(channel);
	}

	public String listAllUsers() {
		return channels.stream().map(c -> c.attr(TelnetServerHandler.USER_NAME).get()).collect(Collectors.joining(", "))
				+ "\n";
	}
}

final class TelnetServerInitializer extends ChannelInitializer<SocketChannel> {

	private static final StringDecoder DECODER = new StringDecoder();
	private static final StringEncoder ENCODER = new StringEncoder();

	private final TelnetServerHandler serverHandler = new TelnetServerHandler();

	@Override
	public void initChannel(SocketChannel ch) throws Exception {
		ChannelPipeline pipeline = ch.pipeline();
		pipeline.addLast(new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));
		pipeline.addLast(DECODER);
		pipeline.addLast(ENCODER);
		pipeline.addLast(serverHandler);
	}
}
