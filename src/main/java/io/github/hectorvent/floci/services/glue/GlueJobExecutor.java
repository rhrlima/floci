package io.github.hectorvent.floci.services.glue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.glue.model.GlueJob;
import io.github.hectorvent.floci.services.glue.model.GlueJobRun;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Executes Glue pythonshell jobs inside a Docker container when real execution is enabled.
 * Reuses the same Docker utilities as Lambda (ContainerBuilder, ContainerLifecycleManager,
 * ContainerLogStreamer) but does NOT use the Lambda runtime API protocol — the Python
 * script just runs and exits.
 */
@ApplicationScoped
public class GlueJobExecutor {

    private static final Logger LOG = Logger.getLogger(GlueJobExecutor.class);
    private static final String TASK_DIR = "/var/task";
    private static final String OPT_PYTHON_DIR = "/opt/python";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final DockerHostResolver dockerHostResolver;
    private final ZipExtractor zipExtractor;
    private final S3Service s3Service;
    private final EmulatorConfig config;

    private final ExecutorService executor = new ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy());

    @Inject
    public GlueJobExecutor(ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager,
                           ContainerLogStreamer logStreamer,
                           DockerHostResolver dockerHostResolver,
                           ZipExtractor zipExtractor,
                           S3Service s3Service,
                           EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.dockerHostResolver = dockerHostResolver;
        this.zipExtractor = zipExtractor;
        this.s3Service = s3Service;
        this.config = config;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /**
     * Submits an asynchronous container execution for a pythonshell job.
     * The {@code onComplete} callback receives the final state ("SUCCEEDED" or "FAILED")
     * and an optional error message, and is responsible for persisting the result.
     */
    public void executeAsync(GlueJob job, GlueJobRun run,
                             BiConsumer<String, String> onComplete) {
        executor.submit(() -> {
            String finalState = "FAILED";
            String errorMessage = null;
            try {
                finalState = executeSync(job, run);
            } catch (Exception e) {
                LOG.errorv("Glue job execution failed [{0}/{1}]: {2}",
                        job.getName(), run.getId(), e.getMessage());
                errorMessage = e.getMessage();
            } finally {
                onComplete.accept(finalState, errorMessage);
            }
        });
    }

    private String executeSync(GlueJob job, GlueJobRun run) throws Exception {
        Path taskDir = Files.createTempDirectory("glue-task-");
        Path optPythonDir = Files.createTempDirectory("glue-opt-python-");
        String containerId = null;
        Closeable logHandle = null;

        try {
            // 1. Resolve and download the main script from S3
            String scriptLocation = job.getCommand().getScriptLocation();
            String mainScript = downloadScript(scriptLocation, taskDir);

            // 2. Process --extra-py-files from merged arguments
            Map<String, String> mergedArgs = mergeArguments(job.getDefaultArguments(), run.getArguments());
            String extraPyFiles = mergedArgs.get("--extra-py-files");
            boolean hasExtraPyFiles = false;
            if (extraPyFiles != null && !extraPyFiles.isBlank()) {
                downloadExtraPyFiles(extraPyFiles, optPythonDir);
                hasExtraPyFiles = true;
            }

            // 3. Choose image: config default or python-version-specific
            String image = resolveImage(job);

            // 4. Build environment variables
            String hostAddress = dockerHostResolver.resolve();
            int flociPort = URI.create(config.baseUrl()).getPort();
            String flociEndpoint = "http://" + hostAddress + ":" + flociPort;
            List<String> env = buildEnv(job, run, flociEndpoint, hasExtraPyFiles);

            // 5. Build container spec
            List<String> cmd = buildCmd(mainScript, mergedArgs);
            String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String containerName = "floci-glue-" + job.getName() + "-" + shortId;

            ContainerSpec spec = containerBuilder.newContainer(image)
                    .withName(containerName)
                    .withEnv(env)
                    .withEntrypoint(List.of("python"))
                    .withCmd(cmd)
                    .withDockerNetwork(
                            config.services().glue().execution().dockerNetwork()
                                    .or(() -> config.services().dockerNetwork())
                    )
                    .withLogRotation()
                    .build();

            // 6. Create container and inject code
            containerId = lifecycleManager.create(spec);
            DockerClient dockerClient = lifecycleManager.getDockerClient();
            copyDirToContainer(dockerClient, containerId, taskDir, TASK_DIR);
            if (hasExtraPyFiles) {
                copyDirToContainer(dockerClient, containerId, optPythonDir, OPT_PYTHON_DIR);
            }

            // 7. Start
            lifecycleManager.startCreated(containerId, spec);
            String logGroup = "/aws/glue/" + job.getName();
            String logStream = shortId;
            logHandle = logStreamer.attach(containerId, logGroup, logStream,
                    config.defaultRegion(), "glue:" + job.getName());

            // 8. Wait for exit
            int exitCode = dockerClient.waitContainerCmd(containerId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode();

            LOG.infov("Glue job [{0}/{1}] exited with code {2}", job.getName(), run.getId(), exitCode);
            return exitCode == 0 ? "SUCCEEDED" : "FAILED";

        } finally {
            // 9. Cleanup
            if (containerId != null) {
                lifecycleManager.stopAndRemove(containerId, logHandle);
            }
            deleteRecursive(taskDir);
            deleteRecursive(optPythonDir);
        }
    }

    /**
     * Downloads the script from S3, extracts it to the task dir, and returns the main script name.
     */
    private String downloadScript(String scriptLocation, Path taskDir) throws IOException {
        S3Uri uri = S3Uri.parse(scriptLocation);
        S3Object obj = s3Service.getObject(uri.bucket(), uri.key());
        byte[] data = obj.getData();

        if (uri.key().endsWith(".zip")) {
            zipExtractor.extractTo(data, taskDir);
            return "script.py";
        } else {
            String fileName = Path.of(uri.key()).getFileName().toString();
            Files.write(taskDir.resolve(fileName), data);
            return fileName;
        }
    }

    /**
     * Downloads each entry in --extra-py-files (comma-separated S3 URIs) to optPythonDir.
     */
    private void downloadExtraPyFiles(String extraPyFiles, Path optPythonDir) throws IOException {
        for (String raw : extraPyFiles.split(",")) {
            String s3Uri = raw.strip();
            if (s3Uri.isEmpty()) continue;
            S3Uri uri = S3Uri.parse(s3Uri);
            S3Object obj = s3Service.getObject(uri.bucket(), uri.key());
            byte[] data = obj.getData();
            String fileName = Path.of(uri.key()).getFileName().toString();
            if (fileName.endsWith(".zip")) {
                zipExtractor.extractTo(data, optPythonDir);
            } else {
                Files.write(optPythonDir.resolve(fileName), data);
            }
        }
    }

    private String resolveImage(GlueJob job) {
        String configured = config.services().glue().execution().image();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String pythonVersion = job.getCommand() != null ? job.getCommand().getPythonVersion() : null;
        if ("2".equals(pythonVersion)) {
            return "python:2.7";
        }
        return "python:3.11";
    }

    private List<String> buildEnv(GlueJob job, GlueJobRun run, String flociEndpoint,
                                  boolean hasPythonPath) {
        List<String> env = new ArrayList<>();
        env.add("AWS_DEFAULT_REGION=" + config.defaultRegion());
        env.add("AWS_REGION=" + config.defaultRegion());
        env.add("AWS_ACCESS_KEY_ID=test");
        env.add("AWS_SECRET_ACCESS_KEY=test");
        env.add("AWS_SESSION_TOKEN=test");
        env.add("AWS_ENDPOINT_URL=" + flociEndpoint);
        env.add("JOB_NAME=" + job.getName());
        env.add("JOB_RUN_ID=" + run.getId());
        if (hasPythonPath) {
            env.add("PYTHONPATH=" + OPT_PYTHON_DIR);
        }
        return env;
    }

    private List<String> buildCmd(String mainScript, Map<String, String> mergedArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(TASK_DIR + "/" + mainScript);
        cmd.add("--JOB_NAME");
        cmd.add("${JOB_NAME}");
        cmd.add("--JOB_RUN_ID");
        cmd.add("${JOB_RUN_ID}");
        if (mergedArgs != null) {
            mergedArgs.forEach((k, v) -> {
                if (!k.equals("--extra-py-files")) {
                    cmd.add(k);
                    cmd.add(v);
                }
            });
        }
        return cmd;
    }

    private Map<String, String> mergeArguments(Map<String, String> defaults, Map<String, String> overrides) {
        if (defaults == null && overrides == null) return Map.of();
        if (defaults == null) return overrides;
        if (overrides == null) return defaults;
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>(defaults);
        merged.putAll(overrides);
        return merged;
    }

    private void copyDirToContainer(DockerClient dockerClient, String containerId,
                                    Path sourceDir, String remotePath) {
        try (java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
             java.io.PipedInputStream pis = new java.io.PipedInputStream(pos)) {

            String label = sourceDir.getFileName().toString();
            new Thread(() -> {
                try (pos) {
                    createTarFromDir(sourceDir, pos);
                } catch (IOException e) {
                    LOG.errorv("Failed to stream tar for Glue dir {0}: {1}", label, e.getMessage());
                }
            }, "glue-tar-" + label).start();

            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath(remotePath)
                    .withTarInputStream(pis)
                    .exec();
        } catch (Exception e) {
            LOG.warnv("Failed to copy {0} into container {1}: {2}", sourceDir, containerId, e.getMessage());
        }
    }

    private static void createTarFromDir(Path sourceDir, OutputStream out) throws IOException {
        try (TarArchiveOutputStream tar = newTarStream(out);
             var stream = Files.walk(sourceDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(path)) continue;
                String entryName = sourceDir.relativize(path).toString();
                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                entry.setSize(Files.size(path));
                entry.setMode(0755);
                tar.putArchiveEntry(entry);
                try (var fis = Files.newInputStream(path)) {
                    fis.transferTo(tar);
                }
                tar.closeArchiveEntry();
            }
        }
    }

    private static TarArchiveOutputStream newTarStream(OutputStream out) {
        TarArchiveOutputStream tar = new TarArchiveOutputStream(out);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR);
        return tar;
    }

    private static void deleteRecursive(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    /** Minimal S3 URI parser for s3://bucket/key URIs. */
    private record S3Uri(String bucket, String key) {
        static S3Uri parse(String uri) {
            if (uri == null || !uri.startsWith("s3://")) {
                throw new IllegalArgumentException("Not a valid S3 URI: " + uri);
            }
            String stripped = uri.substring(5);
            int slash = stripped.indexOf('/');
            if (slash < 0) {
                return new S3Uri(stripped, "");
            }
            return new S3Uri(stripped.substring(0, slash), stripped.substring(slash + 1));
        }
    }
}
