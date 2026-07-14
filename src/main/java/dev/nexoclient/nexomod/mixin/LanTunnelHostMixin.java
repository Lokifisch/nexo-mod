package dev.nexoclient.nexomod.mixin;

import java.net.InetAddress;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;

import net.minecraft.server.network.ServerConnectionListener;

import dev.nexoclient.nexomod.lantunnel.LanTunnel;
import dev.nexoclient.nexomod.lantunnel.LanTunnelConfig;
import dev.nexoclient.nexomod.lantunnel.LanTunnelSession;

/**
 * Host-side hook for the LAN tunnel (see {@link LanTunnelSession} doc):
 * whenever the local server starts listening for TCP connections (i.e.
 * "Open to LAN" was pressed), also start a {@link LanTunnelSession} using
 * the exact same connection handler Minecraft was about to use for normal
 * local connections — so a joining player's tunneled QUIC stream is
 * indistinguishable, from the server's own network code's perspective,
 * from a normal incoming TCP connection.
 *
 * <p>Adapted from e4mc's {@code ServerConnectionListenerMixin}
 * (https://github.com/vgskye/e4mc-minecraft-architectury, MIT); verified
 * against a decompile of the real 26.1.2 {@code startTcpServerListener}
 * body, which still builds a {@code ServerBootstrap} via
 * {@code .childHandler(...).group(...)} the same way.
 */
@Mixin(ServerConnectionListener.class)
public abstract class LanTunnelHostMixin {
	@Unique
	private ChannelHandler nexomod$childHandler;
	@Unique
	private EventLoopGroup nexomod$group;

	@ModifyArg(
			method = "startTcpServerListener",
			at = @At(
					value = "INVOKE",
					target = "Lio/netty/bootstrap/ServerBootstrap;childHandler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/ServerBootstrap;",
					remap = false))
	private ChannelHandler nexomod$interceptHandler(ChannelHandler childHandler) {
		nexomod$childHandler = childHandler;
		return childHandler;
	}

	@ModifyArg(
			method = "startTcpServerListener",
			at = @At(
					value = "INVOKE",
					target = "Lio/netty/bootstrap/ServerBootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/ServerBootstrap;",
					remap = false))
	private EventLoopGroup nexomod$interceptGroup(EventLoopGroup group) {
		nexomod$group = group;
		return group;
	}

	@Inject(method = "startTcpServerListener", at = @At("TAIL"))
	private void nexomod$startTunnel(InetAddress address, int port, CallbackInfo ci) {
		if (LanTunnelConfig.INSTANCE.hostEnabled) {
			LanTunnel.session = new LanTunnelSession(nexomod$childHandler, nexomod$group);
			LanTunnel.session.startAsync();
		}
		nexomod$childHandler = null;
		nexomod$group = null;
	}

	@Inject(method = "stop", at = @At("HEAD"))
	private void nexomod$stopTunnel(CallbackInfo ci) {
		LanTunnelSession activeSession = LanTunnel.session;
		if (activeSession != null && activeSession.state != LanTunnelSession.State.STOPPED) {
			activeSession.stop();
			LanTunnel.session = null;
		}
	}
}
