package org.cryptomator.ui.model;

import org.apache.commons.lang3.SystemUtils;
import org.cryptomator.common.settings.VaultSettings;
import org.cryptomator.cryptofs.CryptoFileSystem;

import org.cryptomator.frontend.fuse.mount.EnvironmentVariables;
import org.cryptomator.frontend.fuse.mount.FuseMount;
import org.cryptomator.frontend.fuse.mount.FuseMountFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@VaultModule.PerVault
public class FuseVolume implements Volume {

	private static final Logger LOG = LoggerFactory.getLogger(FuseVolume.class);

	/**
	 * TODO: dont use fixed Strings and rather set them in some system environment variables in the cryptomator installer and load those!
	 */
	private static final String DEFAULT_MOUNTROOTPATH_MAC = System.getProperty("user.home") + "/Library/Application Support/Cryptomator";
	private static final String DEFAULT_MOUNTROOTPATH_LINUX = System.getProperty("user.home") + "/.Cryptomator";

	private final FuseMount fuseMnt;
	private final VaultSettings vaultSettings;
	private final WindowsDriveLetters windowsDriveLetters;

	private CryptoFileSystem cfs;
	private Path mountPath;
	private boolean extraDirCreated;

	@Inject
	public FuseVolume(VaultSettings vaultSettings, WindowsDriveLetters windowsDriveLetters) {
		this.vaultSettings = vaultSettings;
		this.windowsDriveLetters = windowsDriveLetters;
		this.fuseMnt = FuseMountFactory.createMountObject();
		this.extraDirCreated = false;
	}

	@Override
	public void prepare(CryptoFileSystem fs) throws IOException {
		this.cfs = fs;
		String mountPath;
		if (SystemUtils.IS_OS_WINDOWS) {
			//windows case
			if (vaultSettings.winDriveLetter().get() != null) {
				// specific drive letter selected
				mountPath = vaultSettings.winDriveLetter().get() + ":\\";
			} else {
				// auto assign drive letter
				mountPath = windowsDriveLetters.getAvailableDriveLetters().iterator().next() + ":\\";
			}
		} else if (vaultSettings.individualMountPath().get() != null) {
			//specific path given
			mountPath = vaultSettings.individualMountPath().get();
		} else {
			//choose default path & create extra directory
			mountPath = createDirIfNotExist(SystemUtils.IS_OS_MAC ? DEFAULT_MOUNTROOTPATH_MAC : DEFAULT_MOUNTROOTPATH_LINUX,
					vaultSettings.mountName().get());
			extraDirCreated = true;
		}
		this.mountPath = Paths.get(mountPath).toAbsolutePath();
	}

	private String createDirIfNotExist(String prefix, String dirName) throws IOException {
		Path p = Paths.get(prefix, dirName + vaultSettings.getId());
		if (Files.isDirectory(p) && !Files.newDirectoryStream(p).iterator().hasNext()) {
			throw new DirectoryNotEmptyException("Mount point is not empty.");
		} else {
			Files.createDirectory(p);
			return p.toString();
		}
	}

	@Override
	public void mount() throws CommandFailedException {
		try {
			EnvironmentVariables envVars = EnvironmentVariables.create()
					.withMountName(vaultSettings.mountName().getValue())
					.withMountPath(mountPath.toString())
					.build();
			fuseMnt.mount(cfs.getPath("/"), envVars);
		} catch (Exception e) {
			throw new CommandFailedException("Unable to mount Filesystem", e);
		}
	}

	@Override
	public void reveal() throws CommandFailedException {
		try {
			fuseMnt.reveal();
		} catch (org.cryptomator.frontend.fuse.mount.CommandFailedException e) {
			LOG.info("Revealing the vault in file manger failed: " + e.getMessage());
			throw new CommandFailedException(e);
		}
	}

	@Override
	public synchronized void unmount() throws CommandFailedException {
		if (cfs.getStats().pollBytesRead() == 0 && cfs.getStats().pollBytesWritten() == 0) {
			unmountRaw();
		} else {
			throw new CommandFailedException("Pending read or write operations.");
		}
	}

	@Override
	public synchronized void unmountForced() throws CommandFailedException {
		this.unmountRaw();
	}

	private synchronized void unmountRaw() throws CommandFailedException {
		try {
			fuseMnt.unmount();
		} catch (org.cryptomator.frontend.fuse.mount.CommandFailedException e) {
			throw new CommandFailedException(e);
		}
	}

	@Override
	public void stop() {
		if (extraDirCreated) {
			try {
				Files.delete(mountPath);
			} catch (IOException e) {
				LOG.warn("Could not delete mounting directory:" + e.getMessage());
			}
		}
	}

	@Override
	public String getMountUri() {
		return Paths.get(fuseMnt.getMountPath()).toUri().toString();
	}

	/**
	 * TODO: change this to a real implementation
	 *
	 * @return
	 */
	@Override
	public boolean isSupported() {
		return true;
	}

	@Override
	public boolean supportsForcedUnmount() {
		return true;
	}

}
