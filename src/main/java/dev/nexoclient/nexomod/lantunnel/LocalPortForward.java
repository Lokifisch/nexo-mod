package dev.nexoclient.nexomod.lantunnel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;

/**
 * A generic local-TCP-port forwarder, so {@link LanTunnelSession} can tunnel
 * a server that isn't the in-process integrated server — a locally-run
 * Paper server, in particular (see {@code Mod/ROADMAP.md} Phase 7).
 *
 * <p>Unlike the rest of {@code lantunnel/}, this file is <strong>not</strong>
 * ported from e4mc — e4mc has no equivalent, since it only ever tunnels the
 * in-process integrated server via {@link dev.nexoclient.nexomod.mixin.LanTunnelHostMixin}'s
 * hook into {@code ServerConnectionListener}. No relay protocol change was
 * needed to add this: per-connection bytes already flow as raw, unframed
 * data over a QUIC stream — the relay's own framing exists only on the
 * separate control stream {@link LanTunnelSession} uses for domain
 * assignment — so a new *kind* of connection only ever needed a different
 * {@link io.netty.channel.ChannelHandler}, which is exactly what
 * {@link LanTunnelSession}'s constructor already takes.
 *
 * <p>Only ever points at the game port. RCON must never be reachable
 * through a tunnel meant to be shared with friends — see
 * {@code PaperServerOrchestrator} for where that port is chosen.
 */
public final class LocalPortForward {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/lantunnel");

	private LocalPortForward() {}

	/**
	 * Builds the stream handler {@link LanTunnelSession} calls for every new
	 * inbound tunneled connection: dial {@code 127.0.0.1:localPort} and
	 * relay bytes both ways until either side closes.
	 */
	public static ChannelInitializer<QuicStreamChannel> forwardingHandler(EventLoopGroup group, int localPort) {
		return new ChannelInitializer<>() {
			@Override
			protected void initChannel(QuicStreamChannel quicStream) {
				// Held off until the local TCP connection is actually ready, so
				// nothing the joining player sent first is read (and dropped)
				// before there is anywhere to forward it to.
				quicStream.config().setAutoRead(false);

				new Bootstrap()
						.group(group)
						.channel(NioSocketChannel.class)
						.handler(new ChannelInitializer<Channel>() {
							@Override
							protected void initChannel(Channel tcpChannel) {
								// TCP -> QUIC direction.
								tcpChannel.pipeline().addLast(new Relay(quicStream));
							}
						})
						.connect("127.0.0.1", localPort)
						.addListener((ChannelFutureListener) future -> {
							if (!future.isSuccess()) {
								LOGGER.warn("[nexomod] Could not reach the local server on port {} for a tunneled connection",
										localPort, future.cause());
								quicStream.close();
								return;
							}
							Channel tcpChannel = future.channel();
							// QUIC -> TCP direction.
							quicStream.pipeline().addLast(new Relay(tcpChannel));
							quicStream.config().setAutoRead(true);
						});
			}
		};
	}

	/** Forwards everything read on one channel to the other; closes both together. */
	private static final class Relay extends ChannelInboundHandlerAdapter {
		private final Channel target;

		Relay(Channel target) {
			this.target = target;
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			if (!(msg instanceof ByteBuf)) {
				return;
			}
			if (!target.isActive()) {
				((ByteBuf) msg).release();
				return;
			}
			target.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
				if (!future.isSuccess()) {
					ctx.close();
				}
			});
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			closeGracefully(target);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			LOGGER.debug("[nexomod] Tunnel relay error", cause);
			ctx.close();
		}

		private static void closeGracefully(Channel channel) {
			if (channel.isActive()) {
				channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
			}
		}
	}
}
