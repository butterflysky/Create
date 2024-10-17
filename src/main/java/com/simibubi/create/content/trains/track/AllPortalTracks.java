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

/**
 * Manages portal track integrations for various dimensions and mods within the Create mod.
 * <p>
 * Portals must be entered from the side and must lead to a different dimension than the one entered from.
 * This class handles the registration and functionality of portal tracks for standard and modded portals.
 * </p>
 */
public class AllPortalTracks {

	private static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * Functional interface representing a provider for portal track connections.
	 * It takes a pair of {@link ServerLevel} and {@link BlockFace} representing the inbound track
	 * and returns a similar pair for the outbound track.
	 */
	@FunctionalInterface
	public interface PortalTrackProvider extends UnaryOperator<Pair<ServerLevel, BlockFace>> {
	}

	/**
	 * Registry mapping portal blocks to their respective {@link PortalTrackProvider}s.
	 */
	private static final AttachedRegistry<Block, PortalTrackProvider> PORTAL_BEHAVIOURS =
			new AttachedRegistry<>(BuiltInRegistries.BLOCK);

	/**
	 * Registers a portal track integration for a given block identified by its {@link ResourceLocation}.
	 *
	 * @param block    The resource location of the portal block.
	 * @param provider The portal track provider for the block.
	 */
	public static void registerIntegration(ResourceLocation block, PortalTrackProvider provider) {
		PORTAL_BEHAVIOURS.register(block, provider);
	}

	/**
	 * Registers a portal track integration for a given {@link Block}.
	 *
	 * @param block    The portal block.
	 * @param provider The portal track provider for the block.
	 */
	public static void registerIntegration(Block block, PortalTrackProvider provider) {
		PORTAL_BEHAVIOURS.register(block, provider);
	}

	/**
	 * Checks if a given {@link BlockState} represents a supported portal block.
	 *
	 * @param state The block state to check.
	 * @return {@code true} if the block state represents a supported portal; {@code false} otherwise.
	 */
	public static boolean isSupportedPortal(BlockState state) {
		return PORTAL_BEHAVIOURS.get(state.getBlock()) != null;
	}

	/**
	 * Retrieves the corresponding outbound track on the other side of a portal.
	 *
	 * @param level        The current {@link ServerLevel}.
	 * @param inboundTrack The inbound track {@link BlockFace}.
	 * @return A pair containing the target {@link ServerLevel} and outbound {@link BlockFace},
	 *         or {@code null} if no corresponding portal is found.
	 */
	public static Pair<ServerLevel, BlockFace> getOtherSide(ServerLevel level, BlockFace inboundTrack) {
		BlockPos portalPos = inboundTrack.getConnectedPos();
		BlockState portalState = level.getBlockState(portalPos);
		PortalTrackProvider provider = PORTAL_BEHAVIOURS.get(portalState.getBlock());
		return provider == null ? null : provider.apply(Pair.of(level, inboundTrack));
	}

	// Built-in handlers

	/**
	 * Registers default portal track integrations for built-in dimensions and mods.
	 * This includes the Nether, Aether (if loaded), and Better End (if loaded).
	 */
	public static void registerDefaults() {
		registerIntegration(Blocks.NETHER_PORTAL, AllPortalTracks::nether);
		if (Mods.AETHER.isLoaded())
			registerIntegration(new ResourceLocation("aether", "aether_portal"), AllPortalTracks::aether);
		if (Mods.BETTEREND.isLoaded())
			registerIntegration(new ResourceLocation("betterend", "end_portal_block"), AllPortalTracks::betterend);
	}

	/**
	 * Portal track provider for the Nether portal.
	 *
	 * @param inbound A pair containing the current {@link ServerLevel} and inbound {@link BlockFace}.
	 * @return A pair with the target {@link ServerLevel} and outbound {@link BlockFace}, or {@code null} if not applicable.
	 */
	private static Pair<ServerLevel, BlockFace> nether(Pair<ServerLevel, BlockFace> inbound) {
		ServerLevel level = inbound.getFirst();
		MinecraftServer minecraftServer = level.getServer();

		if (!minecraftServer.isNetherEnabled())
			return null;

		return standardPortalProvider(inbound, Level.OVERWORLD, Level.NETHER, AllPortalTracks::getTeleporter);
	}

	/**
	 * Portal track provider for the Aether mod's portal.
	 *
	 * @param inbound A pair containing the current {@link ServerLevel} and inbound {@link BlockFace}.
	 * @return A pair with the target {@link ServerLevel} and outbound {@link BlockFace}, or {@code null} if not applicable.
	 */
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

	/**
	 * Portal track provider for the Better End mod's portal.
	 *
	 * @param inbound A pair containing the current {@link ServerLevel} and inbound {@link BlockFace}.
	 * @return A pair with the target {@link ServerLevel} and outbound {@link BlockFace}, or {@code null} if not applicable.
	 */
	private static Pair<ServerLevel, BlockFace> betterend(Pair<ServerLevel, BlockFace> inbound) {
		return portalProvider(
				inbound,
				Level.OVERWORLD,
				Level.END,
				(otherLevel, probe) -> getBetterEndPortalInfo(probe, otherLevel)
		);
	}

	/**
	 * Retrieves the adjusted {@link PortalInfo} for the Better End portal using reflection.
	 *
	 * @param entity      The probe {@link Entity} used for portal traversal calculations.
	 * @param targetLevel The target {@link ServerLevel} (dimension).
	 * @return The adjusted {@link PortalInfo} for the target dimension, or {@code null} if an error occurs.
	 */
	private static PortalInfo getBetterEndPortalInfo(Entity entity, ServerLevel targetLevel) {
		try {
			Class<?> travelerStateClass = Class.forName("org.betterx.betterend.portal.TravelerState");
			Constructor<?> constructor = travelerStateClass.getDeclaredConstructor(Entity.class);
			constructor.setAccessible(true);
			Object travelerState = constructor.newInstance(entity);

			// Set the private portalEntrancePos field to the entity's block position as assumed in TravelerState#findDimensionEntryPoint
			Field portalEntrancePosField = travelerStateClass.getDeclaredField("portalEntrancePos");
			portalEntrancePosField.setAccessible(true);
			portalEntrancePosField.set(travelerState, entity.blockPosition().immutable());

			Method findDimensionEntryPointMethod = travelerStateClass.getDeclaredMethod("findDimensionEntryPoint", ServerLevel.class);
			findDimensionEntryPointMethod.setAccessible(true);

			// We need to lower the result by 1 to align with the floor on the exit side
			PortalInfo otherSide = (PortalInfo) findDimensionEntryPointMethod.invoke(travelerState, targetLevel);
			return new PortalInfo(
					new Vec3(otherSide.pos.x, otherSide.pos.y - 1, otherSide.pos.z),
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

	/**
	 * Retrieves the default {@link ITeleporter} for a given {@link ServerLevel}.
	 *
	 * @param level The server level.
	 * @return The teleporter associated with the level.
	 */
	private static ITeleporter getTeleporter(ServerLevel level) {
		return (ITeleporter) level.getPortalForcer();
	}

	/**
	 * Provides a standard portal track provider that handles portal traversal between two dimensions.
	 *
	 * @param inbound             A pair containing the current {@link ServerLevel} and inbound {@link BlockFace}.
	 * @param firstDimension      The first dimension (typically the Overworld).
	 * @param secondDimension     The second dimension (e.g., Nether, Aether).
	 * @param customPortalForcer  A function to obtain the {@link ITeleporter} for the target level.
	 * @return A pair with the target {@link ServerLevel} and outbound {@link BlockFace}, or {@code null} if not applicable.
	 */
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

	/**
	 * Generalized portal provider method that calculates the corresponding outbound track across a portal.
	 *
	 * @param inbound             A pair containing the current {@link ServerLevel} and inbound {@link BlockFace}.
	 * @param firstDimension      The first dimension.
	 * @param secondDimension     The second dimension.
	 * @param portalInfoProvider  A function that provides the {@link PortalInfo} given the target level and probe entity.
	 * @return A pair with the target {@link ServerLevel} and outbound {@link BlockFace}, or {@code null} if not applicable.
	 */
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
