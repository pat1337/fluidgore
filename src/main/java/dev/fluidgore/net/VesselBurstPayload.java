package dev.fluidgore.net;

import dev.fluidgore.FluidGore;
import dev.fluidgore.fragment.FragmentPath;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * The wave and bounded server-resolved fragment paths of one pressure-vessel rupture, sent by whatever made the vessel
 * (the Cobble Tank add-on) before its damage and explosion packets. Fluid Gore plays the gore from it ({@link VesselBursts}).
 */
public record VesselBurstPayload(double x, double y, double z, float litres, float psi, float waveKg, float radius,
        long fragmentSeed, long occurrence, List<FragmentPath> paths) implements CustomPacketPayload {
    public static final Type<VesselBurstPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "vessel_burst"));
    public VesselBurstPayload { paths = List.copyOf(paths); }
    public static final StreamCodec<ByteBuf, VesselBurstPayload> CODEC = StreamCodec.of((buf, p) -> {
        if (!p.finite() || !p.validWave()) throw new io.netty.handler.codec.EncoderException("Invalid vessel rupture");
        buf.writeDouble(p.x); buf.writeDouble(p.y); buf.writeDouble(p.z);
        buf.writeFloat(p.litres); buf.writeFloat(p.psi); buf.writeFloat(p.waveKg); buf.writeFloat(p.radius); buf.writeLong(p.fragmentSeed); buf.writeLong(p.occurrence);
        buf.writeShort(p.paths.size());
        Vec3 origin = new Vec3(p.x, p.y, p.z);
        for (FragmentPath path : p.paths) path.write(buf, origin);
    }, buf -> {
        double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
        float litres = buf.readFloat(), psi = buf.readFloat(), wave = buf.readFloat(), radius = buf.readFloat();
        long seed = buf.readLong(), occurrence = buf.readLong(); int n = buf.readUnsignedShort();
        if (n > FragmentPath.MAX_PATHS) throw new io.netty.handler.codec.DecoderException("Too many fragment paths: " + n);
        List<FragmentPath> paths = new ArrayList<>(n); Vec3 origin = new Vec3(x, y, z);
        for (int i = 0; i < n; i++) paths.add(FragmentPath.read(buf, origin));
        VesselBurstPayload p = new VesselBurstPayload(x, y, z, litres, psi, wave, radius, seed, occurrence, paths);
        if (!p.finite() || !p.validWave()) throw new io.netty.handler.codec.DecoderException("Invalid vessel rupture");
        return p;
    });
    public boolean finite() {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x) > 3.0E7 || Math.abs(z) > 3.0E7 || Math.abs(y) > 3.0E7
                || !Float.isFinite(litres) || litres <= 0 || litres > 5000 || !Float.isFinite(psi) || psi < 0 || psi > 50000
                || !Float.isFinite(radius) || radius < 0 || radius > 200 || paths.size() > FragmentPath.MAX_PATHS) return false;
        HashSet<Integer> indices = new HashSet<>(); Vec3 origin = new Vec3(x, y, z);
        for (FragmentPath path : paths) if (!path.finite(origin) || !indices.add(path.index())) return false;
        return true;
    }
    public boolean validWave() {
        return Float.isFinite(waveKg) && waveKg >= 0 && waveKg <= dev.fluidgore.physics.Blast.tntKg(litres, psi) * 1.001;
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
