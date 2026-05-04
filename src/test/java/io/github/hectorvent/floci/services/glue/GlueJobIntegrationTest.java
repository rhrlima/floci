package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlueJobIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String JOB_NAME = "test-etl-job";
    private static final String JOB_NAME_2 = "test-etl-job-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.specification.RequestSpecification glue(String action) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue." + action);
    }

    // ── CreateJob ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createJob() {
        glue("CreateJob")
            .body("""
                {
                    "Name": "%s",
                    "Role": "arn:aws:iam::000000000000:role/GlueRole",
                    "Command": {
                        "Name": "glueetl",
                        "ScriptLocation": "s3://my-bucket/scripts/etl.py"
                    },
                    "GlueVersion": "4.0",
                    "WorkerType": "G.1X",
                    "NumberOfWorkers": 2,
                    "Timeout": 60,
                    "DefaultArguments": {
                        "--job-language": "python"
                    }
                }
                """.formatted(JOB_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Name", equalTo(JOB_NAME));
    }

    @Test
    @Order(2)
    void createDuplicateJob_returns400() {
        glue("CreateJob")
            .body("""
                {
                    "Name": "%s",
                    "Role": "arn:aws:iam::000000000000:role/GlueRole",
                    "Command": {
                        "Name": "glueetl",
                        "ScriptLocation": "s3://my-bucket/scripts/etl.py"
                    }
                }
                """.formatted(JOB_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AlreadyExistsException"));
    }

    // ── GetJob ────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void getJob() {
        glue("GetJob")
            .body("""
                { "JobName": "%s" }
                """.formatted(JOB_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Job.Name", equalTo(JOB_NAME))
            .body("Job.Role", equalTo("arn:aws:iam::000000000000:role/GlueRole"))
            .body("Job.Command.Name", equalTo("glueetl"))
            .body("Job.Command.ScriptLocation", equalTo("s3://my-bucket/scripts/etl.py"))
            .body("Job.GlueVersion", equalTo("4.0"))
            .body("Job.WorkerType", equalTo("G.1X"))
            .body("Job.NumberOfWorkers", equalTo(2))
            .body("Job.Timeout", equalTo(60))
            .body("Job.CreatedOn", notNullValue())
            .body("Job.LastModifiedOn", notNullValue());
    }

    @Test
    @Order(4)
    void getJob_notFound_returns400() {
        glue("GetJob")
            .body("{ \"JobName\": \"does-not-exist\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }

    // ── UpdateJob ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void updateJob() {
        glue("UpdateJob")
            .body("""
                {
                    "JobName": "%s",
                    "JobUpdate": {
                        "Role": "arn:aws:iam::000000000000:role/UpdatedRole",
                        "NumberOfWorkers": 5
                    }
                }
                """.formatted(JOB_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobName", equalTo(JOB_NAME));
    }

    @Test
    @Order(6)
    void getJob_afterUpdate_reflectsChanges() {
        glue("GetJob")
            .body("""
                { "JobName": "%s" }
                """.formatted(JOB_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Job.Role", equalTo("arn:aws:iam::000000000000:role/UpdatedRole"))
            .body("Job.NumberOfWorkers", equalTo(5));
    }

    // ── GetJobs / ListJobs ────────────────────────────────────────────────────

    @Test
    @Order(7)
    void createSecondJob() {
        glue("CreateJob")
            .body("""
                {
                    "Name": "%s",
                    "Role": "arn:aws:iam::000000000000:role/GlueRole",
                    "Command": {
                        "Name": "pythonshell",
                        "ScriptLocation": "s3://my-bucket/scripts/shell.py",
                        "PythonVersion": "3"
                    }
                }
                """.formatted(JOB_NAME_2))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(8)
    void getJobs_returnsBothJobs() {
        glue("GetJobs")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Jobs.Name", hasItems(JOB_NAME, JOB_NAME_2));
    }

    @Test
    @Order(9)
    void listJobs_returnsJobNames() {
        glue("ListJobs")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobNames", hasItems(JOB_NAME, JOB_NAME_2));
    }

    // ── BatchGetJobs ──────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void batchGetJobs_returnsFoundAndNotFound() {
        glue("BatchGetJobs")
            .body("""
                { "JobNames": ["%s", "ghost-job"] }
                """.formatted(JOB_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Jobs.Name", hasItem(JOB_NAME))
            .body("JobsNotFound", hasItem("ghost-job"));
    }

    // ── DeleteJob ─────────────────────────────────────────────────────────────

    @Test
    @Order(11)
    void deleteJob() {
        glue("DeleteJob")
            .body("""
                { "JobName": "%s" }
                """.formatted(JOB_NAME_2))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobName", equalTo(JOB_NAME_2));
    }

    @Test
    @Order(12)
    void getJob_afterDelete_returns400() {
        glue("GetJob")
            .body("""
                { "JobName": "%s" }
                """.formatted(JOB_NAME_2))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    @Order(13)
    void listJobs_afterDelete_doesNotContainDeletedJob() {
        glue("ListJobs")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobNames", not(hasItem(JOB_NAME_2)));
    }

    // ── StartJobRun ───────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void startJobRun_returnsRunId() {
        glue("StartJobRun")
            .body("""
                {
                    "JobName": "%s",
                    "Arguments": { "--output": "s3://my-bucket/output/" }
                }
                """.formatted(JOB_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobRunId", notNullValue());
    }

    @Test
    @Order(21)
    void startJobRun_nonExistentJob_returns400() {
        glue("StartJobRun")
            .body("{ \"JobName\": \"no-such-job\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }

    // ── GetJobRun ─────────────────────────────────────────────────────────────

    @Test
    @Order(22)
    void getJobRun_afterStart_showsSucceeded() {
        // Start a run to get a valid ID
        String runId = glue("StartJobRun")
            .body("{ \"JobName\": \"%s\" }".formatted(JOB_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobRunId");

        glue("GetJobRun")
            .body("""
                { "JobName": "%s", "RunId": "%s" }
                """.formatted(JOB_NAME, runId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobRun.Id", equalTo(runId))
            .body("JobRun.JobName", equalTo(JOB_NAME))
            .body("JobRun.JobRunState", equalTo("SUCCEEDED"))
            .body("JobRun.StartedOn", notNullValue())
            .body("JobRun.CompletedOn", notNullValue());
    }

    @Test
    @Order(23)
    void getJobRun_notFound_returns400() {
        glue("GetJobRun")
            .body("{ \"JobName\": \"%s\", \"RunId\": \"00000000-0000-0000-0000-000000000000\" }".formatted(JOB_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }

    // ── GetJobRuns ────────────────────────────────────────────────────────────

    @Test
    @Order(24)
    void getJobRuns_returnsAllRunsForJob() {
        glue("StartJobRun")
            .body("{ \"JobName\": \"%s\" }".formatted(JOB_NAME))
        .when()
            .post("/");

        glue("GetJobRuns")
            .body("{ \"JobName\": \"%s\" }".formatted(JOB_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobRuns.size()", greaterThan(0));
    }

    // ── BatchStopJobRun ───────────────────────────────────────────────────────

    @Test
    @Order(25)
    void batchStopJobRun_alreadyCompleted_returnsInErrors() {
        String runId = glue("StartJobRun")
            .body("{ \"JobName\": \"%s\" }".formatted(JOB_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("JobRunId");

        glue("BatchStopJobRun")
            .body("""
                { "JobName": "%s", "JobRunIds": ["%s"] }
                """.formatted(JOB_NAME, runId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Errors.size()", equalTo(1))
            .body("SuccessfulSubmissions.size()", equalTo(0));
    }

    @Test
    @Order(99)
    void cleanup_deleteFirstJob() {
        glue("DeleteJob")
            .body("""
                { "JobName": "%s" }
                """.formatted(JOB_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
