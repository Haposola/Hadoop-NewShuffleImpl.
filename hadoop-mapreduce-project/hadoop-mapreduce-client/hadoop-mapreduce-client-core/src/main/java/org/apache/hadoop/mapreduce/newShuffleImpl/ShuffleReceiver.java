package org.apache.hadoop.mapreduce.newShuffleImpl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobID;
import org.apache.hadoop.mapred.TaskUmbilicalProtocol;
import org.apache.hadoop.net.NetUtils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * //Created by haposola on 16-6-2.
 *
 * So we refined the structure of this system and make it dedicated to ShufflerReceiver
 *
 * YUE DING the path and name of temporary shuffle files.
 * $HADOOP_TMP_PATH/$jobID_shuffle/$buffer_number
 * Maybe simply use ReduceTask's configuration
 *
 * MapCompletionEventFetcher needs too many arguments,
 * Almost catch up to the initialization of YarnChild.
 * NOT VERY COMFORTABLE
 */
public class ShuffleReceiver {
    private static final Log LOG = LogFactory.getLog(ShuffleReceiver.class);
    private static JobID jobId;
    private static int shufflePort;
    private static int numOfThreads = 5;

    //TODO : number of threads to simultaneously receiver data is set to 5
    public static void main(String[]args)throws  Throwable {
        LOG.info("ShuffleReceiver Running for JobID " + args[1]);

        jobId = JobID.forName(args[1]);
        shufflePort = Integer.parseInt(args[2]);
        String host = args[3];
        int port = Integer.parseInt(args[4]);
        int numOfMaps= Integer.parseInt(args[5]);

        final InetSocketAddress address =
                NetUtils.createSocketAddrForHost(host, port);
        //TODO : RpcServiceDaemon is bounded on shufflePort 21116
        InetSocketAddress addr = new InetSocketAddress("localhost", 21116);
        NewShuffleDaemonProtocol proxy = null;
        try {
            proxy = RPC.waitForProxy(NewShuffleDaemonProtocol.class, 1, addr, new Configuration());
        } catch (IOException e) {
            e.printStackTrace();
        }
        //bind several receiver threads onto on shufflePort
        for(int i=0;i<numOfThreads;i++){
            new receiveThread().run();
        }
        final TaskUmbilicalProtocol umbilical = RPC.getProxy(TaskUmbilicalProtocol.class,
                TaskUmbilicalProtocol.versionID, address, new JobConf());
        //TODO : Is a  newed JobConf safe?

        // Start the map-completion events fetcher thread
        final NewEventFetcher eventFetcher =
                new NewEventFetcher(jobId,umbilical,100,numOfMaps);
        eventFetcher.start();
        //Then how this JVMTask knows its work is finished?
            //Condition: all MapTasks of this job is finished
        //So, eventFetcher.


        if (proxy != null) {
            proxy.completeReceiver(jobId);
        }
    }

    static class receiveThread extends Thread{

        DatagramSocket socket=null;
        @Override
        public void run(){
            boolean concurrentConcerns=true;
            while(concurrentConcerns){
                try{
                    socket=new DatagramSocket(shufflePort);
                    concurrentConcerns=false;
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
            DatagramPacket packet= new DatagramPacket(new byte[2048],2048);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
