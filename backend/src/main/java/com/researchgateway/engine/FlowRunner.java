package com.researchgateway.engine;

import com.researchgateway.domain.Flow;
import com.researchgateway.domain.Run;
import com.researchgateway.service.RunStore;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives a single run to completion, either synchronously (blocking the caller until the run
 * finishes) or asynchronously on a background pool so the HTTP request can return immediately and
 * the client can poll the run as steps stream in.
 *
 * <p>The engine persists steps incrementally via {@link RunStore}, so both modes produce the same
 * live, queryable trace — async just lets the caller observe it as it happens.
 */
@Component
public class FlowRunner {

    private final FlowEngine engine;
    private final RunStore store;
    private final ExecutorService pool;

    public FlowRunner(FlowEngine engine, RunStore store) {
        this.engine = engine;
        this.store = store;
        this.pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "flow-runner");
            t.setDaemon(true);
            return t;
        });
    }

    /** Run on a background thread; returns immediately. */
    public void launch(UUID runId, UUID flowId) {
        pool.submit(() -> execute(runId, flowId));
    }

    /** Run on the calling thread; blocks until the run finishes. */
    public void execute(UUID runId, UUID flowId) {
        try {
            Run run = store.loadRun(runId);
            Flow flow = store.loadFlow(flowId);
            engine.execute(run, flow);
        } catch (Exception e) {
            store.fail(runId, e.getMessage());
        }
    }
}
