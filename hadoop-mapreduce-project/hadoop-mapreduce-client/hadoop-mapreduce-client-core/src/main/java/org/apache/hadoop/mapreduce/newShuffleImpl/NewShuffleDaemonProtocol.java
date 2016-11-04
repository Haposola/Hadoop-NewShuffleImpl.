package org.apache.hadoop.mapreduce.newShuffleImpl;


import org.apache.hadoop.ipc.ProtocolInfo;
import org.apache.hadoop.ipc.VersionedProtocol;
import org.apache.hadoop.mapreduce.JobID;

/**
 * Created by haposola on 16-6-1.
 *
 */
@ProtocolInfo(
        protocolName = "org.apache.hadoop.mapreduce.newShuffleImpl.NewShuffleDaemonProtocol",
        protocolVersion = NewShuffleDaemonProtocol.versionID)
public interface NewShuffleDaemonProtocol extends VersionedProtocol {
    int versionID=1;

    int startShuffleReceiver(JobID jobID, String host, int port, int numOfMaps);
    ReceiverStatus getLocalReceiverStatus(JobID jobID);
    void completeReceiver(JobID jobID);
    //void updateShufflePort(JobID jobID, int port);
    //int getShufflePort(JobID jobID);
}
