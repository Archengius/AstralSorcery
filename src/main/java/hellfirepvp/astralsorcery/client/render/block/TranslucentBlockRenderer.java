package hellfirepvp.astralsorcery.client.render.block;

import codechicken.lib.render.block.ICCBlockRenderer;
import com.google.common.base.Preconditions;
import hellfirepvp.astralsorcery.client.util.AirBlockRenderWorld;
import hellfirepvp.astralsorcery.client.util.RenderingUtils;
import hellfirepvp.astralsorcery.common.tile.TileFakeTree;
import hellfirepvp.astralsorcery.common.tile.TileTranslucent;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;

import javax.annotation.Nullable;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

public class TranslucentBlockRenderer implements ICCBlockRenderer {

    private static @Nullable IBlockState getRenderBlockState(IBlockAccess world, BlockPos blockPos) {
        TileEntity tileEntity = world.getTileEntity(blockPos);
        if (tileEntity instanceof TileTranslucent) {
            return ((TileTranslucent) tileEntity).getFakedState();
        }
        else if (tileEntity instanceof TileFakeTree) {
            return ((TileFakeTree) tileEntity).getFakedState();
        }
        return null;
    }

    @Override
    public void handleRenderBlockDamage(IBlockAccess world, BlockPos pos, IBlockState state, TextureAtlasSprite sprite, BufferBuilder buffer) {
        IBlockState renderedBlockState = getRenderBlockState(world, pos);
        if (renderedBlockState == null || renderedBlockState.getRenderType() != EnumBlockRenderType.MODEL)
        {
            return;
        }

        TranslucentBlockWorldAccess virtualBlockAccess = new TranslucentBlockWorldAccess(world);
        IBlockState extendedBlockState = renderedBlockState.getActualState(virtualBlockAccess, pos);

        BlockRendererDispatcher blockRendererDispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
        IBakedModel blockModel = blockRendererDispatcher.getBlockModelShapes().getModelForState(extendedBlockState);
        IBakedModel damageBlockModel = net.minecraftforge.client.ForgeHooksClient.getDamageModel(blockModel, sprite, extendedBlockState, virtualBlockAccess, pos);

        // Lighting data and tinting is discarded since underlying buffer is a BakingVertexBuffer; only position, normal and sprite (plus sub-UV) are evaluated
        blockRendererDispatcher.getBlockModelRenderer().renderModel(virtualBlockAccess, damageBlockModel, extendedBlockState, pos, buffer, true);
    }

    @Override
    public boolean renderBlock(IBlockAccess world, BlockPos pos, IBlockState state, BufferBuilder buffer) {
        IBlockState renderedBlockState = getRenderBlockState(world, pos);

        if (renderedBlockState == null || renderedBlockState.getRenderType() != EnumBlockRenderType.MODEL)
        {
            return true;
        }

        TranslucentBlockWorldAccess virtualBlockAccess = new TranslucentBlockWorldAccess(world);
        IBlockState extendedBlockState = renderedBlockState.getActualState(virtualBlockAccess, pos);

        // We do not query the extended block state here intentionally
        BlockRendererDispatcher blockRendererDispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
        IBakedModel blockModel = blockRendererDispatcher.getBlockModelShapes().getModelForState(extendedBlockState);

        // Render the underlying block state model
        int startVertexIndex = buffer.getVertexCount();
        blockRendererDispatcher.getBlockModelRenderer().renderModel(virtualBlockAccess, blockModel, extendedBlockState, pos, buffer, true);
        int endVertexIndex = buffer.getVertexCount();

        IntBuffer builderIntBuffer = buffer.getByteBuffer().asIntBuffer();
        boolean isLittleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        int transparencyAmount = 50;

        // Update colors on the rendered model to add transparency
        for (int vertexIndex = startVertexIndex; vertexIndex < endVertexIndex; vertexIndex++) {
            int colorIndex = (vertexIndex * buffer.getVertexFormat().getSize() + buffer.getVertexFormat().getColorOffset()) / 4;

            // Update color (we do not touch the original tint, but we do want to add some transparency)
            int packedColor = builderIntBuffer.get(colorIndex);
            int originalAlpha = isLittleEndian ? ((packedColor >> 24) & 0xFF) : packedColor & 0xFF;
            int updatedAlpha = Math.max(originalAlpha - transparencyAmount, 1);

            int newPackedColor;
            if (isLittleEndian) {
                newPackedColor = (packedColor & 0xFFFFFF) | (updatedAlpha << 24);
            } else {
                newPackedColor = (packedColor & (~0xFF)) | (updatedAlpha & 0xFF);
            }
            builderIntBuffer.put(colorIndex, newPackedColor);
        }
        return true;
    }

    @Override
    public void renderBrightness(IBlockState state, float brightness) {
        // Not enough information to actually render the block
    }

    @Override
    public void registerTextures(TextureMap map) {
    }

    // Block access that makes translucent blocks appear as blocks they represent, and the rest of the blocks appear normally
    // Also disallows access to any tile entity in the world to maintain the consistent view
    private static class TranslucentBlockWorldAccess implements IBlockAccess {

        private final IBlockAccess underlyingBlockAccess;

        public TranslucentBlockWorldAccess(IBlockAccess underlyingBlockAccess) {
            this.underlyingBlockAccess = underlyingBlockAccess;
        }

        @Override
        public @Nullable TileEntity getTileEntity(BlockPos pos) {
            // This cannot be simulated adequately and consistently across fake and real blocks so do not allow tile entity access
            return null;
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            IBlockState replacementBlockState = getRenderBlockState(this.underlyingBlockAccess, pos);
            if (replacementBlockState != null) {
                // Appear as render block state if this is our own block or other block around us
                return replacementBlockState;
            }
            return this.underlyingBlockAccess.getBlockState(pos);
        }

        @Override
        public int getCombinedLight(BlockPos pos, int lightValue) {
            // We want to use the lighting information from the translucent blocks, not the simulated blocks
            return this.underlyingBlockAccess.getCombinedLight(pos, lightValue);
        }

        @Override
        public boolean isAirBlock(BlockPos pos) {
            IBlockState blockState = getBlockState(pos);
            return blockState.getBlock().isAir(blockState, this, pos);
        }

        @Override
        public int getStrongPower(BlockPos pos, EnumFacing direction) {
            return getBlockState(pos).getStrongPower(this, pos, direction);
        }

        @Override
        public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
            IBlockState blockState = getBlockState(pos);
            return blockState.getBlock().isSideSolid(blockState, this, pos, side);
        }

        @Override
        public Biome getBiome(BlockPos pos) {
            return this.underlyingBlockAccess.getBiome(pos);
        }

        @Override
        public WorldType getWorldType() {
            return this.underlyingBlockAccess.getWorldType();
        }
    }
}
