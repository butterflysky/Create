package com.simibubi.create.content.trains.track;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.function.BiFunction;

import com.simibubi.create.compat.Mods;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.foundation.utility.AttachedRegistry;
import com.simibubi.create.foundation.utility.BlockFace;
import com.simibubi.create.foundation.utility.Pair;

import io.github.fabricators_of_create.porting_lib.entity.ITeleporter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.AABB;

import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public class AllPortalTracks {
	// Portals must be entered from the side and must lead to a different dimension
	// than the one entered from

	private static final Logger LOGGER = LogUtils.getLogger();

	@FunctionalInterface
	public interface PortalTrackProvider extends UnaryOperator<Pair<ServerLevel, BlockFace>> {
	}

	private static final AttachedRegistry<Block, PortalTrackProvider> PORTAL_BEHAVIOURS =
		new AttachedRegistry<>(BuiltInRegistries.BLOCK);

	public static void registerIntegration(ResourceLocation block, PortalTrackProvider provider) {
		PORTAL_BEHAVIOURS.register(block, provider);
	}

	public static void registerIntegration(Block block, PortalTrackProvider provider) {
		PORTAL_BEHAVIOURS.register(block, provider);
	}

	public static boolean isSupportedPortal(BlockState state) {
		return PORTAL_BEHAVIOURS.get(state.getBlock()) != null;
	}

	public static Pair<ServerLevel, BlockFace> getOtherSide(ServerLevel level, BlockFace inboundTrack) {
		BlockPos portalPos = inboundTrack.getConnectedPos();
		BlockState portalState = level.getBlockState(portalPos);
		PortalTrackProvider provider = PORTAL_BEHAVIOURS.get(portalState.getBlock());
		return provider == null ? null : provider.apply(Pair.of(level, inboundTrack));
	}

	// Builtin handlers

	public static void registerDefaults() {
		registerIntegration(Blocks.NETHER_PORTAL, AllPortalTracks::nether);
		if (Mods.AETHER.isLoaded())
			registerIntegration(new ResourceLocation("aether", "aether_portal"), AllPortalTracks::aether);
		if (Mods.BETTEREND.isLoaded())
			registerIntegration(new ResourceLocation("betterend", "end_portal_block"), AllPortalTracks::betterend);
	}

	private static Pair<ServerLevel, BlockFace> nether(Pair<ServerLevel, BlockFace> inbound) {
		ServerLevel level = inbound.getFirst();
		MinecraftServer minecraftServer = level.getServer();

		if (!minecraftServer.isNetherEnabled())
			return null;

		return standardPortalProvider(inbound, Level.OVERWORLD, Level.NETHER, AllPortalTracks::getTeleporter);
	}

	private static Pair<ServerLevel, BlockFace> aether(Pair<ServerLevel, BlockFace> inbound) {
		ResourceKey<Level> aetherLevelKey =
			ResourceKey.create(Registries.DIMENSION, new ResourceLocation("aether", "the_aether"));
		return standardPortalProvider(inbound, Level.OVERWORLD, aetherLevelKey, level -> {
			try {
				return (ITeleporter) Class.forName("com.aetherteam.aether.block.portal.AetherPortalForcer")
					.getDeclaredConstructor(ServerLevel.class, boolean.class)
					.newInstance(level, true);
			} catch (Exception e) {
				LOGGER.error("Failed to create Aether teleporter: ", e);
			}
			return getTeleporter(level);
		});
	}

	private static Pair<ServerLevel, BlockFace> betterend(Pair<ServerLevel, BlockFace> inbound) {
		return portalProvider(
				inbound,
				Level.OVERWORLD,
				Level.END,
				(otherLevel, probe) -> getBetterEndPortalInfo(probe, otherLevel)
		);
	}

	private static PortalInfo getBetterEndPortalInfo(Entity entity, ServerLevel targetLevel) {
		try {
			Class<?> travelerStateClass = Class.forName("org.betterx.betterend.portal.TravelerState");
			Constructor<?> constructor = travelerStateClass.getDeclaredConstructor(Entity.class);
			constructor.setAccessible(true);
			Object travelerState = constructor.newInstance(entity);

			// Set the private portalEntrancePos field to the portalPos as assumed in TravelerState#findDimensionEntryPoint
			Field portalEntrancePosField = travelerStateClass.getDeclaredField("portalEntrancePos");
			portalEntrancePosField.setAccessible(true);
			portalEntrancePosField.set(travelerState, entity.blockPosition().immutable());

			Method findDimensionEntryPointMethod = travelerStateClass.getDeclaredMethod("findDimensionEntryPoint", ServerLevel.class);
			findDimensionEntryPointMethod.setAccessible(true);

			// we need to lower the result by 1 to get level with the floor on the exit side
			PortalInfo otherSide = (PortalInfo) findDimensionEntryPointMethod.invoke(travelerState, targetLevel);
			return new PortalInfo(
					(new Vec3(otherSide.pos.x, otherSide.pos.y - 1, otherSide.pos.z)),
					otherSide.speed,
					otherSide.yRot,
					otherSide.xRot
			);
		} catch (ClassNotFoundException e) {
			LOGGER.error("Better End's TravelerState class not found: ", e);
		} catch (NoSuchMethodException e) {
			LOGGER.error("Method not found in Better End's TravelerState class: ", e);
		} catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
			LOGGER.error("Failed to invoke method in Better End's TravelerState class: ", e);
		} catch (NoSuchFieldException e) {
			LOGGER.error("Field not found in Better End's TravelerState class: ", e);
		}
		return null;
	}

	private static ITeleporter getTeleporter(ServerLevel level) {
		return (ITeleporter) level.getPortalForcer();
	}

	private static Pair<ServerLevel, BlockFace> standardPortalProvider(
			Pair<ServerLevel, BlockFace> inbound,
			ResourceKey<Level> firstDimension,
			ResourceKey<Level> secondDimension,
			Function<ServerLevel, ITeleporter> customPortalForcer
	) {
		return portalProvider(
				inbound,
				firstDimension,
				secondDimension,
				(otherLevel, probe) -> {
					ITeleporter teleporter = customPortalForcer.apply(otherLevel);
					return teleporter.getPortalInfo(probe, otherLevel, probe::findDimensionEntryPoint);
				}
		);
	}

	private static Pair<ServerLevel, BlockFace> portalProvider(
			Pair<ServerLevel, BlockFace> inbound,
			ResourceKey<Level> firstDimension,
			ResourceKey<Level> secondDimension,
			BiFunction<ServerLevel, SuperGlueEntity, PortalInfo> portalInfoProvider
	) {
		ServerLevel level = inbound.getFirst();
		ResourceKey<Level> resourceKey = level.dimension() == secondDimension ? firstDimension : secondDimension;

		MinecraftServer minecraftServer = level.getServer();
		ServerLevel otherLevel = minecraftServer.getLevel(resourceKey);

		if (otherLevel == null)
			return null;

		BlockFace inboundTrack = inbound.getSecond();
		BlockPos portalPos = inboundTrack.getConnectedPos();
		BlockState portalState = level.getBlockState(portalPos);

		SuperGlueEntity probe = new SuperGlueEntity(level, new AABB(portalPos));
		probe.setYRot(inboundTrack.getFace().toYRot());
		probe.setPortalEntrancePos();

		PortalInfo portalInfo = portalInfoProvider.apply(otherLevel, probe);
		if (portalInfo == null)
			return null;

		BlockPos otherPortalPos = BlockPos.containing(portalInfo.pos);
		BlockState otherPortalState = otherLevel.getBlockState(otherPortalPos);
		if (!otherPortalState.is(portalState.getBlock()))
			return null;

		Direction targetDirection = inboundTrack.getFace();
		if (targetDirection.getAxis() == otherPortalState.getValue(BlockStateProperties.HORIZONTAL_AXIS))
			targetDirection = targetDirection.getClockWise();
		BlockPos otherPos = otherPortalPos.relative(targetDirection);
		return Pair.of(otherLevel, new BlockFace(otherPos, targetDirection.getOpposite()));
	}
}
