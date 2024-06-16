package com.simibubi.create.content.equipment.extendoGrip;

import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.jamieswhiteshirt.reachentityattributes.ReachEntityAttributes;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.utility.AnimationTickHolder;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

public class ExtendoGripItem extends Item  {
	public static final int MAX_DAMAGE = 200;

	public static final AttributeModifier singleRangeAttributeModifier =
		new AttributeModifier(UUID.fromString("7f7dbdb2-0d0d-458a-aa40-ac7633691f66"), "Range modifier", 3,
			AttributeModifier.Operation.ADDITION);
	public static final AttributeModifier doubleRangeAttributeModifier =
		new AttributeModifier(UUID.fromString("8f7dbdb2-0d0d-458a-aa40-ac7633691f66"), "Range modifier", 5,
			AttributeModifier.Operation.ADDITION);

	private static final Supplier<Multimap<Attribute, AttributeModifier>> rangeModifier = Suppliers.memoize(() ->
	// Holding an ExtendoGrip
	ImmutableMultimap.of(ReachEntityAttributes.REACH, singleRangeAttributeModifier));
	private static final Supplier<Multimap<Attribute, AttributeModifier>> doubleRangeModifier = Suppliers.memoize(() ->
	// Holding two ExtendoGrips o.O
	ImmutableMultimap.of(ReachEntityAttributes.REACH, doubleRangeAttributeModifier));

	private static DamageSource lastActiveDamageSource;

	public ExtendoGripItem(Properties properties) {
		super(properties.defaultDurability(MAX_DAMAGE));
	}

	public static final String EXTENDO_MARKER = "createExtendo";
	public static final String DUAL_EXTENDO_MARKER = "createDualExtendo";

	public static void holdingExtendoGripIncreasesRange(LivingEntity entity) {
		if (!(entity instanceof Player))
			return;

		Player player = (Player) entity;

		CompoundTag persistentData = player.getExtraCustomData();
		boolean inOff = AllItems.EXTENDO_GRIP.isIn(player.getOffhandItem());
		boolean inMain = AllItems.EXTENDO_GRIP.isIn(player.getMainHandItem());
		boolean holdingDualExtendo = inOff && inMain;
		boolean holdingExtendo = inOff ^ inMain;
		holdingExtendo &= !holdingDualExtendo;
		boolean wasHoldingExtendo = persistentData.contains(EXTENDO_MARKER);
		boolean wasHoldingDualExtendo = persistentData.contains(DUAL_EXTENDO_MARKER);

		if (holdingExtendo != wasHoldingExtendo) {
			if (!holdingExtendo) {
				player.getAttributes()
					.removeAttributeModifiers(rangeModifier.get());
				persistentData.remove(EXTENDO_MARKER);
			} else {
				AllAdvancements.EXTENDO_GRIP.awardTo(player);
				player.getAttributes()
					.addTransientAttributeModifiers(rangeModifier.get());
				persistentData.putBoolean(EXTENDO_MARKER, true);
			}
		}

		if (holdingDualExtendo != wasHoldingDualExtendo) {
			if (!holdingDualExtendo) {
				player.getAttributes()
					.removeAttributeModifiers(doubleRangeModifier.get());
				persistentData.remove(DUAL_EXTENDO_MARKER);
			} else {
				AllAdvancements.EXTENDO_GRIP_DUAL.awardTo(player);
				player.getAttributes()
					.addTransientAttributeModifiers(doubleRangeModifier.get());
				persistentData.putBoolean(DUAL_EXTENDO_MARKER, true);
			}
		}

	}

	public static void addReachToJoiningPlayersHoldingExtendo(Entity entity, @Nullable CompoundTag persistentData) {
		if (!(entity instanceof Player player) || persistentData == null) return;
//		Player player = event.getPlayer();
//		CompoundTag persistentData = player.getExtraCustomData();

		if (persistentData.contains(DUAL_EXTENDO_MARKER))
			player.getAttributes()
				.addTransientAttributeModifiers(doubleRangeModifier.get());
		else if (persistentData.contains(EXTENDO_MARKER))
			player.getAttributes()
				.addTransientAttributeModifiers(rangeModifier.get());
	}

//	@SubscribeEvent
	@Environment(EnvType.CLIENT)
	public static void dontMissEntitiesWhenYouHaveHighReachDistance(/*ClickInputEvent event*/) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (mc.level == null || player == null)
			return;
		if (!isHoldingExtendoGrip(player))
			return;
		if (mc.hitResult instanceof BlockHitResult && mc.hitResult.getType() != Type.MISS)
			return;

		// Modified version of GameRenderer#getMouseOver
		double d0 = player.getAttribute(ReachEntityAttributes.REACH)
			.getValue();
		if (!player.isCreative())
			d0 -= 0.5f;
		Vec3 Vector3d = player.getEyePosition(AnimationTickHolder.getPartialTicks());
		Vec3 Vector3d1 = player.getViewVector(1.0F);
		Vec3 Vector3d2 = Vector3d.add(Vector3d1.x * d0, Vector3d1.y * d0, Vector3d1.z * d0);
		AABB AABB = player.getBoundingBox()
			.expandTowards(Vector3d1.scale(d0))
			.inflate(1.0D, 1.0D, 1.0D);
		EntityHitResult entityraytraceresult =
			ProjectileUtil.getEntityHitResult(player, Vector3d, Vector3d2, AABB, (e) -> {
				return !e.isSpectator() && e.isPickable();
			}, d0 * d0);
		if (entityraytraceresult != null) {
			Entity entity1 = entityraytraceresult.getEntity();
			Vec3 Vector3d3 = entityraytraceresult.getLocation();
			double d2 = Vector3d.distanceToSqr(Vector3d3);
			if (d2 < d0 * d0 || mc.hitResult == null || mc.hitResult.getType() == Type.MISS) {
				mc.hitResult = entityraytraceresult;
				if (entity1 instanceof LivingEntity || entity1 instanceof ItemFrame)
					mc.crosshairPickEntity = entity1;
			}
		}
	}

	private static void findAndDamageExtendoGrip(Player player) {
		if (player == null)
			return;
		if (player.level.isClientSide)
			return;
		InteractionHand hand = InteractionHand.MAIN_HAND;
		ItemStack extendo = player.getMainHandItem();
		if (!AllItems.EXTENDO_GRIP.isIn(extendo)) {
			extendo = player.getOffhandItem();
			hand = InteractionHand.OFF_HAND;
		}
		if (!AllItems.EXTENDO_GRIP.isIn(extendo))
			return;
		final InteractionHand h = hand;
		if (!BacktankUtil.canAbsorbDamage(player, maxUses()))
			extendo.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(h));
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		return BacktankUtil.isBarVisible(stack, maxUses());
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		return BacktankUtil.getBarWidth(stack, maxUses());
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return BacktankUtil.getBarColor(stack, maxUses());
	}

	private static int maxUses() {
		return AllConfigs.server().equipment.maxExtendoGripActions.get();
	}

	public static float bufferLivingAttackEvent(DamageSource source, LivingEntity damaged, float amount) {
		// Workaround for removed patch to get the attacking entity.
		lastActiveDamageSource = source;

		Entity trueSource = source.getEntity();
		if (trueSource instanceof Player)
			findAndDamageExtendoGrip((Player) trueSource);
		return amount;
	}

	public static double attacksByExtendoGripHaveMoreKnockback(double strength, Player player) {
		if (lastActiveDamageSource == null)
			return strength;
		Entity entity = lastActiveDamageSource.getDirectEntity();
		if (!(entity instanceof Player))
			return strength;
		Player playerE = (Player) entity;
		if (!isHoldingExtendoGrip(playerE))
			return strength;
		return strength + 2;
	}

//	private static boolean isUncaughtClientInteraction(Entity entity, Entity target) {
//		// Server ignores entity interaction further than 6m
//		if (entity.distanceToSqr(target) < 36)
//			return false;
//		if (!entity.level.isClientSide)
//			return false;
//		if (!(entity instanceof Player))
//			return false;
//		return true;
//	}

//	@SubscribeEvent
//	@Environment(EnvType.CLIENT)
//	public static void notifyServerOfLongRangeAttacks(AttackEntityEvent event) {
//		Entity entity = event.getEntity();
//		Entity target = event.getTarget();
//		if (!isUncaughtClientInteraction(entity, target))
//			return;
//		Player player = (Player) entity;
//		if (isHoldingExtendoGrip(player))
//			AllPackets.channel.sendToServer(new ExtendoGripInteractionPacket(target));
//	}
//
//	@SubscribeEvent
//	@Environment(EnvType.CLIENT)
//	public static void notifyServerOfLongRangeInteractions(PlayerInteractEvent.EntityInteract event) {
//		Entity entity = event.getEntity();
//		Entity target = event.getTarget();
//		if (!isUncaughtClientInteraction(entity, target))
//			return;
//		Player player = (Player) entity;
//		if (isHoldingExtendoGrip(player))
//			AllPackets.channel.sendToServer(new ExtendoGripInteractionPacket(target, event.getHand()));
//	}
//
//	@SubscribeEvent
//	@Environment(EnvType.CLIENT)
//	public static void notifyServerOfLongRangeSpecificInteractions(PlayerInteractEvent.EntityInteractSpecific event) {
//		Entity entity = event.getEntity();
//		Entity target = event.getTarget();
//		if (!isUncaughtClientInteraction(entity, target))
//			return;
//		Player player = (Player) entity;
//		if (isHoldingExtendoGrip(player))
//			AllPackets.getChannel()
//				.sendToServer(new ExtendoGripInteractionPacket(target, event.getHand(), event.getLocalPos()));
//	}

	public static boolean isHoldingExtendoGrip(Player player) {
		boolean inOff = AllItems.EXTENDO_GRIP.isIn(player.getOffhandItem());
		boolean inMain = AllItems.EXTENDO_GRIP.isIn(player.getMainHandItem());
		boolean holdingGrip = inOff || inMain;
		return holdingGrip;
	}

    public static void consumeDurabilityOnBreak(Level level, Player player, BlockPos blockPos, BlockState blockState, BlockEntity blockEntity) {
		findAndDamageExtendoGrip(player);
	}

	public static void consumeDurabilityOnPlace(BlockPlaceContext blockPlaceContext, BlockPos blockPos, BlockState blockState) {
		findAndDamageExtendoGrip(blockPlaceContext.getPlayer());
	}

//	@Override
//	@Environment(EnvType.CLIENT)
//	public void initializeClient(Consumer<IItemRenderProperties> consumer) {
//		consumer.accept(SimpleCustomRenderer.create(this, new ExtendoGripItemRenderer()));
//	}

}
