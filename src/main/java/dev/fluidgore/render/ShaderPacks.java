package dev.fluidgore.render;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Whether a shader pack is drawing the world (Iris). A pack lights everything its own way, so the few things here
 * that paint light themselves (the sun's glint on wet blood) are left to it. Asked through Iris's public API by name:
 * Fluid Gore does not depend on Iris.
 */
public final class ShaderPacks {
	private static boolean looked;
	private static @Nullable Object api;
	private static @Nullable Method inUse;

	private ShaderPacks() {
	}

	public static boolean inUse() {
		if (!looked) {
			looked = true;
			if (FabricLoader.getInstance().isModLoaded("iris")) {
				try {
					Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
					api = type.getMethod("getInstance").invoke(null);
					inUse = type.getMethod("isShaderPackInUse");
				} catch (ReflectiveOperationException | LinkageError e) {
					api = null;
					inUse = null;
				}
			}
		}

		if (api == null || inUse == null) {
			return false;
		}

		try {
			return Boolean.TRUE.equals(inUse.invoke(api));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}
}
