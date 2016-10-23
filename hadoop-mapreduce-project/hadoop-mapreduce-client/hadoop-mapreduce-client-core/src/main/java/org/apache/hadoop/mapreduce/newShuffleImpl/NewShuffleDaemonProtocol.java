package org.apache.hadoop.mapreduce.newShuffleImpl;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.ProtocolInfo;
import org.apache.hadoop.ipc.VersionedProtocol;

/**
 * Created by haposola on 16-6-1.
 *
 */
@ProtocolInfo(
        protocolName = "org.apache.hadoop.mapreduce.newShuffleImpl.NewShuffleDaemonProtocol",
        protocolVersion = NewShuffleDaemonProtocol.versionID)
public interface NewShuffleDaemonProtocol extends VersionedProtocol {
    int versionID=1;
    void setConfiguration(Configuration conf);
    void startReduceTask(
            String jobID
            ,String jobFile
            ,String host, int port //taskAttemptListener breaks down to host and port
            ,String appId
            ,int partition
    );
}
