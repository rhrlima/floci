package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ExecutionProperty {

    @JsonProperty("MaxConcurrentRuns")
    private int maxConcurrentRuns = 1;

    public ExecutionProperty() {}

    public int getMaxConcurrentRuns() { return maxConcurrentRuns; }
    public void setMaxConcurrentRuns(int maxConcurrentRuns) { this.maxConcurrentRuns = maxConcurrentRuns; }
}
