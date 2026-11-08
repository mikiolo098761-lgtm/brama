package brama.client;

import brama.block.AbstractGateBlock;
import brama.block.DrawbridgeBlock;
import brama.block.GateAssembly;
import brama.block.GateSize;
import brama.block.MedievalGateBlock;
import brama.block.PortcullisBlock;
import brama.blockentity.GateBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

import java.util.List;

/**
 * Draws every assembled gate: geometry from the Blockbench kits in {@link GateModels}, painted with
 * the single gate-wide image from {@link GateTextures}.
 *
 * <h2>Why the UVs are projected</h2>
 *
 * <p>The obvious way to cover a multi-block gate is to tile a one-block model across it. That was the
 * previous approach and it looked wrong: every cell repeated the same 16x16 image, so a 3x3 gate read
 * as nine little panels rather than one gate.
 *
 * <p>So the models here supply <em>shape only</em>. Each quad's UV is recomputed from where its
 * vertex sits within the gate, and sampled from one image spanning the whole gate. The texture is
 * then continuous no matter how the geometry is arranged — which is what lets the portcullis keep
 * repeating bars (they have to land on block boundaries) while still looking like one grate.
 *
 * <h2>Why it lights like a block, not an entity</h2>
 *
 * <p>A gate is masonry, so it has to shade like the wall it sits in. Drawing it on an entity render
 * type made it shade like a mob instead: those shaders light each face from the quad's normal, which
 * left the gate's brightness depending on where you stood, and dropped detail at range because the
 * entity sheet is not mipmapped.
 *
 * <p>It now draws on {@code RenderType.cutoutMipped()}, the chunk type the surrounding wall uses, and
 * carries vanilla's fixed per-face shade in its vertex colour exactly as a baked block model does.
 * Normals no longer affect brightness at all — which also disposes of a subtler problem, that
 * stretching a leaf to size skews its normals.
 *
 * <p>Beware {@code Sheets.cutoutBlockSheet()}: the name suggests it belongs here, but it is
 * {@code RenderType.entityCutout} pointed at the block atlas — it lights from the normal like a mob,
 * and it takes the entity vertex format.
 *
 * <p><b>Vertex order matters here.</b> {@code DefaultVertexFormat.BLOCK} is position, colour, UV,
 * lightmap, normal — with <em>no overlay</em>, since the overlay is the entity damage flash and
 * blocks have no use for it. Miss an element and {@code endVertex} throws "Not filled all elements
 * of the vertex"; write one too many and there is no error at all — the overlay lands in the
 * lightmap slot, shunts everything after it along, and the gate renders pitch black.
 *
 * <p>Still emitted as raw quads: the drawbridge <b>chain</b>. Its length and angle change every frame,
 * so no fixed model can describe it.
 */
public class GateRenderer implements BlockEntityRenderer<GateBlockEntity> {

    private static final float THICKNESS = GateAssembly.THICKNESS;

    /** Model cells span 0..1; shifting them back half a block centres them on the gate plane. */
    private static final float CENTRE_ON_PLANE = -0.5F;

    /** Block models are position-independent here, so the seed only has to be stable. */
    private static final RandomSource RANDOM = RandomSource.create();
    private static final long QUAD_SEED = 42L;

    /** Vanilla's chain sprite, already on the block atlas because the chain block uses it. */
    private static final ResourceLocation CHAIN_TEXTURE = new ResourceLocation("minecraft", "block/chain");
    /** Vanilla's chain model puts its two crossed planes in the leftmost 6 pixels of the sprite. */
    private static final float CHAIN_UV_SPAN = 3.0F;
    private static final float CHAIN_WIDTH = 3.0F / 16.0F;

    /** Bascule beam geometry, in blocks. */
    private static final float ARM_PIVOT_HEIGHT = 1.0F;
    private static final float ARM_LENGTH_FRACTION = 0.6F;

    /**
     * How far a gate keeps drawing, in blocks.
     *
     * <p>Block entities stop rendering at 64 by default, which is fine for a chest but absurd for a
     * castle gate — it is the size of a building and vanished well before the wall it sits in did.
     * 256 matches the beacon, the one vanilla block entity meant to be seen from across the map.
     *
     * <p>Raising it costs almost nothing: a gate is a handful of quads and is skipped entirely by the
     * distance check below the moment it is out of range.
     *
     * <p>This cannot exceed the player's own render distance, and no setting here would change that.
     * Past it the client has not loaded the chunk at all, so there is no gate to draw — that is the
     * point where an impostor would be the only option, and it would need its own copy of the world
     * to draw from.
     */
    private static final int VIEW_DISTANCE = 256;

    public GateRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    @Override
    public void render(GateBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        BlockState state = blockEntity.getBlockState();
        if (!GateAssembly.isOrigin(state) || !(state.getBlock() instanceof AbstractGateBlock gate)) {
            return;
        }

        Direction.Axis axis = state.getValue(AbstractGateBlock.AXIS);
        // From the block entity, not the blockstate: see AbstractGateBlock#sizeAt.
        GateSize size = blockEntity.size();
        int damage = Math.min(state.getValue(AbstractGateBlock.DAMAGE), GateSize.MAX_DAMAGE_STAGES - 1);
        float progress = blockEntity.openProgress(partialTick);
        int light = sampleLight(blockEntity, axis, size, packedLight);
        Shading shading = Shading.forGate(blockEntity.getLevel(), axis);

        // One image for the whole gate, named by the block itself. That indirection is what lets
        // eleven woods and four gate types share this one renderer.
        TextureAtlasSprite sprite = sprite(GateTextures.of(gate.textureFamily()).sprite(size, damage));

        // A chunk render type, so the gate goes through the same shader path as the wall around it.
        // Not Sheets.cutoutBlockSheet(): despite the name that is RenderType.entityCutout on the
        // block atlas, so it lights from the normal like a mob and wants an overlay this format has
        // no slot for. packedOverlay is deliberately unused here.
        VertexConsumer consumer = buffers.getBuffer(RenderType.cutoutMipped());

        poseStack.pushPose();
        // Fold the Z-axis case into the same local frame as the X one: afterwards local +X is the
        // gate's width direction and the panel plane sits at local z = 0, so all the geometry below
        // only has to be written once.
        if (axis == Direction.Axis.X) {
            poseStack.translate(0.0F, 0.0F, 0.5F);
        } else {
            poseStack.translate(0.5F, 0.0F, 0.0F);
            poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
        }

        if (state.getBlock() instanceof PortcullisBlock) {
            renderPortcullis(poseStack, consumer, sprite, light, size, progress, shading);
        } else if (state.getBlock() instanceof DrawbridgeBlock drawbridge) {
            // Rotating about local +X carries +Y towards +Z. The Z-axis case is inverted because the
            // frame above maps local +Z onto world -X.
            boolean fallPositive = state.getValue(DrawbridgeBlock.FALL_POSITIVE);
            float fallSign = axis == Direction.Axis.X
                    ? (fallPositive ? 1.0F : -1.0F)
                    : (fallPositive ? -1.0F : 1.0F);
            boolean arms = drawbridge.hasSupportArms();
            GateModels.Kit kit = arms ? GateModels.BASCULE : GateModels.DRAWBRIDGE;

            renderDrawbridge(poseStack, consumer, sprite, light, size, progress, fallSign, arms, kit, shading);
        } else {
            // Sign of the Y rotation that carries a leaf towards the positive end of the
            // perpendicular world axis. The Z case is inverted because the frame above maps local
            // +Z onto world -X.
            boolean swingPositive = state.hasProperty(MedievalGateBlock.SWING_POSITIVE)
                    && state.getValue(MedievalGateBlock.SWING_POSITIVE);
            float swingSign = axis == Direction.Axis.X
                    ? (swingPositive ? -1.0F : 1.0F)
                    : (swingPositive ? 1.0F : -1.0F);

            renderLeaf(poseStack, consumer, sprite, light, size, progress, swingSign, shading, false);
            renderLeaf(poseStack, consumer, sprite, light, size, progress, swingSign, shading, true);
        }
        poseStack.popPose();
    }

    // Looked up per frame rather than cached: sprites are invalidated by a resource reload.
    private static TextureAtlasSprite sprite(ResourceLocation texture) {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
    }

    /**
     * Vanilla's fixed per-face brightness, the same table a baked block model bakes into its colours:
     * full from above, half from below, and two horizontal values.
     *
     * <p>Held in the gate's own frame rather than the world's. The renderer works in a local frame
     * where the gate always spans local +X, so which world direction a face points depends on the
     * gate's axis — a gate running east-west shows its broad faces north and south, and one running
     * north-south shows them east and west. Picking the right pair here is what makes the gate match
     * the wall beside it instead of being a shade out.
     */
    private record Shading(float flat, float edge, float top, float bottom) {

        static Shading forGate(Level level, Direction.Axis axis) {
            if (level == null) {
                return new Shading(0.8F, 0.6F, 1.0F, 0.5F);
            }
            float northSouth = level.getShade(Direction.NORTH, true);
            float eastWest = level.getShade(Direction.EAST, true);
            boolean broadFacesNorth = axis == Direction.Axis.X;

            return new Shading(
                    broadFacesNorth ? northSouth : eastWest,
                    broadFacesNorth ? eastWest : northSouth,
                    level.getShade(Direction.UP, true),
                    level.getShade(Direction.DOWN, true));
        }

        /** @param facing the quad's direction in the gate's local frame. */
        float of(Direction facing) {
            return switch (facing.getAxis()) {
                case Z -> this.flat;
                case X -> this.edge;
                case Y -> facing == Direction.UP ? this.top : this.bottom;
            };
        }

        /** A lowered drawbridge deck is a floor, so its broad face has to brighten towards one. */
        Shading flattening(float progress) {
            return new Shading(Mth.lerp(progress, this.flat, this.top), this.edge, this.top, this.bottom);
        }
    }

    /**
     * One leaf of the swinging gate, drawn as a single piece stretched to the leaf's size.
     *
     * @param farLeaf false for the leaf hinged at the low end of the gate's axis, true for the one
     *                hinged at the high end. The two mirror each other exactly.
     */
    private void renderLeaf(PoseStack poseStack, VertexConsumer consumer, TextureAtlasSprite sprite,
                            int light, GateSize size, float progress,
                            float swingSign, Shading shading, boolean farLeaf) {
        float gateWidth = size.width();
        float leafWidth = gateWidth / 2.0F;
        int height = size.height();
        float sign = farLeaf ? -swingSign : swingSign;

        poseStack.pushPose();
        poseStack.translate(farLeaf ? gateWidth : 0.0F, 0.0F, 0.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(sign * progress * 90.0F));

        // Turning the whole far leaf around is what mirrors it. Every piece then places identically
        // to the near leaf, and the detail lands on the correct outer edge for free. A negative
        // scale would do the same thing visually but invert the winding, and the faces would vanish.
        if (farLeaf) {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        }

        // Each leaf takes its own half of the gate image, so the two halves meet as one picture when
        // the gate is shut. The far leaf reads its half backwards because it has been turned around.
        float u0 = farLeaf ? 1.0F : 0.0F;
        float du = farLeaf ? -0.5F : 0.5F;

        poseStack.pushPose();
        poseStack.translate(0.0F, 0.0F, CENTRE_ON_PLANE);
        poseStack.scale(leafWidth, height, 1.0F);
        emitModel(poseStack, consumer, GateModels.WOODEN.body(size), light, shading,
                Projection.of(u0, du, 1.0F, -1.0F, leafWidth, height, Projection.UPRIGHT), sprite);
        poseStack.popPose();
        poseStack.popPose();
    }

    /**
     * A grid of bars spanning the whole footprint, winched straight up, with spikes along its
     * underside.
     *
     * <p>The only body that tiles rather than stretches: a grate is defined by its gaps, and they
     * have to land on block boundaries. The projected texture keeps the metal continuous anyway.
     */
    private void renderPortcullis(PoseStack poseStack, VertexConsumer consumer, TextureAtlasSprite sprite,
                                  int light, GateSize size, float progress, Shading shading) {
        GateModels.Kit kit = GateModels.PORTCULLIS;
        int width = size.width();
        int height = size.height();

        poseStack.pushPose();
        poseStack.translate(0.0F, progress * size.liftHeight(), 0.0F);

        ResourceLocation body = kit.body(size);
        for (int column = 0; column < width; column++) {
            for (int row = 0; row < height; row++) {
                poseStack.pushPose();
                poseStack.translate(column, row, CENTRE_ON_PLANE);
                emitModel(poseStack, consumer, body, light, shading,
                        Projection.of((float) column / width, 1.0F / width,
                                1.0F - (float) row / height, -1.0F / height, 1.0F, 1.0F,
                                Projection.UPRIGHT), sprite);
                poseStack.popPose();
            }
        }

        // The tiled cell drops a bar every 8px from its own left edge, so the last one always falls
        // short of the gate's far side and the rails would end in mid air. One closing bar per row
        // finishes the grid off.
        ResourceLocation edge = kit.piece(GateModels.EDGE, size);
        for (int row = 0; row < height; row++) {
            poseStack.pushPose();
            poseStack.translate(width - 1, row, CENTRE_ON_PLANE);
            emitModel(poseStack, consumer, edge, light, shading,
                    Projection.of((float) (width - 1) / width, 1.0F / width,
                            1.0F - (float) row / height, -1.0F / height, 1.0F, 1.0F,
                            Projection.UPRIGHT), sprite);
            poseStack.popPose();
        }

        poseStack.popPose();
    }

    /**
     * A deck hinged along its bottom edge, plus the two chains holding it.
     *
     * <p>The deck's thickness has to sit on the side that ends up <em>above</em> the hinge once
     * rotated, otherwise the lowered bridge renders below its own collision slab — hence the
     * thickness flipping with the fall direction.
     */
    private void renderDrawbridge(PoseStack poseStack, VertexConsumer consumer, TextureAtlasSprite sprite,
                                  int light, GateSize size, float progress, float fallSign,
                                  boolean supportArms, GateModels.Kit kit, Shading shading) {
        int width = size.width();
        int length = size.height();
        float angle = fallSign * progress * 90.0F;
        Shading deckShading = shading.flattening(progress);

        poseStack.pushPose();
        poseStack.mulPose(Axis.XP.rotationDegrees(angle));
        float zNear = fallSign > 0.0F ? -THICKNESS : 0.0F;
        poseStack.translate(0.0F, 0.0F, zNear);

        // The deck model spans z 0..THICKNESS on its own, matching the collision slab, so it is
        // placed flat rather than centred the way the upright gates are.
        poseStack.pushPose();
        poseStack.scale(width, length, 1.0F);
        emitModel(poseStack, consumer, kit.body(size), light, deckShading,
                Projection.of(0.0F, 1.0F, 1.0F, -1.0F, width, length, Projection.DECK), sprite);
        poseStack.popPose();

        // Banded at the two ends only. Running a batten down every row would be the repetition
        // problem all over again.
        ResourceLocation batten = kit.piece(GateModels.BATTEN, size);
        for (int column = 0; column < width; column++) {
            placeDetail(poseStack, consumer, light, deckShading, batten, column, 0.0F, 0.0F);
            placeDetail(poseStack, consumer, light, deckShading, batten, column, length - 1.0F, 0.0F);
        }
        poseStack.popPose();

        TextureAtlasSprite chainSprite = sprite(CHAIN_TEXTURE);

        float radians = (float) Math.toRadians(angle);
        float sin = (float) Math.sin(radians);
        float cos = (float) Math.cos(radians);

        // Far edge of the deck, carried round by the same rotation the deck just used. Both chain
        // ends live in this local frame, so the chain follows the deck with no extra state.
        float deckTipY = length * cos;
        float deckTipZ = length * sin;

        float pivotY = length + ARM_PIVOT_HEIGHT;
        // Whole blocks, because the arm is tiled from a one-block beam model. Rounding here rather
        // than leaving a stub keeps the beam's end flush and the chain anchored to real geometry.
        int armBlocks = Math.max(1, Math.round(length * ARM_LENGTH_FRACTION));

        for (int side = 0; side < 2; side++) {
            float x = side == 0 ? 0.35F : width - 0.35F;

            if (!supportArms) {
                // Plain variant: the chain runs straight to a fixed anchor on the wall.
                chain(poseStack, consumer, chainSprite, light, shading,
                        x, pivotY, fallSign > 0.0F ? -0.5F : 0.5F, x, deckTipY, deckTipZ);
                continue;
            }

            // Bascule: the beam swings through the same angle as the deck, so beam and deck stay
            // parallel and the chain between their tips keeps a constant length -- a parallelogram
            // linkage, which is what the real mechanism is.
            poseStack.pushPose();
            poseStack.translate(x, pivotY, 0.0F);
            poseStack.mulPose(Axis.XP.rotationDegrees(angle));

            ResourceLocation beam = kit.piece(GateModels.BEAM, size);
            for (int block = 0; block < armBlocks; block++) {
                placeDetail(poseStack, consumer, light, shading, beam,
                        CENTRE_ON_PLANE, block, CENTRE_ON_PLANE);
            }
            placeDetail(poseStack, consumer, light, shading,
                    kit.piece(GateModels.COUNTERWEIGHT, size), CENTRE_ON_PLANE, -1.0F, CENTRE_ON_PLANE);
            poseStack.popPose();

            chain(poseStack, consumer, chainSprite, light, shading,
                    x, pivotY + armBlocks * cos, armBlocks * sin, x, deckTipY, deckTipZ);
        }
    }

    /**
     * The window of the gate's image that one piece of geometry occupies.
     *
     * <p>{@code u0/du} and {@code v0/dv} map model-local 0..1 onto that window. {@code uPerBlock} and
     * {@code vPerBlock} say how much of the image one block covers, which is what the edge faces need
     * — see {@link #emitQuads}.
     */
    private record Projection(float u0, float du, float v0, float dv, float uPerBlock, float vPerBlock,
                              float depthCentre) {

        /** Upright gates hang their panel on the middle of the block. */
        static final float UPRIGHT = 0.5F;
        /** The bridge deck lies along the bottom of its block instead, in the collision slab. */
        static final float DECK = THICKNESS / 2.0F;

        /**
         * @param blocksX,blocksY how many blocks this piece's local 0..1 spans.
         * @param depthCentre     where the piece's surface sits in z. Edge faces measure their depth
         *                        offset from here, so getting it wrong throws that offset across the
         *                        whole image: the deck sits at 0.125, and measuring it from 0.5 like
         *                        an upright gate pushed every edge half a block off before clamping.
         */
        static Projection of(float u0, float du, float v0, float dv, float blocksX, float blocksY,
                             float depthCentre) {
            return new Projection(u0, du, v0, dv, du / blocksX, dv / blocksY, depthCentre);
        }
    }

    /** Draws a detail piece, keeping the UVs and texture authored into its own model. */
    private void placeDetail(PoseStack poseStack, VertexConsumer consumer, int light, Shading shading,
                             ResourceLocation model, float x, float y, float z) {
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        emitModel(poseStack, consumer, model, light, shading, null, null);
        poseStack.popPose();
    }

    /**
     * @param uv the window of the gate's image to project across this model, or null to keep the UVs
     *           authored into it.
     */
    private void emitModel(PoseStack poseStack, VertexConsumer consumer, ResourceLocation model,
                           int light, Shading shading, Projection uv, TextureAtlasSprite sprite) {
        BakedModel baked = GateModels.baked(model);
        PoseStack.Pose pose = poseStack.last();

        RANDOM.setSeed(QUAD_SEED);
        emitQuads(pose, consumer, baked.getQuads(null, null, RANDOM, ModelData.EMPTY, null),
                light, shading, uv, sprite);
        for (Direction face : Direction.values()) {
            RANDOM.setSeed(QUAD_SEED);
            emitQuads(pose, consumer, baked.getQuads(null, face, RANDOM, ModelData.EMPTY, null),
                    light, shading, uv, sprite);
        }
    }

    /**
     * Vertex layout for {@code DefaultVertexFormat.BLOCK}: eight ints each — position as float bits,
     * colour, UV, light, normal. Position is always taken; the UV is taken only when the caller is
     * not projecting one; the rest is replaced, because the gate supplies its own light and shade.
     *
     * <p>A flat projection is degenerate on the faces it hits edge-on: every corner of a face at
     * constant x lands on the same u, so the whole edge samples one column of pixels smeared along
     * its length. Those faces take their missing coordinate from depth instead, at the same pixel
     * density as the rest of the gate, so an edge shows a real slice of the timber it belongs to.
     */
    private static void emitQuads(PoseStack.Pose pose, VertexConsumer consumer, List<BakedQuad> quads,
                                  int light, Shading shading, Projection uv, TextureAtlasSprite sprite) {
        for (BakedQuad quad : quads) {
            int[] data = quad.getVertices();
            Direction facing = quad.getDirection();
            Vec3i normal = facing.getNormal();
            float shade = shading.of(facing);
            Direction.Axis axis = facing.getAxis();

            for (int corner = 0; corner < 4; corner++) {
                int base = corner * 8;
                float x = Float.intBitsToFloat(data[base]);
                float y = Float.intBitsToFloat(data[base + 1]);
                float z = Float.intBitsToFloat(data[base + 2]);

                float u;
                float v;
                if (uv == null) {
                    u = Float.intBitsToFloat(data[base + 4]);
                    v = Float.intBitsToFloat(data[base + 5]);
                } else {
                    float pu = uv.u0() + x * uv.du();
                    float pv = uv.v0() + y * uv.dv();
                    // Depth measured from the gate plane, so an edge samples alongside its own face.
                    float depth = z - uv.depthCentre();
                    if (axis == Direction.Axis.X) {
                        pu += uv.uPerBlock() * depth;
                    } else if (axis == Direction.Axis.Y) {
                        pv += uv.vPerBlock() * depth;
                    }
                    u = sprite.getU(clamp(pu) * 16.0F);
                    v = sprite.getV(clamp(pv) * 16.0F);
                }

                vertex(pose, consumer, shade, light, normal.getX(), normal.getY(), normal.getZ(),
                        x, y, z, u, v);
            }
        }
    }

    /**
     * Keeps a projected coordinate inside the gate's own image.
     *
     * <p>Load-bearing, not defensive. The depth nudge above pushes edge faces slightly off the gate,
     * and the faces that need it most are the ones already at the very edge — the top of a leaf sits
     * at v = 0, so any outward nudge goes negative. These are atlas coordinates, so off the end of
     * one sprite is the middle of whichever sprite got stitched next door: the top of the gate came
     * out striped blue and yellow. Clamping costs a hairline of flat colour on a 4px rim.
     */
    private static float clamp(float coordinate) {
        return Math.min(1.0F, Math.max(0.0F, coordinate));
    }

    /** Draws one chain as two crossed quads spanning the line from one point to the other. */
    private static void chain(PoseStack poseStack, VertexConsumer consumer, TextureAtlasSprite sprite,
                              int light, Shading shading,
                              float ax, float ay, float az, float bx, float by, float bz) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-4F) {
            return;
        }

        // Align local +Y with the line: yaw about Y, then tilt away from vertical about X.
        float yaw = (float) Math.atan2(dx, dz);
        float pitch = (float) Math.acos(dy / length);

        poseStack.pushPose();
        poseStack.translate(ax, ay, az);
        poseStack.mulPose(Axis.YP.rotation(yaw));
        poseStack.mulPose(Axis.XP.rotation(pitch));

        PoseStack.Pose pose = poseStack.last();
        float half = CHAIN_WIDTH / 2.0F;
        float shade = shading.edge();
        float u0 = sprite.getU0();
        float u1 = sprite.getU(CHAIN_UV_SPAN);
        float u2 = sprite.getU(CHAIN_UV_SPAN * 2.0F);

        // One sprite copy per block of chain, so the links keep their size however long it gets.
        // The repetition has to be geometric: V cannot run past the sprite's bounds on an atlas, it
        // would sample whatever texture happens to sit next door.
        for (float y = 0.0F; y < length - 1.0E-4F; y += 1.0F) {
            float segment = Math.min(1.0F, length - y);
            float v0 = sprite.getV0();
            float v1 = sprite.getV(segment * 16.0F);

            quadBothSides(pose, consumer, shade, light, 0, 0, 1,
                    -half, y, 0.0F, u0, v1, half, y, 0.0F, u1, v1,
                    half, y + segment, 0.0F, u1, v0, -half, y + segment, 0.0F, u0, v0);
            quadBothSides(pose, consumer, shade, light, 1, 0, 0,
                    0.0F, y, -half, u1, v1, 0.0F, y, half, u2, v1,
                    0.0F, y + segment, half, u2, v0, 0.0F, y + segment, -half, u1, v0);
        }

        poseStack.popPose();
    }

    /**
     * A quad wound both ways. The block sheet culls back faces, unlike the entity sheet the chain
     * used to sit on, and a chain is two crossed planes with no inside — so each has to be drawn from
     * both sides or it vanishes when you walk around the bridge.
     */
    private static void quadBothSides(PoseStack.Pose pose, VertexConsumer consumer, float shade,
                                      int light, int nx, int ny, int nz,
                                      float ax, float ay, float az, float au, float av,
                                      float bx, float by, float bz, float bu, float bv,
                                      float cx, float cy, float cz, float cu, float cv,
                                      float dx, float dy, float dz, float du, float dv) {
        vertex(pose, consumer, shade, light, nx, ny, nz, ax, ay, az, au, av);
        vertex(pose, consumer, shade, light, nx, ny, nz, bx, by, bz, bu, bv);
        vertex(pose, consumer, shade, light, nx, ny, nz, cx, cy, cz, cu, cv);
        vertex(pose, consumer, shade, light, nx, ny, nz, dx, dy, dz, du, dv);

        vertex(pose, consumer, shade, light, -nx, -ny, -nz, dx, dy, dz, du, dv);
        vertex(pose, consumer, shade, light, -nx, -ny, -nz, cx, cy, cz, cu, cv);
        vertex(pose, consumer, shade, light, -nx, -ny, -nz, bx, by, bz, bu, bv);
        vertex(pose, consumer, shade, light, -nx, -ny, -nz, ax, ay, az, au, av);
    }

    /**
     * One vertex in {@code DefaultVertexFormat.BLOCK} order. No {@code overlayCoords} call: that
     * element does not exist in this format, and writing one silently corrupts the lightmap.
     */
    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, float shade, int light,
                               int nx, int ny, int nz, float x, float y, float z, float u, float v) {
        consumer.vertex(pose.pose(), x, y, z)
                .color(shade, shade, shade, 1.0F)
                .uv(u, v)
                .uv2(light)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
    }

    /**
     * The light value handed to the renderer is sampled at the origin block, which sits inside the
     * gate itself and is usually pitch black. Sample the open air on either side of the gate
     * instead and take the brighter of the two.
     */
    private static int sampleLight(GateBlockEntity blockEntity, Direction.Axis axis, GateSize size, int fallback) {
        Level level = blockEntity.getLevel();
        if (level == null) {
            return fallback;
        }

        Direction perpendicular = Direction.get(Direction.AxisDirection.POSITIVE, GateAssembly.perpendicularAxis(axis));
        BlockPos center = GateAssembly.partPos(blockEntity.getBlockState(), blockEntity.getBlockPos(), size.width() / 2, size.height() / 2);

        return Math.max(
                LevelRenderer.getLightColor(level, center.relative(perpendicular)),
                LevelRenderer.getLightColor(level, center.relative(perpendicular.getOpposite())));
    }
}
