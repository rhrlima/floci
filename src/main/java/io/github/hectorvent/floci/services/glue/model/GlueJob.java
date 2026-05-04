package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
public class GlueJob {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("Role")
    private String role;

    @JsonProperty("Command")
    private JobCommand command;

    @JsonProperty("DefaultArguments")
    private Map<String, String> defaultArguments;

    @JsonProperty("NonOverridableArguments")
    private Map<String, String> nonOverridableArguments;

    @JsonProperty("ExecutionProperty")
    private ExecutionProperty executionProperty;

    @JsonProperty("MaxRetries")
    private int maxRetries;

    @JsonProperty("Timeout")
    private Integer timeout;

    @JsonProperty("MaxCapacity")
    private Double maxCapacity;

    @JsonProperty("WorkerType")
    private String workerType;

    @JsonProperty("NumberOfWorkers")
    private Integer numberOfWorkers;

    @JsonProperty("GlueVersion")
    private String glueVersion;

    @JsonProperty("SecurityConfiguration")
    private String securityConfiguration;

    @JsonProperty("LogUri")
    private String logUri;

    @JsonProperty("ExecutionClass")
    private String executionClass;

    @JsonProperty("Tags")
    private Map<String, String> tags;

    @JsonProperty("CreatedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdOn;

    @JsonProperty("LastModifiedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastModifiedOn;

    public GlueJob() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public JobCommand getCommand() { return command; }
    public void setCommand(JobCommand command) { this.command = command; }
    public Map<String, String> getDefaultArguments() { return defaultArguments; }
    public void setDefaultArguments(Map<String, String> defaultArguments) { this.defaultArguments = defaultArguments; }
    public Map<String, String> getNonOverridableArguments() { return nonOverridableArguments; }
    public void setNonOverridableArguments(Map<String, String> nonOverridableArguments) { this.nonOverridableArguments = nonOverridableArguments; }
    public ExecutionProperty getExecutionProperty() { return executionProperty; }
    public void setExecutionProperty(ExecutionProperty executionProperty) { this.executionProperty = executionProperty; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public Integer getTimeout() { return timeout; }
    public void setTimeout(Integer timeout) { this.timeout = timeout; }
    public Double getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(Double maxCapacity) { this.maxCapacity = maxCapacity; }
    public String getWorkerType() { return workerType; }
    public void setWorkerType(String workerType) { this.workerType = workerType; }
    public Integer getNumberOfWorkers() { return numberOfWorkers; }
    public void setNumberOfWorkers(Integer numberOfWorkers) { this.numberOfWorkers = numberOfWorkers; }
    public String getGlueVersion() { return glueVersion; }
    public void setGlueVersion(String glueVersion) { this.glueVersion = glueVersion; }
    public String getSecurityConfiguration() { return securityConfiguration; }
    public void setSecurityConfiguration(String securityConfiguration) { this.securityConfiguration = securityConfiguration; }
    public String getLogUri() { return logUri; }
    public void setLogUri(String logUri) { this.logUri = logUri; }
    public String getExecutionClass() { return executionClass; }
    public void setExecutionClass(String executionClass) { this.executionClass = executionClass; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public Instant getCreatedOn() { return createdOn; }
    public void setCreatedOn(Instant createdOn) { this.createdOn = createdOn; }
    public Instant getLastModifiedOn() { return lastModifiedOn; }
    public void setLastModifiedOn(Instant lastModifiedOn) { this.lastModifiedOn = lastModifiedOn; }
}
