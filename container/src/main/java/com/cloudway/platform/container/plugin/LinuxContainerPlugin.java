/**
 * Cloudway Platform
 * Copyright (c) 2012-2013 Cloudway Technology, Inc.
 * All rights reserved.
 */

package com.cloudway.platform.container.plugin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static java.util.stream.Collectors.*;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableTable;

import com.cloudway.platform.common.Config;
import com.cloudway.platform.common.util.Etc;
import com.cloudway.platform.common.util.Exec;
import com.cloudway.platform.common.util.IO;
import com.cloudway.platform.common.util.IOConsumer;
import com.cloudway.platform.common.util.BiIOConsumer;
import com.cloudway.platform.container.ApplicationContainer;
import com.cloudway.platform.container.ResourceLimits;
import jnr.constants.platform.Signal;

public class LinuxContainerPlugin extends UnixContainerPlugin
{
    public LinuxContainerPlugin(ApplicationContainer container) {
        super(container);
    }

    @Override
    public void create() throws IOException {
        try {
            createUser();
            createHomeDir();
            cgcreate();
            startTrafficControl();
            initQuota();
            initPamLimits();
        } catch (IOException ex) {
            // cleanup when container creation failed
            nothrow(
                this::deleteUser,
                this::cgdelete,
                this::stopTrafficControl,
                this::removePamLimits
            );
            throw ex;
        }
    }

    @Override
    public void destroy() {
        nothrow(
            this::killProcs,
            this::cgfreeze,
            this::killProcs,
            this::deleteUser,
            this::cgunfreeze,
            this::cgdelete,
            this::stopTrafficControl,
            this::removePamLimits
        );
    }

    @Override
    public void start() throws IOException {
        cgrestore();
    }

    // SELinux

    private static final String DEF_RUN_USER    = "unconfined_u";
    private static final String DEF_RUN_ROLE    = "system_r";
    private static final String DEF_RUN_TYPE    = "cloudway_t";

    private static final int DEF_MCS_SET_SIZE   = 1024;
    private static final int DEF_MCS_GROUP_SIZE = 2;
    private static final int DEF_MCS_UID_OFFSET = 0;
    private static final int DEF_MLS_NUM        = 0;

    private static final boolean selinux_enabled = SELinux.enabled();
    private String mcs_label;

    private String get_mcs_label() {
        if (selinux_enabled && mcs_label == null && container.getUID() != 0) {
            mcs_label = get_mcs_label_for(container.getUID());
        }
        return mcs_label;
    }

    private String get_target_context() {
        return String.join(":", DEF_RUN_USER, DEF_RUN_ROLE, DEF_RUN_TYPE, get_mcs_label());
    }

    /**
     * Determine the MCS label for the given UID.
     */
    private static String get_mcs_label_for(int uid) {
        Config config = Config.getDefault();

        int set_size   = config.getInt("SELINUX_MCS_SET_SIZE",   DEF_MCS_SET_SIZE);
        int group_size = config.getInt("SELINUX_MCS_GROUP_SIZE", DEF_MCS_GROUP_SIZE);
        int uid_offset = config.getInt("SELINUX_MCS_UID_OFFSET", DEF_MCS_UID_OFFSET);
        int mls_num    = config.getInt("SELINUX_MLS_NUM",        DEF_MLS_NUM);

        if (uid < uid_offset + group_size - 1) {
            throw new IllegalArgumentException("The UID must be greater than " +
                                               (uid_offset + group_size - 1));
        }

        if (uid < uid_offset + set_size * (set_size - 1) / 2) {
            if (group_size == 2) { // optimize
                // offset uid
                int ouid = uid - uid_offset;

                // Quadratic formula
                int a = 1;
                int b = 2 * set_size - 1; // This is actually negative b
                int c = 2 * ouid - 2;

                // Root of the equation
                int root = (int)((b - Math.sqrt(b * b - 4 * a * c)) / (2 * a));
                // remainder
                int remainder = (ouid - (2 * set_size - root - 1) * root / 2) + root;

                return "s" + mls_num + ":c" + root + ",c" + remainder;
            } else {
                // combination for large groups
                String mcs_label = combination(set_size, 0, group_size)
                    .skip(uid - (uid_offset + group_size - 1))
                    .findFirst()            // an optional IntStream
                    .get()                  // an IntStream
                    .mapToObj(i -> "c" + i) // joining the IntStream
                    .collect(joining(","));
                return "s" + mls_num + ":" + mcs_label;
            }
        } else {
            throw new IllegalArgumentException("The UID is too large for MCS set parameters");
        }
    }

    private static Stream<IntStream> combination(int n, int k, int m) {
        if (m == 0) {
            return Stream.of(IntStream.empty());
        } else {
            return IntStream.rangeClosed(k, n-m).boxed()
                .flatMap(i -> combination(n, i+1, m-1).map(c -> IntStream.concat(IntStream.of(i), c)));
        }
    }

    @Override
    public void setFileReadOnly(Path file) throws IOException {
        super.setFileReadOnly(file);
        set_mcs_label(file, get_mcs_label());
    }

    @Override
    public void setFileReadWrite(Path file) throws IOException {
        super.setFileReadWrite(file);
        set_mcs_label(file, get_mcs_label());
    }

    private static void set_mcs_label(Path path, String label)
        throws IOException
    {
        if (selinux_enabled) {
            SELinux.chcon(path, label, null, null, null);
        }
    }

    // Cgroups

    private static final String CG_KEY_PREFIX = "cgroup.";

    private static final ImmutableTable<String, String, Object> cgprofiles =
        Cgroup.enabled ? load_cgprofiles() : ImmutableTable.of();

    private static ImmutableTable<String, String, Object> load_cgprofiles() {
        ImmutableTable.Builder<String, String, Object> profiles = ImmutableTable.builder();
        ResourceLimits limits = ResourceLimits.getInstance();

        // make a uniform key set from all profiles
        Set<String> keys = limits.keys()
            .filter(k -> k.startsWith(CG_KEY_PREFIX))
            .collect(toSet());
        keys.add("cgroup.freezer.state"); // used to restore freezer state

        // load configuration for each cgroup profiles
        limits.profiles().forEach(profile -> keys.forEach(key -> {
            String ck = key.substring(CG_KEY_PREFIX.length());
            limits.getProperty(profile, key, () -> Optional.ofNullable(Cgroup.CG_PARAMETERS.get(ck)))
                  .ifPresent(v -> profiles.put(profile, ck, v));
        }));

        return profiles.build();
    }

    private boolean cgcall(IOConsumer<Cgroup> action)
        throws IOException
    {
        if (Cgroup.enabled) {
            action.accept(new Cgroup(container.getId(), container.getUID()));
            return true;
        } else {
            return false;
        }
    }

    private boolean cgcall(BiIOConsumer<Cgroup, Map<String,Object>> action)
        throws IOException
    {
        if (Cgroup.enabled) {
            Map<String, Object> cfg = cgprofiles.rowMap().get(container.getCapacity());
            if (cfg == null) {
                throw new IllegalArgumentException("Unknown cgroup profile: " + container.getCapacity());
            }

            Cgroup cg = new Cgroup(container.getId(), container.getUID());
            action.accept(cg, cfg);
            return true;
        } else {
            return false;
        }
    }

    private void cgcreate() throws IOException {
        cgcall(Cgroup::create);
        cgcall(this::fetch_limits);
    }

    private void cgdelete() throws IOException {
        cgcall(Cgroup::delete);
    }

    private void cgfreeze() throws IOException {
        cgcall(cg -> {
            cg.freeze();
            for (int i = 0; i < 20; i++) {
                int[] pids = cg.tasks();
                if (pids.length == 0) {
                    return;
                } else {
                    try {
                        Arrays.stream(pids).forEach(pid -> Etc.kill(pid, Signal.SIGKILL));
                        cg.thaw();
                        Thread.sleep(100);
                        cg.freeze();
                    } catch (Exception ex) {
                        // log and ignore
                    }
                }
            }
        });
    }

    private void cgunfreeze() throws IOException {
        cgcall(Cgroup::thaw);
    }

    private void cgrestore() throws IOException {
        cgcall(Cgroup::store);
        cgcall(this::fetch_limits);
    }

    private void fetch_limits(Cgroup cg) {
        try {
            cg.fetch("memory.limit_in_bytes").ifPresent(val -> {
                long mb = Long.parseLong((String)val) / (1024 * 1024);
                container.addEnvVar("MEMORY_LIMIT", String.valueOf(mb));
            });
        } catch (RuntimeException ex) {
            // ignored
        }
    }

    private void startTrafficControl() throws IOException {
        TrafficControl.startUser(container.getUID());
    }

    private void stopTrafficControl() throws IOException {
        TrafficControl.stopUser(container.getUID());
    }

    // Quota

    public static final int DEFAULT_QUOTA_BLOCKS = 1048576; // 1GB
    public static final int DEFAULT_QUOTA_FILES  = 80000;

    private void initQuota() throws IOException {
        ResourceLimits limits = ResourceLimits.getInstance();
        String category = container.getCapacity();
        set_quota(limits.getIntProperty(category, "quota.blocks", DEFAULT_QUOTA_BLOCKS),
                  limits.getIntProperty(category, "quota.files", DEFAULT_QUOTA_FILES));
    }

    private void set_quota(int maxblocks, int maxfiles)
        throws IOException
    {
        int curblocks = 0, curfiles = 0;

        Optional<String[]> quota = get_quota();
        if (quota.isPresent()) {
            String[] cur_quota = quota.get();
            curblocks = Integer.parseInt(cur_quota[1]);
            maxblocks = Integer.parseInt(cur_quota[3]);
            curfiles  = Integer.parseInt(cur_quota[5]);
            maxfiles  = Integer.parseInt(cur_quota[7]);
        }

        if (curblocks > maxblocks || curfiles > maxfiles) {
            // Log warning: current usage exceeds requested quota
        }

        Exec.args("setquota",
                  "-u", container.getId(),
                  0, maxblocks, 0, maxfiles,
                  "-a",
                  get_mountpoint(container.getHomeDir()))
            .silentIO()
            .checkError()
            .run();

    }

    private Optional<String[]> get_quota() throws IOException {
        String out = Exec.args("quota", "-pw", container.getId()).silentIO().subst();
        return Arrays.stream(out.split("\n"))
            .filter(line -> line.matches("^.*/dev/.*"))
            .findFirst()
            .map(line -> line.split("\\s+"));
    }

    private static Path get_mountpoint(Path path) {
        return Etc.do_posix(posix -> {
            Path oldpath = path.toAbsolutePath();
            long olddev = posix.lstat(oldpath.toString()).dev();

            while (true) {
                Path newpath = oldpath.getParent();
                if (newpath == null || newpath.equals(oldpath) ||
                    olddev != posix.lstat(newpath.toString()).dev()) {
                    break;
                }
                oldpath = newpath;
            }
            return oldpath;
        });
    }

    // PAM resource limits

    private static final String PAM_LIMITS_DIR = "/etc/security/limits.d";
    private static final int PAM_LIMITS_ORDER = ResourceLimits.getInstance().getGlobalProperty("limits.order", 85);

    private Path pam_limits_file() {
        return Paths.get(PAM_LIMITS_DIR, PAM_LIMITS_ORDER + "-" + container.getId() + ".conf");
    }

    private static final ImmutableList<String> PAM_LIMITS_VARS = ImmutableList.of(
        "core", "data", "fsize", "memlock", "nofile", "rss", "cpu", "nproc", "as",
        "maxlogins", "priority", "locks", "sigpending", "msgqueue", "nice", "rtprio"
    );
    private static final ImmutableList<String> PAM_SOFT_VARS = ImmutableList.of("nproc");

    private void initPamLimits() throws IOException {
        ResourceLimits cfg = ResourceLimits.getInstance();
        String id = container.getId();
        String profile = container.getCapacity();

        try (BufferedWriter out = Files.newBufferedWriter(pam_limits_file())) {
            out.write("# PAM process limits for guest " + id + "\n");
            IO.forEach(PAM_LIMITS_VARS, k ->
                cfg.getProperty(profile, "limits." + k).ifPresent(IOConsumer.wrap(v -> {
                    String limtype =
                        (PAM_SOFT_VARS.contains(k) && !"0".equals(v)) ? "soft" : "hard";
                    out.write(String.join("\t", id, limtype, k, v));
                    out.newLine();
                }))
            );
        }
    }

    private void removePamLimits() throws IOException {
        Files.deleteIfExists(pam_limits_file());
    }

    // Switch context

    // TODO: we can use a C program to perform the following process
    @Override
    public Exec join(Exec exec) throws IOException {
        if (Etc.getuid() != container.getUID()) {
            String cmd = exec.command().stream()
                .map(arg -> arg.isEmpty() ? "''" :
                            arg.indexOf(' ') != -1 ? "\\\"" + arg + "\\\"" :
                            arg)
                .collect(joining(" "));

            if (selinux_enabled) {
                String current_context = SELinux.getcon();
                String target_context = get_target_context();

                // Only switch contexts if necessary
                if (!current_context.equals(target_context)) {
                    cmd = "exec /usr/bin/runcon '" + target_context + "' /bin/sh -c \"" + cmd + "\"";
                }
            }

            exec.command("/sbin/runuser", "-s", "/bin/sh", container.getId(), "-c", cmd);
        }

        return exec;
    }
}
