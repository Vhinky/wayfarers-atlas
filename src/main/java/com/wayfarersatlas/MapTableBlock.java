package com.wayfarersatlas;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * Wayfarer's Map Table: two blocks wide (like a bed). Right-click it with an Atlas to leave what you explored on
 * the table and take what others left there. Breaking the table loses its records.
 */
public class MapTableBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<MapTableBlock> CODEC = simpleCodec(MapTableBlock::new);

    public enum Part implements StringRepresentable {
        MAIN("main"), SIDE("side");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    // shapes of the table facing north (front = north); the second half is to the west of the main half
    private static final double[][] MAIN_BOXES = {
            {0, 13, 0, 16, 16, 16},  // top
            {12, 0, 1, 15, 13, 4},   // legs on the outer (east) edge
            {12, 0, 12, 15, 13, 15}
    };
    private static final Map<Direction, VoxelShape> MAIN_SHAPES = new EnumMap<>(Direction.class);
    private static final Map<Direction, VoxelShape> SIDE_SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            MAIN_SHAPES.put(d, shape(d, false));
            SIDE_SHAPES.put(d, shape(d, true));
        }
    }

    private static VoxelShape shape(Direction facing, boolean mirror) {
        int turns = switch (facing) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        VoxelShape s = Shapes.empty();
        for (double[] b : MAIN_BOXES) {
            double x1 = mirror ? 16 - b[3] : b[0], x2 = mirror ? 16 - b[0] : b[3];
            double z1 = b[2], z2 = b[5];
            for (int i = 0; i < turns; i++) { // clockwise seen from above, like the block model rotation
                double nx1 = 16 - z2, nx2 = 16 - z1, nz1 = x1, nz2 = x2;
                x1 = nx1;
                x2 = nx2;
                z1 = nz1;
                z2 = nz2;
            }
            s = Shapes.or(s, Shapes.box(x1 / 16, b[1] / 16, z1 / 16, x2 / 16, b[4] / 16, z2 / 16));
        }
        return s.optimize();
    }

    public MapTableBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.MAIN));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, PART);
    }

    /** Direction from this half to the other half. */
    private static Direction toOther(BlockState state) {
        Direction f = state.getValue(FACING);
        return state.getValue(PART) == Part.MAIN ? f.getCounterClockWise() : f.getClockWise();
    }

    /** Position of the main half (the table's records are stored under it). */
    public static BlockPos mainPos(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.MAIN ? pos : pos.relative(toOther(state));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction facing = ctx.getHorizontalDirection().getOpposite(); // the table faces the player
        BlockPos other = ctx.getClickedPos().relative(facing.getCounterClockWise()); // second half to the player's right
        Level level = ctx.getLevel();
        if (!level.getBlockState(other).canBeReplaced(ctx) || !level.getWorldBorder().isWithinBounds(other)) return null;
        return defaultBlockState().setValue(FACING, facing).setValue(PART, Part.MAIN);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            BlockPos other = pos.relative(toOther(state));
            level.setBlock(other, state.setValue(PART, Part.SIDE), 3);
            level.blockUpdated(pos, Blocks.AIR);
            state.updateNeighbourShapes(level, pos, 3);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (dir == toOther(state)) {
            boolean ok = neighbor.is(this) && neighbor.getValue(PART) != state.getValue(PART)
                    && neighbor.getValue(FACING) == state.getValue(FACING);
            return ok ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // creative: remove the other half without dropping the table
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) == Part.SIDE) {
            BlockPos main = pos.relative(toOther(state));
            BlockState ms = level.getBlockState(main);
            if (ms.is(this) && ms.getValue(PART) == Part.MAIN) {
                level.setBlock(main, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(player, 2001, main, Block.getId(ms));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && state.getValue(PART) == Part.MAIN && !newState.is(this)
                && level instanceof ServerLevel sl) {
            MapStore.delete(Net.tableId(sl, pos)); // the records were on the table
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return (state.getValue(PART) == Part.MAIN ? MAIN_SHAPES : SIDE_SHAPES).get(state.getValue(FACING));
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(WayfarersAtlas.ATLAS.get())) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) ClientHooks.openTable(mainPos(pos, state));
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (AtlasSlot.get(player).is(WayfarersAtlas.ATLAS.get())) { // the Atlas in the Atlas slot works too
            if (level.isClientSide) ClientHooks.openTable(mainPos(pos, state));
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            player.displayClientMessage(Component.translatable("msg.wayfarers_atlas.table_need_atlas"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
