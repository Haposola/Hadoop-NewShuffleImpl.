package org.apache.hadoop.mapreduce.newShuffleImpl;


/**
 * Created by haposola on 16-5-29.
 * 100 Lines?!
 * ReduceTask.java in Hadoop uses 600 lines.
 * Buf MapTask.java has 2000 lines.
 */

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.ProtocolSignature;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.mapreduce.JobID;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;


public  class NewShuffleRpcServiceDaemon {

    //TODO : RpcServiceDaemon is bounded on port 21116
    private static final Log LOG = LogFactory.getLog(NewShuffleRpcServiceDaemon.class.getName());
    Server server;
    public NewShuffleRpcServiceDaemon(){
        try {
            String localhost= InetAddress.getLocalHost().getCanonicalHostName();
            server = new RPC.Builder(new Configuration())
                    .setInstance(new RpcService())
                    .setBindAddress(localhost)
                    .setProtocol(NewShuffleDaemonProtocol.class)
                    .setPort(21116)
                    .setNumHandlers(5)
                    .build();
            server.start();
            LOG.info("Successfully started RPC daemon for ReduceTask");
        }catch (IOException e){
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        new NewShuffleRpcServiceDaemon();
    }

    class RpcService implements NewShuffleDaemonProtocol {

        HashMap<JobID,ReceiverStatus> runningReceivers= new HashMap<JobID,ReceiverStatus>();
        HashMap<JobID,Integer> receiverPorts = new HashMap<JobID, Integer>();
        @Override
        public int startShuffleReceiver(JobID jobID, String host, int port, int numOfMaps) {
            LOG.info("Starting ShuffleReceiver for job " + jobID.toString());
            runningReceivers.put(jobID,ReceiverStatus.RUNNING);
            Socket socket = null;
            try {
                LOG.info("Starting Process Builder");
                //Pick a free port and pass it to ShuffleReceiver
                socket =new Socket();
                socket.setReuseAddress(true);
                //TODO : Integrate this class into Hadoop's Daemon system.
                ProcessBuilder x=new ProcessBuilder(
                        "/home/haposola/hadoop/ReduceTaskShells/RunShuffleReceiver.sh"
                        ,jobID.toString()
                        ,String.valueOf(socket.getLocalPort())
                        ,host,String.valueOf(port),String.valueOf(numOfMaps)
                        );
                x.redirectOutput(new File("/home/haposola/hadoop/ReduceTaskShells/RunLog"));
                x.start();
                //How To new a JVM?0
                //HHH, eventually it is to run a script

            }catch (IOException e){
                LOG.warn("ERROR when executing bash to launch ShuffleReceiver");
                LOG.info(e.getMessage());
                e.printStackTrace();

            }
            return socket.getLocalPort();
        }

        @Override
        public ReceiverStatus getLocalReceiverStatus(JobID jobID) {
            ReceiverStatus status=runningReceivers.get(jobID);
            if(status== ReceiverStatus.COMPLETE){
                //So when COMPLETE status is queried, it will never be queried any more.
                runningReceivers.remove(jobID);
            }
            return status;
        }

        @Override
        public void completeReceiver(JobID jobID) {
            //Receiver reports its completion and mark itself as COMPLETE
            //This record is to be removed when ReduceTask queries this status
            runningReceivers.put(jobID,ReceiverStatus.COMPLETE);
            receiverPorts.remove(jobID);
        }
/*
        @Override
        public void updateShufflePort(JobID jobID, int port) {
            receiverPorts.put(jobID,port);
        }

        @Override
        public int getShufflePort(JobID jobID) {
            return receiverPorts.get(jobID);
        }
*/
        @Override
        public long getProtocolVersion(String protocol, long clientVersion) throws IOException {
            return versionID;
        }

        @Override
        public ProtocolSignature getProtocolSignature(String protocol, long clientVersion, int clientMethodsHash) throws IOException {
            return new ProtocolSignature(versionID,new int[3]);
        }
    }
}