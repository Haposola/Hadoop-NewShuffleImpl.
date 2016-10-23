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

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;


public  class NewShuffleRpcServiceDaemon {
    //ConcurrentHashMap<> RunningReduceTasks;
    //TODO: need to know the running ReduceTasks. To inform them that its MapTasks is finished?
    //Or use umbilical to fetch JobTracker Events?
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


        //for(int i=0;i<5;i++) {
        //   Thread receive = new NewShuffleRpcServiceDaemon();
        // receive.start();
        // }
    }

    class RpcService implements NewShuffleDaemonProtocol {
        Configuration conf;

        @Override
        public void setConfiguration(Configuration conf) {
            this.conf=conf;
        }

        @Override
        public void startReduceTask(
                String jobId
                ,String jobFile// It seems a symbolic link should be created for this file. jobFile ->"job.xml"
                //int numMapTasks,
                ,String host, int port//TaskAttemptListener taskAttemptListener,//used
                ,String appId//used
                ,int partition
                //int id,
                //int num
        ) {
            //String sid=String.valueOf(num);
            //int l=sid.length();
            //for(int i=0;i<6-l;i++)sid="0"+sid;
            //new JobId
            LOG.info("Starting ReduceTask with AttemptID : "+ appId);
            /*
            String appAttemptId="attempt_"+
                    String.valueOf(appId)+"_"
                    +String.valueOf(id)+"_"
                    +"r_"+sid+"_"
                    +String.valueOf(0);
            */

            
            //args.add(String.valueOf()); TODO: what if we don't use JVM ID?
            try {
                LOG.info("Starting Process Builder");
                ProcessBuilder x=new ProcessBuilder(
                        "/home/haposola/hadoop/ReduceTaskShells/RunReduceTask.sh"
                        ,jobId,jobFile
                        ,host
                        ,String.valueOf(port)
                        ,appId
                        ,String.valueOf(partition)
                        //appAttemptId,
                        //String.valueOf(numMapTasks),
                        //host, String.valueOf(port)
                        );
                x.redirectOutput(new File("/home/haposola/hadoop/ReduceTaskShells/RunLog"));
                x.start();
                //How To new a JVM?0
                //HHH, eventually it is to run a script
                //reduce.run(umbilical);
                //new Shuffle().start();
            }catch (IOException e){
                LOG.warn("ERROR when executing bash to launch ReduceChild");
                LOG.info(e.getMessage());
                e.printStackTrace();

            }
        }

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