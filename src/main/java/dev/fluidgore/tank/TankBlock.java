package dev.fluidgore.tank;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * The Cobble Tank: a pressure vessel holding the Roadster's air (600 L at 10,000 psi by default), crafted from a TNT
 * with cobblestone on its four sides. Placed like a piston, its top facing away from the block it was put against.
 * Light it like TNT (flint and steel, a fire charge, a burning arrow, fire or lava beside it, a redstone signal) and
 * the heat bursts it when the fuse has burnt; hit it hard enough and it leaks where it was struck (see
 * {@link TankState}). An explosion bursts it a moment later.
 * <p>
 * While it holds pressure it cannot be mined: blows cut into it instead, and leave their scars. Empty, it breaks like
 * stone and drops nothing. Sneak and use it with an empty hand to pick up a tank that has not been harmed.
 */
public class TankBlock extends BaseEntityBlock {
	public static final BooleanProperty LIT = BlockStateProperties.LIT;
	/** Which way its top points. */
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	public static final EnumProperty<Hiss> LEAK = EnumProperty.create("leak", Hiss.class);
	/** How cut up its casing looks: 0 untouched, 1 scratched, 2 chipped, 3 gouged to the fibre. */
	public static final IntegerProperty SCARS = IntegerProperty.create("scars", 0, 3);
	/** Ticks a lit tank burns before it bursts, as TNT's fuse. */
	public static final int FUSE = 80;

	/** What the crack sounds like from outside. */
	public enum Hiss implements StringRepresentable {
		NONE("none"),
		PINHOLE("pinhole"),
		CRACKED("cracked");

		private final String name;

		Hiss(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}

		static Hiss of(TankState tank) {
			return !tank.leaking() ? NONE : tank.cracked() ? CRACKED : PINHOLE;
		}
	}

	public TankBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(LIT, false).setValue(FACING, Direction.UP).setValue(LEAK, Hiss.NONE).setValue(SCARS, 0));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT, FACING, LEAK, SCARS);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		// Like a piston on its back: the top faces away from what it was placed against.
		return defaultBlockState().setValue(FACING, context.getClickedFace());
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	/** The scars of a casing cut {@code damage} deep. */
	static int scars(float damage) {
		return damage <= 0.0F ? 0 : damage < 0.25F ? 1 : damage < TankState.THROUGH ? 2 : 3;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TankBlockEntity(pos, state);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (type != Tanks.BLOCK_ENTITY) {
			return null;
		}

		return level.isClientSide() ? (l, p, s, e) -> ((TankBlockEntity) e).clientTick(l, p, s)
				: (l, p, s, e) -> ((TankBlockEntity) e).serverTick((ServerLevel) l, p, s);
	}

	private static @Nullable TankBlockEntity tank(BlockGetter level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof TankBlockEntity t ? t : null;
	}

	@Override
	protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
		TankBlockEntity tank = tank(level, pos);
		// Under pressure it does not give: every click is a blow instead (see attack).
		return tank != null && !tank.state().empty() ? 0.0F : super.getDestroyProgress(state, player, level, pos);
	}

	@Override
	protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
		TankBlockEntity tank = tank(level, pos);
		if (level instanceof ServerLevel server && tank != null && !player.isCreative()) {
			tank.blow(server, player, player.getMainHandItem());
		}
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (!oldState.is(state.getBlock()) && level.hasNeighborSignal(pos)) {
			light(level, pos, null);
		}
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
		if (level.hasNeighborSignal(pos)) {
			light(level, pos, null);
		}
	}

	/** Lights the fuse: the heat will burst it. Like TNT, not where TNT is not allowed to explode. */
	public static boolean light(Level level, BlockPos pos, @Nullable Entity by) {
		BlockState state = level.getBlockState(pos);
		TankBlockEntity tank = tank(level, pos);
		if (!(level instanceof ServerLevel server) || !state.is(Tanks.BLOCK) || tank == null || state.getValue(LIT) || tank.state().empty()) {
			return false;
		}

		if (!server.getGameRules().get(GameRules.TNT_EXPLODES)) {
			return false;
		}

		level.setBlock(pos, state.setValue(LIT, true), 3);
		tank.lit();
		level.playSound(null, pos, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 0.8F);
		level.gameEvent(by, GameEvent.PRIME_FUSE, pos);
		return true;
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (!stack.is(Items.FLINT_AND_STEEL) && !stack.is(Items.FIRE_CHARGE)) {
			return super.useItemOn(stack, state, level, pos, player, hand, hit);
		}

		if (level instanceof ServerLevel server && !server.getGameRules().get(GameRules.TNT_EXPLODES)) {
			player.sendOverlayMessage(Component.translatable("block.minecraft.tnt.disabled"));
			return InteractionResult.PASS;
		}

		if (light(level, pos, player)) {
			if (stack.is(Items.FLINT_AND_STEEL)) {
				stack.hurtAndBreak(1, player, hand.asEquipmentSlot());
			} else {
				stack.consume(1, player);
			}

			player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
		}

		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		TankBlockEntity tank = tank(level, pos);
		if (!player.isShiftKeyDown() || tank == null || state.getValue(LIT) || tank.state().damage > 0.0F || tank.state().fraction < 1.0F) {
			return super.useWithoutItem(state, level, pos, player, hit);
		}

		// Lifted down carefully, the way it was put there.
		if (level instanceof ServerLevel) {
			level.removeBlock(pos, false);
			if (!player.getAbilities().instabuild || !player.getInventory().contains(new ItemStack(Tanks.ITEM))) {
				player.getInventory().placeItemBackInInventory(new ItemStack(Tanks.ITEM), net.minecraft.util.Prediction.SERVER_ONLY);
			}

			level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.BLOCKS, 1.0F, 0.7F);
		}

		return InteractionResult.SUCCESS;
	}

	@Override
	protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
		if (level instanceof ServerLevel server && projectile.isOnFire() && projectile.mayInteract(server, hit.getBlockPos())) {
			light(level, hit.getBlockPos(), projectile.getOwner());
		}
	}

	@Override
	protected void onExplosionHit(BlockState state, ServerLevel level, BlockPos pos, Explosion explosion, BiConsumer<ItemStack, BlockPos> onHit) {
		TankBlockEntity tank = tank(level, pos);
		if (tank == null || explosion.getBlockInteraction() == Explosion.BlockInteraction.TRIGGER_BLOCK) {
			return;
		}

		// The blast cracks the casing: it goes a moment later, as TNT caught in a blast does.
		TankState copy = tank.state().copy();
		level.removeBlock(pos, false);
		TankBurst.later(level, net.minecraft.world.phys.Vec3.atCenterOf(pos), copy, explosion.getIndirectSourceEntity(), 2 + level.getRandom().nextInt(8));
	}

	@Override
	public boolean dropFromExplosion(Explosion explosion) {
		return false;
	}
}
