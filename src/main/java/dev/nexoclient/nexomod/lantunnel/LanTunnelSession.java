package dev.nexoclient.nexomod.lantunnel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * A QUIC tunnel from this (host's) local Minecraft server to a public relay,
 * so players anywhere can join without port forwarding. Adapted from e4mc's
 * {@code QuiclimeSession} (https://github.com/vgskye/e4mc-minecraft-architectury,
 * MIT) — see Mod/THIRD-PARTY-NOTICES.md. Stripped of Dialtone (e4mc's
 * optional direct peer-to-peer path): this only ever tunnels through the
 * relay, which is sufficient for "anyone can join" and needs no native P2P
 * library on either side. The relay's own capability advertisement is
 * still parsed so a future Dialtone add-on could hook back in without
 * protocol changes.
 */
public class LanTunnelSession {
	private static final Gson GSON = new Gson();
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/lantunnel");

	final ChannelHandler handler;
	final EventLoopGroup group;
	private DatagramChannel datagramChannel;
	private QuicChannel quicChannel;

	public State state = State.STARTING;
	public Throwable failureCause = null;

	public enum State {
		STARTING,
		STARTED,
		UNHEALTHY,
		STOPPING,
		STOPPED
	}

	static final class RelayInfo {
		String id;
		String host;
		int port;
	}

	public LanTunnelSession(ChannelHandler handler, EventLoopGroup group) {
		this.handler = handler;
		this.group = group;
	}

	public void startAsync() {
		Thread thread = new Thread(this::start, "nexomod-lantunnel-init");
		thread.setDaemon(true);
		thread.start();
	}

	private static RelayInfo getRelay() throws Exception {
		LanTunnelConfig cfg = LanTunnelConfig.INSTANCE;
		if (cfg.useBroker) {
			HttpClient httpClient = HttpClient.newHttpClient();
			HttpRequest request = HttpRequest.newBuilder(new URI(cfg.brokerUrl))
					.header("Accept", "application/json")
					.build();
			LOGGER.info("broker req: {}", request);
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			LOGGER.info("broker resp: {}", response);
			if (response.statusCode() != 200) {
				throw new RuntimeException("broker returned status " + response.statusCode());
			}
			return GSON.fromJson(response.body(), RelayInfo.class);
		} else {
			RelayInfo info = new RelayInfo();
			info.id = "custom";
			info.host = cfg.relayHost;
			info.port = cfg.relayPort;
			return info;
		}
	}

	public void start() {
		try {
			RelayInfo relayInfo = getRelay();
			LOGGER.info("using relay {}", relayInfo.id);

			QuicSslContext context = QuicSslContextBuilder.forClient()
					.applicationProtocols("quiclime")
					.build();
			ChannelHandler codec = new QuicClientCodecBuilder()
					.sslContext(context)
					.sslEngineProvider(it -> context.newEngine(it.alloc(), relayInfo.host, relayInfo.port))
					.initialMaxStreamsBidirectional(512)
					.maxIdleTimeout(10, TimeUnit.SECONDS)
					.initialMaxData(4611686018427387903L)
					.initialMaxStreamDataBidirectionalRemote(1250000)
					.initialMaxStreamDataBidirectionalLocal(1250000)
					.initialMaxStreamDataUnidirectional(1250000)
					.build();

			Class<? extends DatagramChannel> channelClass = resolveDatagramChannelClass(group);

			new Bootstrap()
					.group(group)
					.channel(channelClass)
					.handler(codec)
					.bind(0)
					.addListener(datagramChannelFuture -> {
						if (!datagramChannelFuture.isSuccess()) {
							fail(datagramChannelFuture.cause());
							return;
						}
						datagramChannel = (DatagramChannel) ((ChannelFuture) datagramChannelFuture).channel();
						connectToRelay(relayInfo);
					});
		} catch (Throwable e) {
			fail(e);
		}
	}

	private static Class<? extends DatagramChannel> resolveDatagramChannelClass(EventLoopGroup group) {
		if (group instanceof EpollEventLoopGroup) {
			return EpollDatagramChannel.class;
		} else if (group instanceof KQueueEventLoopGroup) {
			return KQueueDatagramChannel.class;
		} else if (group instanceof NioEventLoopGroup) {
			return NioDatagramChannel.class;
		} else if (group instanceof MultiThreadIoEventLoopGroup mig) {
			if (mig.isIoType(EpollIoHandler.class)) {
				return EpollDatagramChannel.class;
			} else if (mig.isIoType(KQueueIoHandler.class)) {
				return KQueueDatagramChannel.class;
			} else if (mig.isIoType(NioIoHandler.class)) {
				return NioDatagramChannel.class;
			}
			throw new RuntimeException("Unknown IO type for EventLoopGroup " + group.getClass().getName());
		}
		throw new RuntimeException("Unknown EventLoopGroup " + group.getClass().getName());
	}

	private void connectToRelay(RelayInfo relayInfo) throws Exception {
		QuicChannel.newBootstrap(datagramChannel)
				.streamHandler(handler)
				.handler(new ChannelInboundHandlerAdapter() {
					@Override
					public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
						fail(cause);
					}

					@Override
					public void channelInactive(ChannelHandlerContext ctx) {
						state = State.STOPPED;
					}
				})
				.remoteAddress(new InetSocketAddress(InetAddress.getByName(relayInfo.host), relayInfo.port))
				.connect()
				.addListener(quicChannelFuture -> {
					if (!quicChannelFuture.isSuccess()) {
						fail(quicChannelFuture.cause());
						return;
					}
					quicChannel = (QuicChannel) quicChannelFuture.get();
					openControlStream(relayInfo);
				});
	}

	private void openControlStream(RelayInfo relayInfo) {
		quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInitializer<QuicStreamChannel>() {
			@Override
			protected void initChannel(QuicStreamChannel ch) {
				ch.pipeline().addLast(new ControlMessageCodec(), new SimpleChannelInboundHandler<ControlMessageCodec.ControlMessage>() {
					@Override
					protected void channelRead0(ChannelHandlerContext ctx, ControlMessageCodec.ControlMessage msg) {
						handleControlMessage(msg);
					}
				});
			}
		}).addListener(it -> {
			if (!it.isSuccess()) {
				fail(it.cause());
				return;
			}
			QuicStreamChannel streamChannel = (QuicStreamChannel) it.getNow();
			LOGGER.info("control channel open: {}", streamChannel);
			streamChannel.writeAndFlush(new ControlMessageCodec.ProbeCapabilitiesMessageServerbound());
			streamChannel.writeAndFlush(new ControlMessageCodec.RequestDomainAssignmentMessageServerbound());
			quicChannel.closeFuture().addListener(ignored -> datagramChannel.close());
		});
	}

	private void handleControlMessage(ControlMessageCodec.ControlMessage msg) {
		if (msg instanceof ControlMessageCodec.DomainAssignmentCompleteMessageClientbound domainMsg) {
			state = State.STARTED;
			String domain = domainMsg.domain;
			LOGGER.info("Domain assigned: {}", domain);
			announceDomain(domain);
		} else if (msg instanceof ControlMessageCodec.RequestMessageBroadcastMessageClientbound broadcastMsg) {
			addMessage(Component.literal(broadcastMsg.message));
		}
		// HasCapabilitiesMessageClientbound (e.g. "dialtone_sidecar") is
		// intentionally not acted on — see class doc.
	}

	private void announceDomain(String domain) {
		MutableComponent domainComponent = Component.literal(domain)
				.withStyle(style -> style
						.withClickEvent(new ClickEvent.CopyToClipboard(domain))
						.withColor(ChatFormatting.GREEN)
						.withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.copy.click"))));
		Component message = Component.translatable("text.nexomod.lantunnel.domainAssigned", domainComponent)
				.append(Component.literal(" "))
				.append(Component.translatable("text.nexomod.lantunnel.clickToStop")
						.withStyle(style -> style
								.withClickEvent(new ClickEvent.RunCommand("/nexolan stop"))
								.withColor(ChatFormatting.GRAY)));
		addMessage(message);
	}

	private static void addMessage(Component message) {
		Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.getChat().addClientSystemMessage(message));
	}

	private void fail(Throwable e) {
		state = State.UNHEALTHY;
		failureCause = e;
		LOGGER.error("error in nexomod lantunnel", e);
		addMessage(Component.translatable("text.nexomod.lantunnel.error"));
	}

	private static void afterCloseIfPresent(Channel channel, Consumer<Boolean> callback) {
		if (channel == null) {
			callback.accept(false);
		} else {
			channel.close().addListener(it -> callback.accept(true));
		}
	}

	public void stop() {
		state = State.STOPPING;
		afterCloseIfPresent(quicChannel, a -> afterCloseIfPresent(datagramChannel, b -> state = State.STOPPED));
	}

	private static ByteBuf writeVarInt(ByteBuf buf, int value) {
		while ((value & 0xFFFFFF80) != 0) {
			buf.writeByte(value & 0x7F | 0x80);
			value >>>= 7;
		}
		buf.writeByte(value);
		return buf;
	}

	private static final class ControlMessageCodec extends ByteToMessageCodec<ControlMessageCodec.ControlMessage> {
		interface ControlMessage {}

		static final class ProbeCapabilitiesMessageServerbound implements ControlMessage {
			final String kind = "probe_capabilities";
		}

		static final class RequestDomainAssignmentMessageServerbound implements ControlMessage {
			final String kind = "request_domain_assignment";
		}

		static final class DomainAssignmentCompleteMessageClientbound implements ControlMessage {
			String kind;
			String domain;
		}

		static final class RequestMessageBroadcastMessageClientbound implements ControlMessage {
			String kind;
			String message;
		}

		static final class HasCapabilitiesMessageClientbound implements ControlMessage {
			String kind;
			String[] caps;
		}

		@Override
		protected void encode(ChannelHandlerContext ctx, ControlMessage msg, ByteBuf out) {
			try {
				byte[] json = GSON.toJson(msg).getBytes(StandardCharsets.UTF_8);
				writeVarInt(out, json.length);
				out.writeBytes(json);
			} catch (Throwable e) {
				LOGGER.error("failed to encode control message", e);
			}
		}

		@Override
		protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
			int size = in.getByte(in.readerIndex());
			if (in.readableBytes() < size + 1) {
				return;
			}
			in.skipBytes(1);
			byte[] buf = new byte[size];
			in.readBytes(buf);
			JsonObject json = GSON.fromJson(new String(buf, StandardCharsets.UTF_8), JsonObject.class);
			switch (json.get("kind").getAsString()) {
				case "domain_assignment_complete" -> out.add(GSON.fromJson(json, DomainAssignmentCompleteMessageClientbound.class));
				case "request_message_broadcast" -> out.add(GSON.fromJson(json, RequestMessageBroadcastMessageClientbound.class));
				case "has_capabilities" -> out.add(GSON.fromJson(json, HasCapabilitiesMessageClientbound.class));
				default -> LOGGER.warn("unrecognized control message kind: {}", json.get("kind"));
			}
		}
	}
}
