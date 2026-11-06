package dev.fluidgore.cobbletank;

import dev.fluidgore.physics.Blast;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A standing tank: its {@link TankState}, the fuse once lit, and the leak once cracked. The crack is where the blow
 * that opened it landed, so its jet points back the way the blow came and its thrust pushes the tank on the way it was
 * struck: struck from above, the thrust only presses it into the ground. The server runs the physics; the client gets
 * the pressure, the jet and the casing's state (an update every half second while it leaks) for the jet, the frost and
 * the sound.
 */
public class TankBlockEntity extends BlockEntity {
	/** Static friction of the casing on the ground (stone on stone). */
	static final double GRIP = 0.6;
	private TankState tank = new TankState();
	/** Which way the jet leaves, a unit vector (meaningless until it leaks). */
	private Vec3 jet = new Vec3(0.0, 1.0, 0.0);
	private int age;
	private long lastBlow;

	public TankBlockEntity(BlockPos pos, BlockState state) {
		super(CobbleTank.BLOCK_ENTITY, pos, state);
	}

	public TankState state() {
		return tank;
	}

	void set(TankState tank, Vec3 jet) {
		this.tank = tank;
		this.jet = jet;
		setChanged();
	}

	void lit() {
		tank.triggerTicks = TankBlock.FUSE;
		setChanged();
	}

	/** A deliberate valve opening. Gas leaves the clicked face, so recoil has a visible cause. */
	void vent(ServerLevel level, Direction face) {
		if (tank.leaking() || tank.empty()) return;
		tank.vent();
		jet = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		mark(level);
		level.playSound(null, worldPosition, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.5F, 0.6F);
	}

	/** A blast has reached this casing; weak or screened shocks can leave a leak instead of a forced chain burst. */
	void shock(ServerLevel level, float kPa, Vec3 from, @Nullable Entity cause) {
		boolean leaking = tank.leaking();
		if (tank.shock(kPa, level.getRandom()) == TankState.Blow.BURST) {
			TankState copy = tank.copy();
			level.removeBlock(worldPosition, false);
			TankBurst.later(level, Vec3.atCenterOf(worldPosition), copy, cause, 2 + level.getRandom().nextInt(6));
			return;
		}
		if (!leaking && tank.leaking()) {
			Vec3 d = from.subtract(Vec3.atCenterOf(worldPosition));
			jet = d.lengthSqr() > 1.0E-6 ? d.normalize() : new Vec3(0, 1, 0);
		}
		mark(level);
	}

	private void mark(ServerLevel level) {
		BlockState state = getBlockState();
		BlockState marked = state.setValue(TankBlock.SCARS, TankBlock.scars(Math.max(tank.damage, tank.heatDamage)))
				.setValue(TankBlock.LEAK, TankBlock.Hiss.of(tank));
		level.setBlock(worldPosition, marked, 3);
		setChanged();
		level.sendBlockUpdated(worldPosition, marked, marked, 2);
	}

	/** The point on the casing a jet along {@code dir} leaves from. */
	static Vec3 surface(Vec3 centre, Vec3 dir) {
		double most = Math.max(Math.abs(dir.x), Math.max(Math.abs(dir.y), Math.abs(dir.z)));
		return centre.add(dir.scale(0.5 / Math.max(most, 1.0E-3) + 0.02));
	}

	void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
		age++;
		RandomSource random = level.getRandom();
		if (state.getValue(TankBlock.LIT)) {
			// The crafted block's initiator is a game trigger, separate from environmental heating.
			if (tank.triggerTicks < 35 && random.nextInt(4) == 0) {
				level.playSound(null, pos, random.nextBoolean() ? SoundEvents.CAMPFIRE_CRACKLE : SoundEvents.SCAFFOLDING_BREAK, SoundSource.BLOCKS,
						1.2F, 0.6F + random.nextFloat() * 0.5F);
			}
		}

		// Environmental heat is not a fuse. It can be removed, and venting can outrun the heating.
		if (tank.advance(heated(level, pos)) && level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.TNT_EXPLODES)) {
			burst(level, null);
			return;
		}
		if (age % 10 == 0 && (tank.shellC > 25.0F || tank.heatDamage > 0.0F || tank.leaking())) mark(level);
		if (tank.shellC > 150.0F && age % 20 == 0) {
			level.playSound(null, pos, SoundEvents.SCAFFOLDING_BREAK, SoundSource.BLOCKS, 0.7F, 0.5F);
		}

		if (!tank.leaking()) {
			return;
		}

		if (tearsLoose(level, pos)) {
			TankState flying = tank.copy();
			Direction top = state.getValue(TankBlock.FACING);
			level.removeBlock(pos, false);
			TankEntity.launch(level, pos, flying, jet, top);
			return;
		}

		Jet.blow(level, surface(Vec3.atCenterOf(pos), jet), jet, tank.psiNow(), tank.area, null, age);
		Jet.chill(level, new AABB(pos), tank.temperature(), null);
		if (age % 40 == 0) {
			Jet.frost(level, pos, Direction.getApproximateNearest(jet), tank.temperature());
		}

		TankBlock.Hiss hiss = TankBlock.Hiss.of(tank);
		if (hiss != state.getValue(TankBlock.LEAK)) {
			level.setBlock(pos, state.setValue(TankBlock.LEAK, hiss), 3);
		} else if (age % 10 == 0) {
			level.sendBlockUpdated(pos, state, state, 2);
		}

		setChanged();
	}

	/**
	 * Whether the thrust moves it: it lifts the tank off the ground, or pushes it sideways harder than friction holds
	 * it (friction on its weight plus whatever of the thrust presses it down). A solid block on the side it is pushed
	 * towards takes the push.
	 */
	private boolean tearsLoose(ServerLevel level, BlockPos pos) {
		Vec3 force = jet.scale(-tank.thrust());
		Direction push = Direction.getApproximateNearest(force);
		BlockPos brace = pos.relative(push);
		if (level.getBlockState(brace).isFaceSturdy(level, brace, push.getOpposite())) {
			return false;
		}

		double weight = tank.mass() * Blast.GRAVITY;
		if (force.y > weight) {
			return true;
		}

		boolean resting = level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
		double sideways = Math.sqrt(force.x * force.x + force.z * force.z);
		return !resting || sideways > GRIP * (weight - force.y);
	}

	/** Fire, lava or a lit campfire right next to it heats it like a fuse. */
	static boolean heated(Level level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockState next = level.getBlockState(pos.relative(d));
			if (next.getBlock() instanceof BaseFireBlock || next.getFluidState().is(FluidTags.LAVA) || CampfireBlock.isLitCampfire(next)) {
				return true;
			}
		}

		return false;
	}

	/** A player hit it with {@code stack} (see {@link TankState#force}). */
	void blow(ServerLevel level, Player player, ItemStack stack) {
		long now = level.getGameTime();
		if (now - lastBlow < 4) {
			return;
		}

		lastBlow = now;
		boolean wasLeaking = tank.leaking();
		Vec3 centre = Vec3.atCenterOf(worldPosition);
		// The way the blow travelled: from the striker's eye into the tank.
		Vec3 along = centre.subtract(player.getEyePosition()).normalize();
		float force = TankState.force(stack);
		TankState.Blow blow = tank.blow(force, level.getRandom());
		Scrape.play(level, surface(centre, along.scale(-1.0)), along, blow, force);
		if (!stack.isEmpty() && blow != TankState.Blow.THUD) {
			stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
		}

		if (blow == TankState.Blow.BURST) {
			burst(level, player);
			return;
		}

		if (blow == TankState.Blow.THROUGH && !wasLeaking) {
			// The crack opens where it was struck: its jet comes back out at the striker, and pushes the tank on.
			jet = along.scale(-1.0);
			level.playSound(null, worldPosition, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.5F, 0.5F);
		}

		BlockState state = getBlockState();
		BlockState marked = state.setValue(TankBlock.SCARS, TankBlock.scars(tank.damage)).setValue(TankBlock.LEAK, TankBlock.Hiss.of(tank));
		if (marked != state) {
			level.setBlock(worldPosition, marked, 3);
		}

		setChanged();
		level.sendBlockUpdated(worldPosition, marked, marked, 2);
	}

	/** It bursts where it stands. */
	void burst(ServerLevel level, @Nullable Entity cause) {
		TankState copy = tank.copy();
		level.removeBlock(worldPosition, false);
		TankBurst.burst(level, Vec3.atCenterOf(worldPosition), copy, cause);
	}

	void clientTick(Level level, BlockPos pos, BlockState state) {
		age++;
		RandomSource random = level.getRandom();
		if ((state.getValue(TankBlock.LIT) || tank.shellC > 150.0F) && random.nextInt(2) == 0) {
			level.addParticle(ParticleTypes.SMOKE, pos.getX() + random.nextDouble(), pos.getY() + 1.02, pos.getZ() + random.nextDouble(), 0.0, 0.03, 0.0);
		}

		if (tank.leaking()) {
			TankFx.jet(this, level, surface(Vec3.atCenterOf(pos), jet), jet, tank, random);
		}
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		tank.save(output);
		output.putFloat("JetX", (float) jet.x);
		output.putFloat("JetY", (float) jet.y);
		output.putFloat("JetZ", (float) jet.z);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		tank.load(input);
		Vec3 j = new Vec3(input.getFloatOr("JetX", 0.0F), input.getFloatOr("JetY", 1.0F), input.getFloatOr("JetZ", 0.0F));
		jet = Double.isFinite(j.lengthSqr()) && j.lengthSqr() > 1.0E-4 ? j.normalize() : new Vec3(0.0, 1.0, 0.0);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveCustomOnly(registries);
	}
}
