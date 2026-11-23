package com.daisychain.client;

import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.item.FacadeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.extensions.common.IClientBlockExtensions;

/**
 * Step 151: pipes make their own break and hit particles, in the pipe's colors. Minecraft's own multiply every block
 * particle by the block's tint 0, which on a pipe is the down side's pull ring (dark), and since the thin pipes the
 * item / fluid / universal textures are stored tint-ready, so the particles came out in the wrong colors. Here the
 * particle (the body texture, PipeBakedModel.getParticleIcon) gets the pipe's body color, the player's own pipe colors
 * included; a covered pipe breaks into its cover's bits in the cover's own color. Same spread and counts as Minecraft's.
 * Client only; runs when a pipe breaks or is being mined, never per frame. Forge 1.20.1: handed to the game by
 * BasePipeBlock.initializeClient.
 */
public final class PipeParticles implements IClientBlockExtensions {
    public static final PipeParticles INSTANCE = new PipeParticles();
    /** Minecraft draws block particles at 60% brightness. */
    private static final float SHADE = 0.6F;

    private PipeParticles() {
    }

    /** 0xRRGGBB for this pipe's particles. */
    private static int color(BlockState state, ClientLevel level, BlockPos pos) {
        PipeDataBlockEntity data = BasePipeBlock.getData(level, pos, state);
        BlockState cover = data == null ? null : data.cover();
        if (cover != null && !FacadeItem.isTransparent(cover)) {
            int c = Minecraft.getInstance().getBlockColors().getColor(cover, level, pos, 0);
            return c == -1 ? 0xFFFFFF : c;
        }
        return PipeColors.color(state, level, pos, PipeColors.BODY_TINT);
    }

    private static Particle tinted(Particle particle, int rgb) {
        particle.setColor(SHADE * (rgb >> 16 & 0xFF) / 255F, SHADE * (rgb >> 8 & 0xFF) / 255F, SHADE * (rgb & 0xFF) / 255F);
        return particle;
    }

    /** The boxes the bits come from: the plain pipe boxes (the outline is many small notch boxes), else the outline. */
    private static VoxelShape shape(BlockState state, ClientLevel level, BlockPos pos) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        return shape.isEmpty() ? state.getShape(level, pos) : shape;
    }

    @Override
    public boolean addDestroyEffects(BlockState state, Level level, BlockPos pos, ParticleEngine engine) {
        if (!(level instanceof ClientLevel client) || !(state.getBlock() instanceof BasePipeBlock)) {
            return false;
        }
        int rgb = color(state, client, pos);
        shape(state, client, pos).forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
            double dx = Math.min(1.0, x1 - x0);
            double dy = Math.min(1.0, y1 - y0);
            double dz = Math.min(1.0, z1 - z0);
            int nx = Math.max(2, (int) Math.ceil(dx / 0.25));
            int ny = Math.max(2, (int) Math.ceil(dy / 0.25));
            int nz = Math.max(2, (int) Math.ceil(dz / 0.25));
            for (int i = 0; i < nx; i++) {
                for (int j = 0; j < ny; j++) {
                    for (int k = 0; k < nz; k++) {
                        double fx = (i + 0.5) / nx;
                        double fy = (j + 0.5) / ny;
                        double fz = (k + 0.5) / nz;
                        engine.add(tinted(new TerrainParticle(client, pos.getX() + x0 + fx * dx, pos.getY() + y0 + fy * dy,
                                pos.getZ() + z0 + fz * dz, fx - 0.5, fy - 0.5, fz - 0.5, state, pos)
                                .updateSprite(state, pos), rgb));
                    }
                }
            }
        });
        return true;
    }

    @Override
    public boolean addHitEffects(BlockState state, Level level, HitResult target, ParticleEngine engine) {
        if (!(level instanceof ClientLevel client) || !(target instanceof BlockHitResult hit)
                || !(state.getBlock() instanceof BasePipeBlock)) {
            return false;
        }
        BlockPos pos = hit.getBlockPos();
        AABB box = shape(state, client, pos).bounds();
        double x = pos.getX() + client.random.nextDouble() * (box.maxX - box.minX - 0.2) + 0.1 + box.minX;
        double y = pos.getY() + client.random.nextDouble() * (box.maxY - box.minY - 0.2) + 0.1 + box.minY;
        double z = pos.getZ() + client.random.nextDouble() * (box.maxZ - box.minZ - 0.2) + 0.1 + box.minZ;
        Direction side = hit.getDirection();
        switch (side) {
            case DOWN -> y = pos.getY() + box.minY - 0.1;
            case UP -> y = pos.getY() + box.maxY + 0.1;
            case NORTH -> z = pos.getZ() + box.minZ - 0.1;
            case SOUTH -> z = pos.getZ() + box.maxZ + 0.1;
            case WEST -> x = pos.getX() + box.minX - 0.1;
            case EAST -> x = pos.getX() + box.maxX + 0.1;
        }
        engine.add(tinted(new TerrainParticle(client, x, y, z, 0, 0, 0, state, pos).updateSprite(state, pos), color(state, client, pos))
                .setPower(0.2F).scale(0.6F));
        return true;
    }
}
