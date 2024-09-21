package com.simibubi.create.content.kinetics.base;

import org.apache.commons.lang3.ArrayUtils;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.KineticDebugger;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.simibubi.create.foundation.render.CachedBufferer;
import com.simibubi.create.foundation.render.SuperByteBuffer;
import com.simibubi.create.foundation.render.SuperByteBufferCache;
import com.simibubi.create.foundation.utility.AnimationTickHolder;
import com.simibubi.create.foundation.utility.Color;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public class KineticBlockEntityRenderer<T extends KineticBlockEntity> extends SafeBlockEntityRenderer<T> {

	public static final SuperByteBufferCache.Compartment<BlockState> KINETIC_BLOCK = new SuperByteBufferCache.Compartment<>();
	public static boolean rainbowMode = false;

	public KineticBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	protected void renderSafe(T be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		if (VisualizationManager.supportsVisualization(be.getLevel())) return;

		BlockState state = getRenderedBlockState(be);
		RenderType type = getRenderType(be, state);
		renderRotatingBuffer(be, getRotatedModel(be, state), ms, buffer.getBuffer(type), light);
	}

	protected BlockState getRenderedBlockState(T be) {
		return be.getBlockState();
	}

	protected RenderType getRenderType(T be, BlockState state) {
		return ItemBlockRenderTypes.getChunkRenderType(state);
	}

	protected SuperByteBuffer getRotatedModel(T be, BlockState state) {
		return CachedBufferer.block(KINETIC_BLOCK, state);
	}

	public static void renderRotatingKineticBlock(KineticBlockEntity be, BlockState renderedState, PoseStack ms,
		VertexConsumer buffer, int light) {
		SuperByteBuffer superByteBuffer = CachedBufferer.block(KINETIC_BLOCK, renderedState);
		renderRotatingBuffer(be, superByteBuffer, ms, buffer, light);
	}

	public static void renderRotatingBuffer(KineticBlockEntity be, SuperByteBuffer superBuffer, PoseStack ms,
		VertexConsumer buffer, int light) {
		standardKineticRotationTransform(superBuffer, be, light).renderInto(ms, buffer);
	}

	public static float getAngleForBe(KineticBlockEntity be, final BlockPos pos, Axis axis) {
		float time = AnimationTickHolder.getRenderTime(be.getLevel());
		float offset = getRotationOffsetForPosition(be, pos, axis);
		float angle = ((time * be.getSpeed() * 3f / 10 + offset) % 360) / 180 * (float) Math.PI;
		return angle;
	}

	public static SuperByteBuffer standardKineticRotationTransform(SuperByteBuffer buffer, KineticBlockEntity be,
		int light) {
		final BlockPos pos = be.getBlockPos();
		Axis axis = ((IRotate) be.getBlockState()
			.getBlock()).getRotationAxis(be.getBlockState());
		return kineticRotationTransform(buffer, be, axis, getAngleForBe(be, pos, axis), light);
	}

	public static SuperByteBuffer kineticRotationTransform(SuperByteBuffer buffer, KineticBlockEntity be, Axis axis,
		float angle, int light) {
		buffer.light(light);
		buffer.rotateCentered(angle, Direction.get(AxisDirection.POSITIVE, axis));

		if (KineticDebugger.isActive()) {
			rainbowMode = true;
			buffer.color(be.hasNetwork() ? Color.generateFromLong(be.network) : Color.WHITE);
		} else {
			float overStressedEffect = be.effects.overStressedEffect;
			if (overStressedEffect != 0)
				if (overStressedEffect > 0)
					buffer.color(Color.WHITE.mixWith(Color.RED, overStressedEffect));
				else
					buffer.color(Color.WHITE.mixWith(Color.SPRING_GREEN, -overStressedEffect));
			else
				buffer.color(Color.WHITE);
		}

		return buffer;
	}

	public static float getRotationOffsetForPosition(KineticBlockEntity be, final BlockPos pos, final Axis axis) {
		float offset = ICogWheel.isLargeCog(be.getBlockState()) ? 11.25f : 0;
		double d = (((axis == Axis.X) ? 0 : pos.getX()) + ((axis == Axis.Y) ? 0 : pos.getY())
			+ ((axis == Axis.Z) ? 0 : pos.getZ())) % 2;
		if (d == 0)
			offset = 22.5f;
		return offset + be.getRotationAngleOffset(axis);
	}

	public static BlockState shaft(Axis axis) {
		return AllBlocks.SHAFT.getDefaultState()
			.setValue(BlockStateProperties.AXIS, axis);
	}

	public static Axis getRotationAxisOf(KineticBlockEntity be) {
		return ((IRotate) be.getBlockState()
			.getBlock()).getRotationAxis(be.getBlockState());
	}

}
