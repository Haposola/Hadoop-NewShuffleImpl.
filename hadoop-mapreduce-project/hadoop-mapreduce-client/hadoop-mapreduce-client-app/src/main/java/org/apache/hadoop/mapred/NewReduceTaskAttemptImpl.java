package org.apache.hadoop.mapred;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.TypeConverter;
import org.apache.hadoop.mapreduce.security.token.JobTokenIdentifier;
import org.apache.hadoop.mapreduce.v2.api.records.TaskId;
import org.apache.hadoop.mapreduce.v2.app.AppContext;
import org.apache.hadoop.mapreduce.v2.app.TaskAttemptListener;
import org.apache.hadoop.mapreduce.v2.app.job.impl.TaskAttemptImpl;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.event.EventHandler;

/**
 * Created by haposola on 16-9-8.
 */
public class NewReduceTaskAttemptImpl extends TaskAttemptImpl {

    private final int numMapTasks;

    public NewReduceTaskAttemptImpl(TaskId id, int attempt,
                                    EventHandler eventHandler, Path jobFile, int partition,
                                    int numMapTasks, JobConf conf,
                                    TaskAttemptListener taskAttemptListener,
                                    Token<JobTokenIdentifier> jobToken,
                                    Credentials credentials, org.apache.hadoop.yarn.util.Clock clock,
                                    AppContext appContext) {
        super(id, attempt, eventHandler, taskAttemptListener, jobFile, partition,
                conf, new String[]{}, jobToken, credentials, clock,
                appContext);
        this.numMapTasks = numMapTasks;
    }

    @Override
    public Task createRemoteTask() {
        //job file name is set in TaskAttempt, setting it null here
        NewReduceTask newReduceTask =
                new NewReduceTask("", TypeConverter.fromYarn(getID()), partition,
                        numMapTasks, 1); // YARN doesn't have the concept of slots per task, set it as 1.
        newReduceTask.setUser(conf.get(MRJobConfig.USER_NAME));
        newReduceTask.setConf(conf);
        return newReduceTask;
    }


}
