package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.item.FacadeItem;
import com.daisychain.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facades step 2 (step 126a, zerobit's icon "D"): a facade item looks like its cover block with a pipe end showing in
 * the middle of every face (models/item/facade_pipe_ends.json over the block's own model), in the inventory and in the
 * hand. In inventory slots a small daisy in the top-right corner marks it as a facade (top-right because the stack
 * count sits bottom-right; step 126b/c: 5x5 white petals around one yellow pixel, one pixel in from the slot edges). A facade with no block keeps the flat placeholder icon.
 * <p>
 * One combined model is made per cover block the first time it's drawn and kept until the next resource reload, so
 * drawing a facade allocates nothing. Client only.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT)
public final class FacadeItemModel extends BakedModelWrapper<BakedModel> {
    private static final ModelResourceLocation PIPE_ENDS = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "item/facade_pipe_ends"));
    private static final ModelResourceLocation FACADE = ModelResourceLocation.inventory(
            ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "facade"));
    private static final ResourceLocation DAISY =
            ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "textures/gui/facade_daisy.png");
    private static volatile boolean warned;

    private final BakedModel pipeEnds;
    private final Map<BlockState, BakedModel> byCover = new ConcurrentHashMap<>();
    private final ItemOverrides overrides = new ItemOverrides() {
        @Override
        public BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level,
                                  @Nullable LivingEntity entity, int seed) {
            BlockState cover = FacadeItem.coverOf(stack);
            if (cover == null) {
                return originalModel; // empty facade: the placeholder
            }
            BakedModel made = byCover.get(cover);
            if (made == null) {
                made = combine(cover);
                BakedModel earlier = byCover.putIfAbsent(cover, made);
                if (earlier != null) {
                    made = earlier;
                }
            }
            return made;
        }
    };

    private FacadeItemModel(BakedModel placeholder, BakedModel pipeEnds) {
        super(placeholder);
        this.pipeEnds = pipeEnds;
    }

    @Override
    public ItemOverrides getOverrides() {
        return overrides;
    }

    /** The cover block's model with the pipe ends on top; the placeholder if the cover's model breaks. */
    private BakedModel combine(BlockState cover) {
        try {
            BakedModel block = Minecraft.getInstance().getBlockRenderer().getBlockModel(cover);
            @SuppressWarnings("unchecked")
            List<BakedQuad>[] quads = new List[7];
            for (int i = 0; i < 7; i++) {
                Direction face = i < 6 ? Direction.from3DDataValue(i) : null;
                List<BakedQuad> list = new ArrayList<>(block.getQuads(cover, face, RandomSource.create(42), ModelData.EMPTY, null));
                list.addAll(pipeEnds.getQuads(null, face, RandomSource.create(42), ModelData.EMPTY, null));
                quads[i] = List.copyOf(list);
            }
            boolean translucent = block.getRenderTypes(cover, RandomSource.create(42), ModelData.EMPTY)
                    .contains(RenderType.translucent());
            ItemTransforms transforms = block.getTransforms();
            if (transforms == ItemTransforms.NO_TRANSFORMS) {
                // A modded model without block display settings: borrow stone's so it isn't drawn huge or flat.
                transforms = Minecraft.getInstance().getBlockRenderer().getBlockModel(Blocks.STONE.defaultBlockState())
                        .getTransforms();
            }
            return new Combined(block, quads, transforms, translucent);
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                Daisychain.LOGGER.warn("Daisychain: couldn't build the facade icon for {}; showing the plain one", cover, e);
            }
            return originalModel;
        }
    }

    /** One cover's facade icon. Everything is worked out once in {@link #combine}. */
    private static final class Combined extends BakedModelWrapper<BakedModel> {
        private final List<BakedQuad>[] quads;
        private final ItemTransforms transforms;
        private final List<RenderType> solidTypes;
        private final List<RenderType> fabulousTypes;
        private final List<BakedModel> passes;

        Combined(BakedModel block, List<BakedQuad>[] quads, ItemTransforms transforms, boolean translucent) {
            super(block);
            this.quads = quads;
            this.transforms = transforms;
            this.solidTypes = List.of(translucent
                    ? RenderTypeHelper.getEntityRenderType(RenderType.translucent(), false) : Sheets.cutoutBlockSheet());
            this.fabulousTypes = List.of(translucent
                    ? RenderTypeHelper.getEntityRenderType(RenderType.translucent(), true) : Sheets.cutoutBlockSheet());
            this.passes = List.of(this);
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
            return quads[side == null ? 6 : side.get3DDataValue()];
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                        ModelData data, @Nullable RenderType renderType) {
            return quads[side == null ? 6 : side.get3DDataValue()];
        }

        @Override
        public boolean isGui3d() {
            return true;
        }

        @Override
        public boolean usesBlockLight() {
            return true;
        }

        @Override
        public boolean isCustomRenderer() {
            return false;
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }

        @Override
        public ItemTransforms getTransforms() {
            return transforms;
        }

        @Override
        public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean leftHand) {
            transforms.getTransform(context).apply(leftHand, poseStack);
            return this; // not the block's model: that one has no pipe ends
        }

        @Override
        public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
            return fabulous ? fabulousTypes : solidTypes;
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
            return passes;
        }
    }

    // ---------------------------------------------------------------- registration

    @SubscribeEvent
    public static void registerParts(ModelEvent.RegisterAdditional event) {
        event.register(PIPE_ENDS);
    }

    @SubscribeEvent
    public static void useFacadeModel(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        BakedModel placeholder = models.get(FACADE);
        BakedModel ends = models.get(PIPE_ENDS);
        if (placeholder == null || ends == null) {
            Daisychain.LOGGER.warn("Daisychain: facade item models are missing; facades show the plain icon");
            return;
        }
        models.put(FACADE, new FacadeItemModel(placeholder, ends));
    }

    /** Tinted covers (grass, leaves) get their block's default tint in the icon. */
    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        BlockColors blockColors = event.getBlockColors();
        event.register((stack, tintIndex) -> {
            BlockState cover = tintIndex < 0 ? null : FacadeItem.coverOf(stack);
            if (cover == null) {
                return -1;
            }
            int color = blockColors.getColor(cover, null, null, tintIndex);
            return color == -1 ? -1 : 0xFF000000 | color;
        }, ModItems.FACADE.get());
    }

    /** The daisy badge, inventory slots only (top-right, one pixel in, above the item, same layer as the count). */
    @SubscribeEvent
    public static void registerDecorations(RegisterItemDecorationsEvent event) {
        event.register(ModItems.FACADE.get(), (graphics, font, stack, x, y) -> {
            if (FacadeItem.coverOf(stack) == null) {
                return false;
            }
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 200.0F);
            graphics.blit(DAISY, x + 10, y + 1, 0.0F, 0.0F, 5, 5, 16, 16); // step 126b: 5x5, one pixel in from the edges
            graphics.pose().popPose();
            return false;
        });
    }
}
