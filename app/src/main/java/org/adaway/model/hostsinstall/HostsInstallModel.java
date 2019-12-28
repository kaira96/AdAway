package org.adaway.model.hostsinstall;

import android.content.Context;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.io.SuFile;

import org.adaway.R;
import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostListItemDao;
import org.adaway.db.dao.HostsSourceDao;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.adaway.helper.PreferenceHelper;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.model.error.HostErrorException;
import org.adaway.model.adblocking.AdBlockModel;
import org.adaway.util.AppExecutors;
import org.adaway.util.Log;
import org.adaway.util.RegexUtils;
import org.adaway.util.ShellUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.content.Context.MODE_PRIVATE;
import static org.adaway.model.adblocking.AdBlockMethod.ROOT;
import static org.adaway.model.error.HostError.APPLY_FAIL;
import static org.adaway.model.error.HostError.COPY_FAIL;
import static org.adaway.model.error.HostError.NOT_ENOUGH_SPACE;
import static org.adaway.model.error.HostError.PRIVATE_FILE_FAIL;
import static org.adaway.model.error.HostError.REVERT_FAIL;
import static org.adaway.model.error.HostError.SYMLINK_MISSING;
import static org.adaway.util.Constants.ANDROID_SYSTEM_ETC_HOSTS;
import static org.adaway.util.Constants.COMMAND_CHCON_SYSTEMFILE;
import static org.adaway.util.Constants.COMMAND_CHMOD_644;
import static org.adaway.util.Constants.COMMAND_CHOWN;
import static org.adaway.util.Constants.COMMAND_LN;
import static org.adaway.util.Constants.COMMAND_RM;
import static org.adaway.util.Constants.DEFAULT_HOSTS_FILENAME;
import static org.adaway.util.Constants.HOSTS_FILENAME;
import static org.adaway.util.Constants.LINE_SEPARATOR;
import static org.adaway.util.Constants.LOCALHOST_HOSTNAME;
import static org.adaway.util.Constants.LOCALHOST_IPv4;
import static org.adaway.util.Constants.LOCALHOST_IPv6;
import static org.adaway.util.Constants.TAG;
import static org.adaway.util.MountType.READ_ONLY;
import static org.adaway.util.MountType.READ_WRITE;
import static org.adaway.util.ShellUtils.mergeAllLines;

/**
 * This class is the model to represent hosts file installation.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class HostsInstallModel extends AdBlockModel {
    private static final String HEADER1 = "# This hosts file has been generated by AdAway on:";
    private static final String HEADER2 = "# Please do not modify it directly, it will be overwritten when AdAway is applied again.";
    private static final String HEADER_SOURCES = "# This file is generated from the following sources:";
    /**
     * The {@link HostsSource} DAO.
     */
    private final HostsSourceDao hostsSourceDao;
    /**
     * The {@link HostListItem} DAO.
     */
    private final HostListItemDao hostListItemDao;

    /**
     * Constructor.
     *
     * @param context The application context.
     */
    public HostsInstallModel(Context context) {
        super(context);
        // Get DOA
        AppDatabase database = AppDatabase.getInstance(this.context);
        this.hostsSourceDao = database.hostsSourceDao();
        this.hostListItemDao = database.hostsListItemDao();
        // Check if host list is applied
        AppExecutors.getInstance().diskIO().execute(this::checkApplied);
    }

    @Override
    public AdBlockMethod getMethod() {
        return ROOT;
    }

    @Override
    public void apply() throws HostErrorException {
        setStateAndDetails(R.string.apply_dialog, R.string.apply_dialog_hosts);
        if (!checkHostsFileSymlink()) {
            throw new HostErrorException(SYMLINK_MISSING);
        }
        createNewHostsFile();
        copyNewHostsFile();
        setStateAndDetails(R.string.apply_dialog, R.string.apply_dialog_apply);
        if (!checkInstalledHostsFile()) {
            throw new HostErrorException(APPLY_FAIL);
        }
        markHostsSourcesAsInstalled();
        setStateAndDetails(R.string.status_enabled, R.string.status_enabled_subtitle);
    }

    /**
     * Revert to the default hosts file.
     *
     * @throws HostErrorException If the hosts file could not be reverted.
     */
    @Override
    public void revert() throws HostErrorException {
        // Update status
        setStateAndDetails(R.string.status_reverting, R.string.status_reverting_subtitle);
        try {
            // Revert hosts file
            revertHostFile();
            markHostsSourcesAsUninstalled();
            setStateAndDetails(R.string.status_disabled, R.string.status_disabled_subtitle);
        } catch (IOException exception) {
            setStateAndDetails(R.string.status_enabled, R.string.revert_problem);
            throw new HostErrorException(REVERT_FAIL, exception);
        }
    }

    /**
     * Create symlink from system hosts file to target hosts file.
     *
     * @throws HostErrorException If the symlink could not be created.
     */
    public void createSymlink() throws HostErrorException {
        HostsInstallLocation installLocation = PreferenceHelper.getInstallLocation(this.context);
        boolean success = createSymlink(installLocation.getTarget(this.context));
        if (!success) {
            throw new HostErrorException(SYMLINK_MISSING);
        }
    }

    private void checkApplied() {
        HostsInstallLocation installLocation = PreferenceHelper.getInstallLocation(this.context);
        String target = installLocation.getTarget(this.context);

        boolean applied;

        /* Check if first line in hosts file is AdAway comment */
        SuFile file = new SuFile(target);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            String firstLine = reader.readLine();

            Log.d(TAG, "First line of " + target + ": " + firstLine);

            applied = firstLine.equals(HEADER1);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException", e);
            applied = true; // workaround for: http://code.google.com/p/ad-away/issues/detail?id=137
        } catch (Exception e) {
            Log.e(TAG, "Exception: ", e);
            applied = false;
        }

        this.applied.postValue(applied);
    }

    private void deleteNewHostsFile() {
        // delete generated hosts file from private storage
        this.context.deleteFile(HOSTS_FILENAME);
    }

    /**
     * Check if the hosts file was well installed.
     *
     * @return {@code true} if the hosts file was well installed, {@code false} otherwise.
     */
    private boolean checkInstalledHostsFile() {
        HostsInstallLocation installLocation = PreferenceHelper.getInstallLocation(this.context);
        return isSymlinkCorrect(installLocation.getTarget(this.context));
    }

    /**
     * Check if the hosts file target symlink is needed and installed.
     *
     * @return {@code true} if the hosts file target is the system one or symlink to target is installed, {@code false} otherwise.
     */
    private boolean checkHostsFileSymlink() {
        HostsInstallLocation installLocation = PreferenceHelper.getInstallLocation(this.context);
        return !installLocation.requireSymlink(this.context);
    }

    private void copyNewHostsFile() throws HostErrorException {
        try {
            copyHostsFile(HOSTS_FILENAME);
        } catch (CommandException exception) {
            throw new HostErrorException(COPY_FAIL, exception);
        }
    }

    /**
     * Create a new hosts files in a private file from downloaded hosts sources.
     *
     * @throws HostErrorException If the new hosts file could not be created.
     */
    private void createNewHostsFile() throws HostErrorException {
        deleteNewHostsFile();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(this.context.openFileOutput(HOSTS_FILENAME, MODE_PRIVATE)))) {
            writeHostsHeader(writer);
            writeLoopbackToHosts(writer);
            writeHosts(writer);
        } catch (IOException exception) {
            throw new HostErrorException(PRIVATE_FILE_FAIL, exception);
        }
    }

    private void writeHostsHeader(BufferedWriter writer) throws IOException {
        // Format current date
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        Date now = new Date();
        String date = formatter.format(now);
        // Write header
        writer.write(HEADER1);
        writer.write(date);
        writer.newLine();
        writer.write(HEADER2);
        writer.newLine();
        // Write hosts source
        writer.write(HEADER_SOURCES);
        writer.newLine();
        for (HostsSource hostsSource : this.hostsSourceDao.getEnabled()) {
            writer.write("# - " + hostsSource.getUrl() + LINE_SEPARATOR);
            writer.newLine();
        }
        // Write empty line separator
        writer.newLine();
    }

    private void writeLoopbackToHosts(BufferedWriter writer) throws IOException {
        writer.write(LOCALHOST_IPv4 + " " + LOCALHOST_HOSTNAME);
        writer.newLine();
        writer.write(LOCALHOST_IPv6 + " " + LOCALHOST_HOSTNAME);
        writer.newLine();
    }

    private void writeHosts(BufferedWriter writer) throws IOException {
        // Get user preferences
        String redirectionIpv4 = PreferenceHelper.getRedirectionIpv4(this.context);
        String redirectionIpv6 = PreferenceHelper.getRedirectionIpv6(this.context);
        boolean enableIpv6 = PreferenceHelper.getEnableIpv6(this.context);
        // Write blocked hosts
        List<String> blockedHosts = computeBlockedHosts();
        for (String hostname : blockedHosts) {
            writer.write(redirectionIpv4 + " " + hostname);
            writer.newLine();
            if (enableIpv6) {
                writer.write(redirectionIpv6 + " " + hostname);
                writer.newLine();
            }
        }
        // Write redirected hosts
        List<HostListItem> redirectedHosts = this.hostListItemDao.getEnabledRedirectList();
        for (HostListItem redirectedHost : redirectedHosts) {
            writer.write(redirectedHost.getHost() + " " + redirectedHost.getRedirection());
            writer.newLine();
        }
    }

    private List<String> computeBlockedHosts() {
        Predicate<String> allowed = getAllowedHostsFilter();
        List<String> blockedHosts = this.hostListItemDao.getEnabledBlackListHosts();
        return blockedHosts.stream()
                .parallel()
                .filter(allowed)
                .collect(java.util.stream.Collectors.toList());
    }

    private Predicate<String> getAllowedHostsFilter() {
        // Get allowed hosts
        List<String> allowedHosts = this.hostListItemDao.getEnabledWhiteListHosts();
        // Compute allowed patterns
        List<Pattern> allowedPatterns = allowedHosts.stream()
                .map(RegexUtils::wildcardToRegex)
                .map(Pattern::compile)
                .collect(java.util.stream.Collectors.toList());
        // Build allowed filters
        return host -> allowedPatterns.stream()
                .parallel()
                .map(pattern -> pattern.matcher(host))
                .noneMatch(Matcher::find);
    }
    /**
     * Revert to default hosts file.
     *
     * @throws IOException If the hosts file could not be reverted.
     */
    private void revertHostFile() throws IOException {
        // Create private file
        try (FileOutputStream fos = this.context.openFileOutput(DEFAULT_HOSTS_FILENAME, MODE_PRIVATE)) {
            // Write default localhost as hosts file
            String localhost = LOCALHOST_IPv4 + " " + LOCALHOST_HOSTNAME + LINE_SEPARATOR +
                    LOCALHOST_IPv6 + " " + LOCALHOST_HOSTNAME + LINE_SEPARATOR;
            fos.write(localhost.getBytes());
            // Copy generated hosts file to target location
            copyHostsFile(DEFAULT_HOSTS_FILENAME);
            // Delete generated hosts file after applying it
            this.context.deleteFile(DEFAULT_HOSTS_FILENAME);
        } catch (Exception exception) {
            throw new IOException("Unable to revert hosts file.", exception);
        }
    }

    /**
     * Set local modifications date to now for all enabled hosts sources.
     */
    private void markHostsSourcesAsInstalled() {
        // Get application context and database
        HostsSourceDao hostsSourceDao = AppDatabase.getInstance(this.context).hostsSourceDao();
        Date now = new Date();
        hostsSourceDao.updateEnabledLocalModificationDates(now);
    }

    /**
     * Clear local modification dates for all hosts sources.
     */
    private void markHostsSourcesAsUninstalled() {
        // Get application context and database
        HostsSourceDao hostsSourceDao = AppDatabase.getInstance(this.context).hostsSourceDao();
        hostsSourceDao.clearLocalModificationDates();
    }


    /**
     * Copy source file from private storage of AdAway to hosts file target using root commands.
     */
    private void copyHostsFile(String source) throws HostErrorException, CommandException {

        HostsInstallLocation installLocation = PreferenceHelper.getInstallLocation(this.context);
        String target = installLocation.getTarget(this.context);

        Log.i(TAG, "Copy hosts file with target: " + target);
        String privateDir = this.context.getFilesDir().getAbsolutePath();
        String privateFile = privateDir + File.separator + source;

        // if the target has a trailing slash, it is not a valid target!
        if (target.endsWith("/")) {
            throw new IllegalArgumentException("Custom target ends with trailing slash, it is not a valid target!");
        }

        SuFile targetFile = new SuFile(target);
        if (!target.equals(ANDROID_SYSTEM_ETC_HOSTS)) {
            /*
             * If custom target like /data/etc/hosts is set, create missing directories for writing
             * this file
             */
            SuFile parentFile = targetFile.getParentFile();
            if (!parentFile.isDirectory() && !parentFile.mkdirs()) {
                throw new CommandException("Failed to create directories: " + parentFile);
            }
        }

        /* check for space on partition */
        long size = new File(privateFile).length();
        Log.i(TAG, "Size of hosts file: " + size);
        if (!hasEnoughSpaceOnPartition(target, size)) {
            throw new HostErrorException(NOT_ENOUGH_SPACE);
        }

        /* Execute commands */
        boolean writable = isWritable(target);
        try {
            if (!writable) {
                // remount for write access
                Log.i(TAG, "Remounting for RW...");
                if (!ShellUtils.remountPartition(targetFile, READ_WRITE)) {
                    throw new CommandException("Failed to remount hosts file partition as read-write.");
                }
            }

            if (target.equals(ANDROID_SYSTEM_ETC_HOSTS)) {
                // remove before copying when using /system/etc/hosts
                targetFile.delete();
            }
            // Copy hosts file then set owner and permissions
            Shell.Result result = Shell.su(
                    "dd if=" + privateFile + " of=" + target,
                    COMMAND_CHOWN + " " + target,
                    COMMAND_CHMOD_644 + " " + target
            ).exec();
            if (!result.isSuccess()) {
                throw new CommandException("Failed to copy hosts file: " + mergeAllLines(result.getErr()));
            }
        } finally {
            if (!writable) {
                // after all remount target back as read only
                ShellUtils.remountPartition(targetFile, READ_ONLY);
            }

        }
    }

    /**
     * Create symlink from /system/etc/hosts to target.
     *
     * @param target The target of the symbolic link.
     */
    private boolean createSymlink(String target) {
        // Mount hosts file partition as read/write
        SuFile hostsFile = new SuFile(ANDROID_SYSTEM_ETC_HOSTS);
        if (!ShellUtils.remountPartition(hostsFile, READ_WRITE)) {
            return false;
        }
        Shell.Result result = Shell.su(
                COMMAND_RM + " " + ANDROID_SYSTEM_ETC_HOSTS,
                COMMAND_LN + " " + target + " " + ANDROID_SYSTEM_ETC_HOSTS,
                COMMAND_CHCON_SYSTEMFILE + " " + target,
                COMMAND_CHOWN + " " + target,
                COMMAND_CHMOD_644 + " " + target
        ).exec();
        boolean success = result.isSuccess();
        if (!success) {
            Log.e(TAG, "Failed to create symbolic link: " + mergeAllLines(result.getErr()));
        }
        // Mount hosts file partition as read only
        ShellUtils.remountPartition(hostsFile, READ_ONLY);
        return success;
    }

    /**
     * Checks whether /system/etc/hosts is a symlink and pointing to the target or not
     */
    private boolean isSymlinkCorrect(String target) {
        Log.i(TAG, "Checking whether /system/etc/hosts is a symlink and pointing to " + target + " or not.");

        Shell.Result exec = Shell.su("readlink -e " + target).exec();
        if (!exec.isSuccess()) {
            return false;
        }
        List<String> out = exec.getOut();
        if (out.isEmpty()) {
            return false;
        }
        String read = out.get(0);
        Log.d(TAG, "symlink: " + read + "; target: " + target);
        return read.equals(target);
    }

    /**
     * Check if there is enough space on partition where target is located
     *
     * @param size   size of file to put on partition
     * @param target path where to put the file
     * @return true if it will fit on partition of target, false if it will not fit.
     */
    private boolean hasEnoughSpaceOnPartition(String target, long size) {
        long freeSpace = new SuFile(target).getFreeSpace();
        return (freeSpace == 0 || freeSpace > size);
    }

    /**
     * Check if a path is writable.
     *
     * @param path The path to check.
     * @return <code>true</code> if the path is writable, <code>false</code> otherwise.
     */
    private boolean isWritable(String path) {
        return new SuFile(path).canWrite();
    }
}
