/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.game.minecraft;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.GameProviderHelper;
import net.fabricmc.loader.impl.game.minecraft.patch.BrandingPatch;
import net.fabricmc.loader.impl.game.minecraft.patch.EntrypointPatch;
import net.fabricmc.loader.impl.game.minecraft.patch.EntrypointPatchFML125;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.gui.FabricGuiEntry;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.ModDependencyImpl;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.UrlUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogHandler;

public class MinecraftGameProvider implements GameProvider {
	private static final String[] CLIENT_ENTRYPOINTS = { "net.minecraft.client.main.Main", "net.minecraft.client.MinecraftApplet", "com.mojang.minecraft.MinecraftApplet" };
	private static final String[] SERVER_ENTRYPOINTS = { "net.minecraft.server.Main", "net.minecraft.server.MinecraftServer", "com.mojang.minecraft.server.MinecraftServer" };

	private static final String BUNDLER_ENTRYPOINT = "net.minecraft.bundler.Main";
	private static final String BUNDLER_MAIN_CLASS_PROPERTY = "bundlerMainClass";

	private static final String REALMS_CHECK_PATH = "realmsVersion";
	private static final String LOG4J_API_CHECK_PATH = "org/apache/logging/log4j/LogManager.class";
	private static final String[] LOG4J_IMPL_CHECK_PATHS = { "META-INF/services/org.apache.logging.log4j.spi.Provider", "META-INF/log4j-provider.properties" };

	private static final String[] RESTRICTED_CLASS_PREFIXES = { "net.minecraft.", "com.mojang." };

	private EnvType envType;
	private String entrypoint;
	private Arguments arguments;
	private Path gameJar, realmsJar;
	private final Set<Path> log4jJars = new HashSet<>();
	private final List<Path> miscGameLibraries = new ArrayList<>(); // libraries not relevant for loader's uses
	private McVersion versionData;
	private boolean useGameJarForLogging;
	private boolean hasModLoader = false;

	private static final GameTransformer TRANSFORMER = new GameTransformer(
			new EntrypointPatch(),
			new BrandingPatch(),
			new EntrypointPatchFML125());

	@Override
	public String getGameId() {
		return "minecraft";
	}

	@Override
	public String getGameName() {
		return "Minecraft";
	}

	@Override
	public String getRawGameVersion() {
		return versionData.getRaw();
	}

	@Override
	public String getNormalizedGameVersion() {
		return versionData.getNormalized();
	}

	@Override
	public Collection<BuiltinMod> getBuiltinMods() {
		BuiltinModMetadata.Builder metadata = new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion())
				.setName(getGameName());

		if (versionData.getClassVersion().isPresent()) {
			int version = versionData.getClassVersion().getAsInt() - 44;

			try {
				metadata.addDependency(new ModDependencyImpl(ModDependency.Kind.DEPENDS, "java", Collections.singletonList(String.format(">=%d", version))));
			} catch (VersionParsingException e) {
				throw new RuntimeException(e);
			}
		}

		return Collections.singletonList(new BuiltinMod(gameJar, metadata.build()));
	}

	public Path getGameJar() {
		return gameJar;
	}

	@Override
	public String getEntrypoint() {
		return entrypoint;
	}

	@Override
	public Path getLaunchDirectory() {
		if (arguments == null) {
			return new File(".").toPath();
		}

		return getLaunchDirectory(arguments).toPath();
	}

	@Override
	public boolean isObfuscated() {
		return true; // generally yes...
	}

	@Override
	public boolean requiresUrlClassLoader() {
		return hasModLoader;
	}

	@Override
	public boolean isEnabled() {
		return System.getProperty(SystemProperties.SKIP_MC_PROVIDER) == null;
	}

	@Override
	public boolean locateGame(FabricLauncher launcher, String[] args, ClassLoader cl) {
		this.envType = launcher.getEnvironmentType();
		this.arguments = new Arguments();
		arguments.parse(args);

		List<String> entrypointClasses;

		if (envType == EnvType.CLIENT) {
			entrypointClasses = Arrays.asList(CLIENT_ENTRYPOINTS);
		} else {
			entrypointClasses = Arrays.asList(SERVER_ENTRYPOINTS);
		}

		Optional<GameProviderHelper.EntrypointResult> entrypointResult = GameProviderHelper.findFirstClass(cl, entrypointClasses);

		if (!entrypointResult.isPresent()) {
			// no entrypoint on the class path, try bundler
			if (!processBundlerJar(cl)) return false;
		} else {
			entrypoint = entrypointResult.get().entrypointName;
			gameJar = entrypointResult.get().entrypointPath;
			realmsJar = GameProviderHelper.getSource(cl, REALMS_CHECK_PATH).orElse(null);
			useGameJarForLogging = gameJar.equals(GameProviderHelper.getSource(cl, LOG4J_API_CHECK_PATH).orElse(null));
			hasModLoader = GameProviderHelper.getSource(cl, "ModLoader.class").isPresent();
		}

		if (!useGameJarForLogging && log4jJars.isEmpty()) { // use Log4J log handler directly if it is not shaded into the game jar, otherwise delay it to initialize() after deobfuscation
			setupLog4jLogHandler(launcher, false);
		}

		// expose obfuscated jar locations for mods to more easily remap code from obfuscated to intermediary
		ObjectShare share = FabricLoaderImpl.INSTANCE.getObjectShare();
		share.put("fabric-loader:inputGameJar", gameJar);
		if (realmsJar != null) share.put("fabric-loader:inputRealmsJar", realmsJar);

		String version = arguments.remove(Arguments.GAME_VERSION);
		if (version == null) version = System.getProperty(SystemProperties.GAME_VERSION);
		versionData = McVersionLookup.getVersion(gameJar, entrypointClasses, version);

		processArgumentMap(arguments, envType);

		return true;
	}

	private boolean processBundlerJar(ClassLoader cl) {
		if (envType != EnvType.SERVER) return false;

		// determine urls by running the bundler and extracting them from the context class loader

		URL[] urls;

		try {
			cl = new ClassLoader(cl) {
				@Override
				protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
					synchronized (getClassLoadingLock(name)) {
						Class<?> c = findLoadedClass(name);

						if (c == null) {
							if (name.startsWith("net.minecraft.")) {
								URL url = getResource(LoaderUtil.getClassFileName(name));

								if (url != null) {

									try (InputStream is = url.openConnection().getInputStream()) {
										byte[] data = new byte[Math.max(is.available() + 1, 1000)];
										int offset = 0;
										int len;

										while ((len = is.read(data, offset, data.length - offset)) >= 0) {
											offset += len;
											if (offset == data.length) data = Arrays.copyOf(data, data.length * 2);
										}

										c = defineClass(name, data, 0, offset);
									} catch (IOException e) {
										throw new RuntimeException(e);
									}
								}
							}

							if (c == null) {
								c = getParent().loadClass(name);
							}
						}

						if (resolve) {
							resolveClass(c);
						}

						return c;
					}
				}

				{
					registerAsParallelCapable();
				}
			};

			Class<?> cls = Class.forName(BUNDLER_ENTRYPOINT, true, cl);
			Method method = cls.getMethod("main", String[].class);

			// save + restore the system property and context class loader just in case

			String prevProperty = System.getProperty(BUNDLER_MAIN_CLASS_PROPERTY);
			System.setProperty(BUNDLER_MAIN_CLASS_PROPERTY, BundlerClassPathCapture.class.getName());

			ClassLoader prevCl = Thread.currentThread().getContextClassLoader();

			method.invoke(method, (Object) new String[0]);
			urls = BundlerClassPathCapture.FUTURE.get(10, TimeUnit.SECONDS);

			Thread.currentThread().setContextClassLoader(prevCl);

			if (prevProperty != null) {
				System.setProperty(BUNDLER_MAIN_CLASS_PROPERTY, prevProperty);
			} else {
				System.clearProperty(BUNDLER_MAIN_CLASS_PROPERTY);
			}
		} catch (ClassNotFoundException e) { // no bundler on the class path
			return false;
		} catch (Throwable t) {
			throw new RuntimeException("Error invoking MC server bundler: "+t, t);
		}

		// analyze urls to determine game/realms/log4j/misc libs and the entrypoint

		boolean hasGameJar = false;
		boolean hasRealmsJar = false;
		boolean hasLog4jApiJar = false;
		boolean hasLog4jImplJar = false;

		for (URL url : urls) {
			Path path;

			try {
				path = UrlUtil.asPath(url);
			} catch (URISyntaxException e) {
				throw new RuntimeException("invalid url: "+url);
			}

			if (hasGameJar && hasRealmsJar && hasLog4jApiJar && hasLog4jImplJar) {
				miscGameLibraries.add(path);
				continue;
			}

			useGameJarForLogging = false;
			boolean isMiscLibrary = true; // not game/realms/log4j

			try (ZipFile zf = new ZipFile(path.toFile())) {
				if (!hasGameJar) {
					for (String name : SERVER_ENTRYPOINTS) {
						if (zf.getEntry(LoaderUtil.getClassFileName(name)) != null) {
							entrypoint = name;
							gameJar = path;
							hasGameJar = true;
							isMiscLibrary = false;
							break;
						}
					}
				}

				if (!hasRealmsJar) {
					if (zf.getEntry(REALMS_CHECK_PATH) != null) {
						realmsJar = path;
						hasRealmsJar = true;
						isMiscLibrary = false;
					}
				}

				if (!hasLog4jApiJar) {
					if (zf.getEntry(LOG4J_API_CHECK_PATH) != null) {
						boolean isGameJar = path.equals(gameJar);
						useGameJarForLogging |= isGameJar;

						if (!isGameJar) {
							log4jJars.add(path);
						}

						hasLog4jApiJar = true;
						isMiscLibrary = false;
					}
				}

				if (!hasLog4jImplJar) {
					for (String name : LOG4J_IMPL_CHECK_PATHS) {
						if (zf.getEntry(name) != null) {
							boolean isGameJar = path.equals(gameJar);
							useGameJarForLogging |= isGameJar;

							if (!isGameJar) {
								log4jJars.add(path);
							}

							hasLog4jImplJar = true;
							isMiscLibrary = false;
							break;
						}
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(String.format("Error reading %s: %s", path.toAbsolutePath(), e), e);
			}

			if (isMiscLibrary) miscGameLibraries.add(path);
		}

		if (!hasGameJar) return false;
		if (!hasLog4jApiJar) throw new UnsupportedOperationException("MC server bundler didn't yield a jar containing the Log4J API");
		if (!hasLog4jImplJar) throw new UnsupportedOperationException("MC server bundler didn't yield a jar containing the Log4J implementaion");

		hasModLoader = false; // bundler + modloader don't normally coexist

		return true;
	}

	private static void processArgumentMap(Arguments argMap, EnvType envType) {
		switch (envType) {
		case CLIENT:
			if (!argMap.containsKey("accessToken")) {
				argMap.put("accessToken", "FabricMC");
			}

			if (!argMap.containsKey("version")) {
				argMap.put("version", "Fabric");
			}

			String versionType = "";

			if (argMap.containsKey("versionType") && !argMap.get("versionType").equalsIgnoreCase("release")) {
				versionType = argMap.get("versionType") + "/";
			}

			argMap.put("versionType", versionType + "Fabric");

			if (!argMap.containsKey("gameDir")) {
				argMap.put("gameDir", getLaunchDirectory(argMap).getAbsolutePath());
			}

			break;
		case SERVER:
			argMap.remove("version");
			argMap.remove("gameDir");
			argMap.remove("assetsDir");
			break;
		}
	}

	private static File getLaunchDirectory(Arguments argMap) {
		return new File(argMap.getOrDefault("gameDir", "."));
	}

	@Override
	public void initialize(FabricLauncher launcher) {
		List<Path> gameJars = new ArrayList<>(2);
		gameJars.add(gameJar);

		if (realmsJar != null) {
			gameJars.add(realmsJar);
		}

		if (isObfuscated()) {
			gameJars = GameProviderHelper.deobfuscate(gameJars,
					getGameId(), getNormalizedGameVersion(),
					getLaunchDirectory(),
					launcher);

			gameJar = gameJars.get(0);
			if (gameJars.size() > 1) realmsJar = gameJars.get(1);
		}

		if (useGameJarForLogging || !log4jJars.isEmpty()) {
			if (useGameJarForLogging) {
				launcher.addToClassPath(gameJar);
				launcher.setClassRestrictions(RESTRICTED_CLASS_PREFIXES);
			}

			if (!log4jJars.isEmpty()) {
				for (Path jar : log4jJars) {
					launcher.addToClassPath(jar);
				}
			}

			setupLog4jLogHandler(launcher, true);
		}

		TRANSFORMER.locateEntrypoints(launcher, gameJar);
	}

	private void setupLog4jLogHandler(FabricLauncher launcher, boolean useTargetCl) {
		try {
			final String logHandlerClsName = "net.fabricmc.loader.impl.game.minecraft.Log4jLogHandler";
			Class<?> logHandlerCls;

			if (useTargetCl) {
				logHandlerCls = launcher.loadIntoTarget(logHandlerClsName);
			} else {
				logHandlerCls = Class.forName(logHandlerClsName);
			}

			Log.init((LogHandler) logHandlerCls.getConstructor().newInstance(), true);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Arguments getArguments() {
		return arguments;
	}

	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		if (arguments == null) return new String[0];
		if (!sanitize) return arguments.toArray();

		List<String> list = new ArrayList<>(Arrays.asList(arguments.toArray()));
		int remove = 0;
		Iterator<String> iterator = list.iterator();

		while (iterator.hasNext()) {
			String next = iterator.next();

			if ("--accessToken".equals(next)) {
				remove = 2;
			}

			if (remove > 0) {
				iterator.remove();
				remove--;
			}
		}

		return list.toArray(new String[0]);
	}

	@Override
	public GameTransformer getEntrypointTransformer() {
		return TRANSFORMER;
	}

	@Override
	public boolean canOpenErrorGui() {
		if (arguments == null || envType == EnvType.CLIENT) {
			return true;
		}

		List<String> extras = arguments.getExtraArgs();
		return !extras.contains("nogui") && !extras.contains("--nogui");
	}

	@Override
	public boolean hasAwtSupport() {
		// MC always sets -XstartOnFirstThread for LWJGL
		return !LoaderUtil.hasMacOs();
	}

	@Override
	public void unlockClassPath(FabricLauncher launcher) {
		if (useGameJarForLogging) {
			launcher.setClassRestrictions(new String[0]);
		} else {
			launcher.addToClassPath(gameJar);
		}

		if (realmsJar != null) launcher.addToClassPath(realmsJar);

		for (Path lib : miscGameLibraries) {
			launcher.addToClassPath(lib);
		}
	}

	@Override
	public void launch(ClassLoader loader) {
		String targetClass = entrypoint;

		if (envType == EnvType.CLIENT && targetClass.contains("Applet")) {
			targetClass = "net.fabricmc.loader.impl.game.minecraft.applet.AppletMain";
		}

		try {
			Class<?> c = loader.loadClass(targetClass);
			Method m = c.getMethod("main", String[].class);
			m.invoke(null, (Object) arguments.toArray());
		} catch (InvocationTargetException e) {
			if (!onCrash(e.getCause(), "Minecraft has crashed!")) {
				throw new RuntimeException("Minecraft has crashed", e.getCause()); // Pass it on
			}
		} catch (ReflectiveOperationException e) {
			if (!onCrash(e, "Failed to start Minecraft!")) {
				throw new RuntimeException("Failed to start Minecraft", e);
			}
		}
	}

	@Override
	public boolean onCrash(Throwable exception, String context) {
		FabricGuiEntry.displayError(context, exception, false);
		return false; // Allow the crash to propagate
	}
}