package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.config.DaisychainClientConfig;
import com.daisychain.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ModelEvent;

import java.util.Map;

/**
 * Step 42 (reworked in step 55): loads the pipe part models (assets/daisychain/models/block/pipe/<type>/, made by
 * scripts/pipe_models.py) and puts a PipeBakedModel on every block state of every pipe. Parts: the core faces
 * (core_<face>_<mask>, 6 x 16), the arms (link / insert / pull per side) and node (a lone pipe). The blockstate files
 * only point at node, which stays in place if a part is missing, so a broken resource pack shows plain cores instead of
 * crashing. Client only.
 */
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PipeModels {
    private static final String[] TYPES = {"item", "fluid", "energy", "universal"};
    /** Indexed like PipeBakedModel.LINK / INSERT / PULL / END / ... / CAP. */
    private static final String[] PIECES = {"link", "insert", "pull", "end", "pullend", "insertend", "pulllone", "insertlone",
            "cap"}; // Step 146: "cap" = the flat end cap toward a covered pipe (PipeBakedModel.CAP)

    private PipeModels() {
    }

    private static ResourceLocation part(String type, String name) {
        // 1.20.1: extra models are registered and looked up by their plain model id.
        return new ResourceLocation(Daisychain.MOD_ID, "block/pipe/" + type + "/" + name);
    }

    private static String sideName(int side) {
        return Direction.from3DDataValue(side).getSerializedName();
    }

    /** Step 146: closes the grooves where two arms at right angles meet at the core. */
    private static String plugName(int a, int b) {
        return "plug_" + sideName(a) + "_" + sideName(b);
    }

    private static String coreName(int side, int mask) {
        return "core_" + sideName(side) + "_" + mask;
    }

    @SubscribeEvent
    public static void registerParts(ModelEvent.RegisterAdditional event) {
        for (String type : TYPES) {
            event.register(part(type, "node"));
            event.register(part(type, "core_all"));
            for (int side = 0; side < 6; side++) {
                for (int mask = 0; mask < PipeBakedModel.CORE_MASKS; mask++) {
                    event.register(part(type, coreName(side, mask)));
                }
                for (int other = side + 1; other < 6; other++) {
                    if (other / 2 != side / 2) {
                        event.register(part(type, plugName(side, other))); // step 146
                    }
                }
            }
            for (String piece : PIECES) {
                for (int side = 0; side < 6; side++) {
                    event.register(part(type, piece + "_" + sideName(side)));
                }
            }
        }
    }

    /** Step 90: the energy glow setting as last drawn, so a change redraws the world's pipes once. */
    private static Boolean drawnGlow;

    /** Step 90: the client config changed (settings screen or file): if the energy glow changed, redraw chunks. */
    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != DaisychainClientConfig.SPEC) {
            return;
        }
        boolean glow = DaisychainClientConfig.energyGlow();
        boolean colours = PipeTints.refresh(); // step 147: the pipe colours changed (screen or file)
        if (drawnGlow != null && drawnGlow != glow || colours) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> {
                if (minecraft.levelRenderer != null) {
                    minecraft.levelRenderer.allChanged();
                }
            });
        }
        drawnGlow = glow;
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == DaisychainClientConfig.SPEC) {
            drawnGlow = DaisychainClientConfig.energyGlow();
            PipeTints.refresh();
        }
    }

    @SubscribeEvent
    public static void usePipeModels(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, BakedModel> models = event.getModels();
        // Step 84: every energy pipe tier uses the energy parts; the tier color comes from PipeColors (tint 12).
        Block[][] blocks = {{ModBlocks.ITEM_PIPE.get()}, {ModBlocks.FLUID_PIPE.get()}, ModBlocks.energyPipes(),
                {ModBlocks.UNIVERSAL_PIPE.get()}};
        for (int t = 0; t < TYPES.length; t++) {
            String type = TYPES[t];
            BakedModel node = models.get(part(type, "node"));
            BakedModel[][] cores = new BakedModel[6][PipeBakedModel.CORE_MASKS];
            BakedModel[][] pieces = new BakedModel[PIECES.length][6];
            BakedModel[][] plugs = new BakedModel[6][6];
            BakedModel coreAll = models.get(part(type, "core_all"));
            boolean complete = node != null && coreAll != null;
            for (int a = 0; a < 6; a++) {
                for (int b = a + 1; b < 6; b++) {
                    if (a / 2 != b / 2) {
                        plugs[a][b] = models.get(part(type, plugName(a, b)));
                        complete &= plugs[a][b] != null;
                    }
                }
            }
            for (int side = 0; side < 6; side++) {
                for (int mask = 0; mask < PipeBakedModel.CORE_MASKS; mask++) {
                    cores[side][mask] = models.get(part(type, coreName(side, mask)));
                    complete &= cores[side][mask] != null;
                }
            }
            for (int p = 0; p < PIECES.length; p++) {
                for (int side = 0; side < 6; side++) {
                    pieces[p][side] = models.get(part(type, PIECES[p] + "_" + sideName(side)));
                    complete &= pieces[p][side] != null;
                }
            }
            if (!complete) {
                Daisychain.LOGGER.warn("Daisychain: some {} pipe part models are missing; those pipes show plain cores", type);
                continue;
            }
            PipeBakedModel model = new PipeBakedModel(node, cores, pieces, plugs, coreAll);
            for (Block block : blocks[t]) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    models.put(BlockModelShaper.stateToModelLocation(state), model);
                }
            }
        }
    }
}
