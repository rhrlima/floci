package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.GlueJob;
import io.github.hectorvent.floci.services.glue.model.GlueJobRun;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.SchemaReference;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.glue.schemaregistry.SchemaToColumnsConverter;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class GlueService {

    private static final Logger LOG = Logger.getLogger(GlueService.class);

    private final StorageBackend<String, Database> databaseStore;
    private final StorageBackend<String, Table> tableStore;
    private final StorageBackend<String, Partition> partitionStore;
    private final StorageBackend<String, GlueJob> jobStore;
    private final StorageBackend<String, GlueJobRun> jobRunStore;
    private final GlueSchemaRegistryService schemaRegistryService;
    private final RegionResolver regionResolver;
    private final GlueJobExecutor glueJobExecutor;
    private final EmulatorConfig emulatorConfig;

    @Inject
    public GlueService(StorageFactory storageFactory,
                       GlueSchemaRegistryService schemaRegistryService,
                       RegionResolver regionResolver,
                       GlueJobExecutor glueJobExecutor,
                       EmulatorConfig emulatorConfig) {
        this.databaseStore = storageFactory.create("glue", "databases.json", new TypeReference<Map<String, Database>>() {});
        this.tableStore = storageFactory.create("glue", "tables.json", new TypeReference<Map<String, Table>>() {});
        this.partitionStore = storageFactory.create("glue", "partitions.json", new TypeReference<Map<String, Partition>>() {});
        this.jobStore = storageFactory.create("glue", "jobs.json", new TypeReference<Map<String, GlueJob>>() {});
        this.jobRunStore = storageFactory.create("glue", "job-runs.json", new TypeReference<Map<String, GlueJobRun>>() {});
        this.schemaRegistryService = schemaRegistryService;
        this.regionResolver = regionResolver;
        this.glueJobExecutor = glueJobExecutor;
        this.emulatorConfig = emulatorConfig;
    }

    GlueService(StorageBackend<String, Database> databaseStore,
                StorageBackend<String, Table> tableStore,
                StorageBackend<String, Partition> partitionStore,
                StorageBackend<String, GlueJob> jobStore,
                StorageBackend<String, GlueJobRun> jobRunStore,
                GlueSchemaRegistryService schemaRegistryService,
                RegionResolver regionResolver) {
        this.databaseStore = databaseStore;
        this.tableStore = tableStore;
        this.partitionStore = partitionStore;
        this.jobStore = jobStore;
        this.jobRunStore = jobRunStore;
        this.schemaRegistryService = schemaRegistryService;
        this.regionResolver = regionResolver;
        this.glueJobExecutor = null;
        this.emulatorConfig = null;
    }

    public void createDatabase(Database database) {
        if (databaseStore.get(database.getName()).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Database already exists: " + database.getName(), 400);
        }
        databaseStore.put(database.getName(), database);
        LOG.infov("Created Glue Database: {0}", database.getName());
    }

    public Database getDatabase(String name) {
        return databaseStore.get(name)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Database not found: " + name, 400));
    }

    public List<Database> getDatabases() {
        return databaseStore.scan(k -> true);
    }

    public void createTable(String databaseName, Table table) {
        getDatabase(databaseName);
        String key = databaseName + ":" + table.getName();
        if (tableStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Table already exists: " + table.getName(), 400);
        }
        validateSchemaReference(table);
        table.setDatabaseName(databaseName);
        tableStore.put(key, table);
        LOG.infov("Created Glue Table: {0}.{1}", databaseName, table.getName());
    }

    public Table getTable(String databaseName, String tableName) {
        String key = databaseName + ":" + tableName;
        Table table = tableStore.get(key)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Table not found: " + databaseName + "." + tableName, 400));
        return withResolvedSchemaReference(table);
    }

    public List<Table> getTables(String databaseName) {
        List<Table> tables = tableStore.scan(k -> k.startsWith(databaseName + ":"));
        List<Table> resolved = new ArrayList<>(tables.size());
        for (Table table : tables) {
            resolved.add(withResolvedSchemaReference(table));
        }
        return resolved;
    }

    public void deleteTable(String databaseName, String tableName) {
        String key = databaseName + ":" + tableName;
        tableStore.delete(key);
        partitionStore.scan(k -> k.startsWith(key + ":")).forEach(p -> {
            partitionStore.delete(databaseName + ":" + tableName + ":" + String.join(",", p.getValues()));
        });
        LOG.infov("Deleted Glue Table: {0}.{1}", databaseName, tableName);
    }

    public void createPartition(String databaseName, String tableName, Partition partition) {
        getTable(databaseName, tableName);
        String key = databaseName + ":" + tableName + ":" + String.join(",", partition.getValues());
        partition.setDatabaseName(databaseName);
        partition.setTableName(tableName);
        partitionStore.put(key, partition);
    }

    public List<Partition> getPartitions(String databaseName, String tableName) {
        String prefix = databaseName + ":" + tableName + ":";
        return partitionStore.scan(k -> k.startsWith(prefix));
    }

    // ── Jobs ─────────────────────────────────────────────────────────────────

    public GlueJob createJob(GlueJob job) {
        if (jobStore.get(job.getName()).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Job already exists: " + job.getName(), 400);
        }
        Instant now = Instant.now();
        job.setCreatedOn(now);
        job.setLastModifiedOn(now);
        jobStore.put(job.getName(), job);
        LOG.infov("Created Glue Job: {0}", job.getName());
        return job;
    }

    public GlueJob getJob(String jobName) {
        return jobStore.get(jobName)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Job not found: " + jobName, 400));
    }

    public List<GlueJob> getJobs() {
        return jobStore.scan(k -> true);
    }

    public GlueJob updateJob(String jobName, GlueJob update) {
        GlueJob existing = getJob(jobName);
        if (update.getRole() != null) existing.setRole(update.getRole());
        if (update.getCommand() != null) existing.setCommand(update.getCommand());
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getDefaultArguments() != null) existing.setDefaultArguments(update.getDefaultArguments());
        if (update.getNonOverridableArguments() != null) existing.setNonOverridableArguments(update.getNonOverridableArguments());
        if (update.getExecutionProperty() != null) existing.setExecutionProperty(update.getExecutionProperty());
        if (update.getTimeout() != null) existing.setTimeout(update.getTimeout());
        if (update.getMaxCapacity() != null) existing.setMaxCapacity(update.getMaxCapacity());
        if (update.getWorkerType() != null) existing.setWorkerType(update.getWorkerType());
        if (update.getNumberOfWorkers() != null) existing.setNumberOfWorkers(update.getNumberOfWorkers());
        if (update.getGlueVersion() != null) existing.setGlueVersion(update.getGlueVersion());
        if (update.getMaxRetries() != 0) existing.setMaxRetries(update.getMaxRetries());
        if (update.getSecurityConfiguration() != null) existing.setSecurityConfiguration(update.getSecurityConfiguration());
        if (update.getLogUri() != null) existing.setLogUri(update.getLogUri());
        if (update.getExecutionClass() != null) existing.setExecutionClass(update.getExecutionClass());
        existing.setLastModifiedOn(Instant.now());
        jobStore.put(jobName, existing);
        LOG.infov("Updated Glue Job: {0}", jobName);
        return existing;
    }

    public void deleteJob(String jobName) {
        jobStore.delete(jobName);
        LOG.infov("Deleted Glue Job: {0}", jobName);
    }

    public List<String> listJobNames() {
        return jobStore.scan(k -> true).stream()
                .map(GlueJob::getName)
                .collect(Collectors.toList());
    }

    public List<GlueJob> batchGetJobs(List<String> jobNames) {
        return jobNames.stream()
                .map(name -> jobStore.get(name).orElse(null))
                .filter(job -> job != null)
                .collect(Collectors.toList());
    }

    public List<String> batchGetJobsNotFound(List<String> jobNames) {
        return jobNames.stream()
                .filter(name -> jobStore.get(name).isEmpty())
                .collect(Collectors.toList());
    }

    // ── Job Runs ──────────────────────────────────────────────────────────────

    public GlueJobRun startJobRun(String jobName, Map<String, String> arguments) {
        GlueJob job = getJob(jobName);
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        GlueJobRun run = new GlueJobRun();
        run.setId(runId);
        run.setJobName(jobName);
        run.setArguments(arguments);
        run.setStartedOn(now);
        run.setGlueVersion(job.getGlueVersion());
        run.setWorkerType(job.getWorkerType());
        run.setNumberOfWorkers(job.getNumberOfWorkers());
        run.setMaxCapacity(job.getMaxCapacity());

        boolean realExecution = emulatorConfig != null
                && emulatorConfig.services().glue().execution().enabled()
                && "pythonshell".equals(job.getCommand() != null ? job.getCommand().getName() : null)
                && glueJobExecutor != null;

        if (realExecution) {
            run.setJobRunState("RUNNING");
            jobRunStore.put(jobName + ":" + runId, run);
            LOG.infov("Starting real execution of Glue Job Run: {0} for job {1}", runId, jobName);
            glueJobExecutor.executeAsync(job, run, (state, errorMsg) -> {
                GlueJobRun stored = jobRunStore.get(jobName + ":" + runId).orElse(run);
                stored.setJobRunState(state);
                stored.setCompletedOn(Instant.now());
                if (errorMsg != null) {
                    stored.setErrorMessage(errorMsg);
                }
                jobRunStore.put(jobName + ":" + runId, stored);
            });
        } else {
            run.setJobRunState("SUCCEEDED");
            run.setCompletedOn(now);
            run.setExecutionTime(0);
            jobRunStore.put(jobName + ":" + runId, run);
            LOG.infov("Simulated Glue Job Run: {0} for job {1}", runId, jobName);
        }

        return run;
    }

    public GlueJobRun getJobRun(String jobName, String runId) {
        return jobRunStore.get(jobName + ":" + runId)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Job run not found: " + runId + " for job " + jobName, 400));
    }

    public List<GlueJobRun> getJobRuns(String jobName) {
        getJob(jobName);
        return jobRunStore.scan(k -> k.startsWith(jobName + ":"));
    }

    public List<String> batchStopJobRun(String jobName, List<String> runIds) {
        List<String> stopped = new ArrayList<>();
        for (String runId : runIds) {
            String key = jobName + ":" + runId;
            jobRunStore.get(key).ifPresent(run -> {
                if ("RUNNING".equals(run.getJobRunState())) {
                    run.setJobRunState("STOPPED");
                    run.setCompletedOn(Instant.now());
                    jobRunStore.put(key, run);
                    stopped.add(runId);
                }
            });
        }
        return stopped;
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    private void validateSchemaReference(Table table) {
        SchemaReference ref = schemaReferenceOf(table);
        if (ref == null) {
            return;
        }
        // Throws EntityNotFoundException / InvalidInputException if reference is broken.
        resolveSchemaVersion(ref);
    }

    private Table withResolvedSchemaReference(Table table) {
        SchemaReference ref = schemaReferenceOf(table);
        if (ref == null) {
            return table;
        }
        try {
            SchemaVersion version = resolveSchemaVersion(ref);
            List<Column> columns = SchemaToColumnsConverter.toColumns(
                    version.getDataFormat(), version.getSchemaDefinition());
            if (!columns.isEmpty()) {
                Table resolved = copyTable(table);
                resolved.getStorageDescriptor().setColumns(columns);
                return resolved;
            }
        } catch (AwsException e) {
            LOG.warnv("SchemaReference resolution failed for {0}.{1}: {2}",
                    table.getDatabaseName(), table.getName(), e.getMessage());
        }
        return table;
    }

    private SchemaVersion resolveSchemaVersion(SchemaReference ref) {
        boolean latest = ref.getSchemaVersionId() == null && ref.getSchemaVersionNumber() == null;
        return schemaRegistryService.getSchemaVersion(
                ref.getSchemaId(), ref.getSchemaVersionId(),
                ref.getSchemaVersionNumber(), latest, regionResolver.getDefaultRegion());
    }

    private static SchemaReference schemaReferenceOf(Table table) {
        StorageDescriptor sd = table != null ? table.getStorageDescriptor() : null;
        return sd != null ? sd.getSchemaReference() : null;
    }

    private static Table copyTable(Table source) {
        Table copy = new Table();
        copy.setName(source.getName());
        copy.setDatabaseName(source.getDatabaseName());
        copy.setDescription(source.getDescription());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        copy.setLastAccessTime(source.getLastAccessTime());
        copy.setPartitionKeys(copyColumns(source.getPartitionKeys()));
        copy.setStorageDescriptor(copyStorageDescriptor(source.getStorageDescriptor()));
        copy.setTableType(source.getTableType());
        copy.setParameters(copyMap(source.getParameters()));
        return copy;
    }

    private static StorageDescriptor copyStorageDescriptor(StorageDescriptor source) {
        if (source == null) {
            return null;
        }
        StorageDescriptor copy = new StorageDescriptor();
        copy.setColumns(copyColumns(source.getColumns()));
        copy.setLocation(source.getLocation());
        copy.setInputFormat(source.getInputFormat());
        copy.setOutputFormat(source.getOutputFormat());
        copy.setCompressed(source.getCompressed());
        copy.setNumberOfBuckets(source.getNumberOfBuckets());
        copy.setSerdeInfo(copySerDeInfo(source.getSerdeInfo()));
        copy.setParameters(copyMap(source.getParameters()));
        copy.setSchemaReference(copySchemaReference(source.getSchemaReference()));
        return copy;
    }

    private static StorageDescriptor.SerDeInfo copySerDeInfo(StorageDescriptor.SerDeInfo source) {
        if (source == null) {
            return null;
        }
        StorageDescriptor.SerDeInfo copy = new StorageDescriptor.SerDeInfo();
        copy.setName(source.getName());
        copy.setSerializationLibrary(source.getSerializationLibrary());
        copy.setParameters(copyMap(source.getParameters()));
        return copy;
    }

    private static SchemaReference copySchemaReference(SchemaReference source) {
        if (source == null) {
            return null;
        }
        SchemaReference copy = new SchemaReference();
        SchemaId schemaId = source.getSchemaId();
        if (schemaId != null) {
            copy.setSchemaId(new SchemaId(
                    schemaId.getRegistryName(), schemaId.getSchemaName(), schemaId.getSchemaArn()));
        }
        copy.setSchemaVersionId(source.getSchemaVersionId());
        copy.setSchemaVersionNumber(source.getSchemaVersionNumber());
        return copy;
    }

    private static List<Column> copyColumns(List<Column> source) {
        if (source == null) {
            return null;
        }
        List<Column> copy = new ArrayList<>(source.size());
        for (Column column : source) {
            Column columnCopy = new Column();
            columnCopy.setName(column.getName());
            columnCopy.setType(column.getType());
            columnCopy.setComment(column.getComment());
            copy.add(columnCopy);
        }
        return copy;
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        return source != null ? new LinkedHashMap<>(source) : null;
    }
}
