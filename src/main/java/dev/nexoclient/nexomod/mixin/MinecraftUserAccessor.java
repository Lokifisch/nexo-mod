package dev.nexoclient.nexomod.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;

/**
 * These are all private final fields Minecraft only sets once, at startup,
 * from the launch session — swapping accounts at runtime means rebuilding
 * every one of them (not just {@code user}), or downstream services stay
 * bound to the old account's token (observed live: a 401 fetching the chat
 * signing profile key pair after switching, because profileKeyPairManager
 * was still built from the previous session's userApiService).
 * {@code @Mutable} is required on each: without it the field stays final in
 * the transformed bytecode and writes throw IllegalAccessError even though
 * the accessor method itself compiles fine.
 */
@Mixin(Minecraft.class)
public interface MinecraftUserAccessor {
	@Mutable
	@Accessor("user")
	void nexomod$setUser(User user);

	@Mutable
	@Accessor("profileFuture")
	void nexomod$setProfileFuture(CompletableFuture<ProfileResult> profileFuture);

	@Mutable
	@Accessor("userApiService")
	void nexomod$setUserApiService(UserApiService userApiService);

	@Mutable
	@Accessor("userPropertiesFuture")
	void nexomod$setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> userPropertiesFuture);

	@Mutable
	@Accessor("profileKeyPairManager")
	void nexomod$setProfileKeyPairManager(ProfileKeyPairManager profileKeyPairManager);
}
