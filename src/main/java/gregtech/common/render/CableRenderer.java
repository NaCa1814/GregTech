package gregtech.common.render;

import codechicken.lib.render.BlockRenderer.BlockFace;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.block.BlockRenderingRegistry;
import codechicken.lib.render.block.ICCBlockRenderer;
import codechicken.lib.render.item.IItemRenderer;
import codechicken.lib.render.particle.IModelParticleProvider;
import codechicken.lib.render.pipeline.ColourMultiplier;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.texture.TextureUtils;
import codechicken.lib.vec.Cuboid6;
import codechicken.lib.vec.Translation;
import codechicken.lib.vec.uv.IconTransformation;
import gregtech.api.GTValues;
import gregtech.api.unification.material.MaterialIconSet;
import gregtech.api.unification.material.MaterialIconType;
import gregtech.api.unification.material.type.MetalMaterial;
import gregtech.api.util.GTLog;
import gregtech.api.util.GTUtility;
import gregtech.common.blocks.MetaBlocks;
import gregtech.common.cable.BlockCable;
import gregtech.common.cable.Insulation;
import gregtech.common.cable.tile.TileEntityCable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.model.IModelState;
import net.minecraftforge.common.model.TRSRTransformation;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nonnull;
import javax.vecmath.Matrix4f;
import java.util.*;

import static gregtech.api.render.MetaTileEntityRenderer.BLOCK_TRANSFORMS;

public class CableRenderer implements ICCBlockRenderer, IItemRenderer, IModelParticleProvider {

    public static ModelResourceLocation MODEL_LOCATION = new ModelResourceLocation(new ResourceLocation(GTValues.MODID, "cable"), "normal");
    public static CableRenderer INSTANCE = new CableRenderer();
    public static EnumBlockRenderType BLOCK_RENDER_TYPE;
    private static ThreadLocal<BlockFace> blockFaces = ThreadLocal.withInitial(BlockFace::new);

    private TextureAtlasSprite[] insulationTextures = new TextureAtlasSprite[6];
    private Set<MaterialIconSet> generatedSets = new HashSet<>();
    private Map<MaterialIconSet, TextureAtlasSprite> wireTextures = new HashMap<>();

    public static void preInit() {
        BLOCK_RENDER_TYPE = BlockRenderingRegistry.createRenderType("gt_cable");
        BlockRenderingRegistry.registerRenderer(BLOCK_RENDER_TYPE, INSTANCE);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        TextureUtils.addIconRegister(INSTANCE::registerIcons);
        for(MetalMaterial material : MetaBlocks.CABLES.keySet()) {
            MaterialIconSet iconSet = material.materialIconSet;
            INSTANCE.generatedSets.add(iconSet);
        }
    }

    public void registerIcons(TextureMap map) {
        GTLog.logger.info("Registering cable textures.");
        for(int i = 0; i < insulationTextures.length; i++) {
            ResourceLocation location = new ResourceLocation(GTValues.MODID, "blocks/insulation/insulation_" + i);
            this.insulationTextures[i] = map.registerSprite(location);
        }
        for(MaterialIconSet iconSet : generatedSets) {
            ResourceLocation location = MaterialIconType.wire.getBlockPath(iconSet);
            this.wireTextures.put(iconSet, map.registerSprite(location));
        }
    }

    @SubscribeEvent
    public void onModelsBake(ModelBakeEvent event) {
        GTLog.logger.info("Injected cable render model");
        event.getModelRegistry().putObject(MODEL_LOCATION, this);
    }

    @Override
    public void renderItem(ItemStack stack, TransformType transformType) {
        GlStateManager.enableBlend();
        CCRenderState renderState = CCRenderState.instance();
        GlStateManager.enableBlend();
        renderState.reset();
        renderState.startDrawing(GL11.GL_QUADS, DefaultVertexFormats.ITEM);
        Insulation insulation = BlockCable.getInsulation(stack);
        MetalMaterial material = ((BlockCable) ((ItemBlock) stack.getItem()).getBlock()).baseProps.material;
        renderCableBlock(material, insulation, TileEntityCable.DEFAULT_INSULATION_COLOR, renderState, new IVertexOperation[0],
            1 << EnumFacing.SOUTH.getIndex() | 1 << EnumFacing.NORTH.getIndex() | 1 << 7);
        renderState.draw();
        GlStateManager.disableBlend();
    }

    @Override
    public boolean renderBlock(IBlockAccess world, BlockPos pos, IBlockState state, BufferBuilder buffer) {
        CCRenderState renderState = CCRenderState.instance();
        renderState.reset();
        renderState.bind(buffer);
        renderState.lightMatrix.locate(world, pos);
        IVertexOperation[] pipeline = new IVertexOperation[2];
        pipeline[0] = new Translation(pos);
        pipeline[1] = renderState.lightMatrix;

        TileEntityCable tileEntityCable = BlockCable.getCableTileEntity(world, pos);
        int paintingColor = tileEntityCable.getInsulationColor();
        int connectedSidesMask = BlockCable.getActualConnections(tileEntityCable, world, pos);

        Insulation insulation = state.getValue(BlockCable.INSULATION);
        MetalMaterial material = ((BlockCable) state.getBlock()).baseProps.material;

        renderCableBlock(material, insulation, paintingColor, renderState, pipeline, connectedSidesMask);
        return true;
    }

    public void renderCableBlock(MetalMaterial material, Insulation insulation1, int insulationColor1, CCRenderState state, IVertexOperation[] pipeline, int connectMask) {
        MaterialIconSet iconSet = material.materialIconSet;
        int wireColor = GTUtility.convertRGBtoOpaqueRGBA(material.materialRGB);
        float thickness = insulation1.thickness;

        IVertexOperation[] wire = ArrayUtils.addAll(pipeline, new IconTransformation(wireTextures.get(iconSet)), new ColourMultiplier(wireColor));
        IVertexOperation[] overlays = wire;
        IVertexOperation[] insulation = wire;

        if(insulation1.insulationLevel != -1) {
            int insulationColor = GTUtility.convertRGBtoOpaqueRGBA(insulationColor1);
            ColourMultiplier multiplier = new ColourMultiplier(insulationColor);
            insulation = ArrayUtils.addAll(pipeline, new IconTransformation(insulationTextures[5]), multiplier);
            overlays = ArrayUtils.addAll(pipeline, new IconTransformation(insulationTextures[insulation1.insulationLevel]), multiplier);
        }

        Cuboid6 cuboid6 = BlockCable.getSideBox(null, thickness);
        for(EnumFacing renderedSide : EnumFacing.VALUES) {
            if((connectMask & 1 << renderedSide.getIndex()) == 0) {
                int oppositeIndex = renderedSide.getOpposite().getIndex();
                if((connectMask & 1 << oppositeIndex) > 0 && (connectMask & ~(1 << oppositeIndex)) == 0) {
                    //if there is something on opposite side, render overlay + wire
                    renderCableSide(state, wire, renderedSide, cuboid6);
                    renderCableSide(state, overlays, renderedSide, cuboid6);
                } else {
                    renderCableSide(state, insulation, renderedSide, cuboid6);
                }
            }
        }

        renderCableCube(connectMask, state, insulation, wire, overlays, EnumFacing.DOWN, thickness);
        renderCableCube(connectMask, state, insulation, wire, overlays, EnumFacing.UP, thickness);
        renderCableCube(connectMask, state, insulation, wire, overlays, EnumFacing.WEST, thickness);
        renderCableCube(connectMask, state, insulation, wire, overlays, EnumFacing.EAST, thickness);
        renderCableCube(connectMask, state, insulation, wire, overlays, EnumFacing.NORTH, thickness);
        renderCableCube(connectMask, state, insulation, wire, overlays, EnumFacing.SOUTH, thickness);
    }

    private static void renderCableCube(int connections, CCRenderState renderState, IVertexOperation[] pipeline, IVertexOperation[] wire, IVertexOperation[] overlays, EnumFacing side, float thickness) {
        if((connections & 1 << side.getIndex()) > 0) {
            boolean isItem = (connections & 1 << 7) > 0;
            Cuboid6 cuboid6 = BlockCable.getSideBox(side, thickness);
            for(EnumFacing renderedSide : EnumFacing.VALUES) {
                if(renderedSide == side || renderedSide == side.getOpposite()) {
                    if(isItem) {
                        renderCableSide(renderState, wire, renderedSide, cuboid6);
                        renderCableSide(renderState, overlays, renderedSide, cuboid6);
                    }
                } else renderCableSide(renderState, pipeline, renderedSide, cuboid6);
            }
        }
    }

    private static void renderCableSide(CCRenderState renderState, IVertexOperation[] pipeline, EnumFacing side, Cuboid6 cuboid6) {
        BlockFace blockFace = blockFaces.get();
        blockFace.loadCuboidFace(cuboid6, side.getIndex());
        renderState.setPipeline(blockFace, 0, blockFace.verts.length, pipeline);
        renderState.render();
    }

    @Override
    public void renderBrightness(IBlockState state, float brightness) {
        renderItem(new ItemStack(state.getBlock(), 1, state.getBlock().getMetaFromState(state)), TransformType.FIXED);
    }

    @Override
    public void handleRenderBlockDamage(IBlockAccess world, BlockPos pos, IBlockState state, TextureAtlasSprite sprite, BufferBuilder buffer) {
        //TODO implement properly
    }

    @Override
    public void registerTextures(TextureMap map) {
    }

    @Override
    public IModelState getTransforms() {
        return TRSRTransformation.identity();
    }

    @Override
    public Pair<? extends IBakedModel, Matrix4f> handlePerspective(TransformType cameraTransformType) {
        if(BLOCK_TRANSFORMS.containsKey(cameraTransformType)) {
            return Pair.of(this, BLOCK_TRANSFORMS.get(cameraTransformType).getMatrix());
        }
        return Pair.of(this, null);
    }

    @Override
    public TextureAtlasSprite getParticleTexture() {
        return TextureUtils.getMissingSprite();
    }

    @Override
    public boolean isBuiltInRenderer() {
        return true;
    }

    @Override
    public boolean isAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public Set<TextureAtlasSprite> getHitEffects(@Nonnull RayTraceResult traceResult, IBlockState state, IBlockAccess world, BlockPos pos) {
        return getDestroyEffects(state, world, pos);
    }

    @Override
    public Set<TextureAtlasSprite> getDestroyEffects(IBlockState state, IBlockAccess world, BlockPos pos) {
        Insulation insulation = state.getValue(BlockCable.INSULATION);
        MetalMaterial material = ((BlockCable) state.getBlock()).baseProps.material;
        return Collections.singleton(insulation.insulationLevel > -1 ? insulationTextures[5] : wireTextures.get(material.materialIconSet));
    }
}
