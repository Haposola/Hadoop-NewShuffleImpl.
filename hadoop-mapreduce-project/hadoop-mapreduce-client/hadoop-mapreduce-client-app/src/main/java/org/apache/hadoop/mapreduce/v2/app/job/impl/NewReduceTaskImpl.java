package org.apache.hadoop.mapreduce.v2.app.job.impl;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.NewReduceTaskAttemptImpl;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.security.token.JobTokenIdentifier;
import org.apache.hadoop.mapreduce.v2.api.records.JobId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskType;
import org.apache.hadoop.mapreduce.v2.app.AppContext;
import org.apache.hadoop.mapreduce.v2.app.TaskAttemptListener;
import org.apache.hadoop.mapreduce.v2.app.metrics.MRAppMetrics;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.util.Clock;

/**
 * Created by haposola on 16-9-8.
 */
public class NewReduceTaskImpl extends TaskImpl {
    private final int numMapTasks;

    public NewReduceTaskImpl(JobId jobId, int partition,
                             EventHandler eventHandler, Path jobFile, JobConf conf,
                             int numMapTasks, TaskAttemptListener taskAttemptListener,
                             Token<JobTokenIdentifier> jobToken,
                             Credentials credentials, Clock clock,
                             int appAttemptId, MRAppMetrics metrics, AppContext appContext) {
        super(jobId, TaskType.REDUCE, partition, eventHandler, jobFile, conf,
                taskAttemptListener, jobToken, credentials, clock,
                appAttemptId, metrics, appContext);
        this.numMapTasks = numMapTasks;
    }

    @Override
    protected int getMaxAttempts() {
        return conf.getInt(MRJobConfig.REDUCE_MAX_ATTEMPTS, 4);
    }

    @Override
    protected TaskAttemptImpl createAttempt() {
        return new NewReduceTaskAttemptImpl(getID(), nextAttemptNumber,
                eventHandler, jobFile,
                partition, numMapTasks, conf, taskAttemptListener,
                jobToken, credentials, clock, appContext);
    }

    @Override
    public TaskType getType() {
        return TaskType.REDUCE;
    }

}
