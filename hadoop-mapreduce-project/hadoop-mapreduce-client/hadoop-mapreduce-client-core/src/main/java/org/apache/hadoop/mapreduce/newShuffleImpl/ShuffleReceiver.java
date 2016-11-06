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
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

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
 *
 * Threads in ShuffleReceiver
 *  3   =numOfThreads   ReceiveThreads
 *  1                   MapCompleteEventFetcher
 *  1                   ackSender
 *  1   =numOf          flushThread
 *  1                   mainThread
 */
public class ShuffleReceiver {
    private static final Log LOG = LogFactory.getLog(ShuffleReceiver.class);
    private static int numOfThreads = 3;
    private static boolean mapAllFinished;
    private static LinkedBlockingDeque<DatagramPacket> pendingACKMsgs;

    private static ReceiveThread[] receivers;
    //TODO : number of threads to simultaneously receive data is set to 3
    public static void main(String[]args)throws  Throwable {
        LOG.info("ShuffleReceiver Running for JobID " + args[1]);

        JobID jobId = JobID.forName(args[1]);
        String host = args[3];
        int port = Integer.parseInt(args[4]);
        int numOfMaps= Integer.parseInt(args[5]);


        pendingACKMsgs =new LinkedBlockingDeque<DatagramPacket>();


        final InetSocketAddress address =
                NetUtils.createSocketAddrForHost(host, port);
        //TODO : RpcServiceDaemon is bounded on shufflePort 21116
        InetSocketAddress addr = new InetSocketAddress("localhost", 21116);
        NewShuffleDaemonProtocol proxy = null;
        TaskUmbilicalProtocol umbilical=null;
        try {
            proxy = RPC.waitForProxy(NewShuffleDaemonProtocol.class, 1, addr, new Configuration());
            umbilical = RPC.getProxy(TaskUmbilicalProtocol.class,
                    TaskUmbilicalProtocol.versionID, address, new JobConf());
        } catch (IOException e) {
            e.printStackTrace();
        }
        Thread ackSender = new ACKSender();
        ackSender.start();
        //bind several receiver threads onto on shufflePort
        receivers = new ReceiveThread[numOfThreads];
        for(int i=0;i<numOfThreads;i++){
            receivers[i]=new ReceiveThread(Integer.parseInt(args[2]));
            receivers[i].start();
        }
        Flusher flusher =new Flusher();
        flusher.start();

        // Start the map-completion events fetcher thread
        final NewEventFetcher eventFetcher =
                new NewEventFetcher(jobId,umbilical,100,numOfMaps);
        eventFetcher.start();
        eventFetcher.join();
        //We expect main thread will wait for eventFetcher's exit
        mapAllFinished=true;

        for(int i=0;i<numOfThreads;i++){
            receivers[i].join();
        }
        //main thread would exit when:
        //Receivers all exited
        ackSender.interrupt();

        if (proxy != null) {
            proxy.completeReceiver(jobId);
        }
    }

    static class ReceiveThread extends Thread{
        private static ConcurrentHashMap<InetSocketAddress,Integer> msgIDs;
        DatagramSocket socket=null;
        DoubleByteCircularBuffer dbcBuf;
        private static byte[] ackMsg= new byte[7];
        int shufflePort;
        public ReceiveThread(int port){
            this.shufflePort=port;
            msgIDs=new ConcurrentHashMap<InetSocketAddress, Integer>();
            ackMsg[0] = (byte) 'A';
            ackMsg[1] = (byte) 'C';
            ackMsg[2] = (byte) 'K';
        }
        public DoubleByteCircularBuffer getDbcBuf(){return dbcBuf;}
        @Override
        public void run(){
            boolean concurrentConcerns=true;
            while(concurrentConcerns){//Concern: maybe when newPort is executed, other threads hasn't set it's own socket as reusable.
                try{
                    socket=new DatagramSocket(shufflePort);
                    socket.setReuseAddress(true);

                    concurrentConcerns=false;
                    /*
                    DatagramSocket socket = new DatagramSocket(null);
                    String host = InetAddress.getLocalHost().getCanonicalHostName();
                    socket.setReuseAddress(true);
                    socket.bind(new InetSocketAddress(host, 20016));
                    socket.receive(dp);
                    */

                }catch (Exception e){
                    System.out.println("Racing socket initialization.");
                }
            }
            //TODO: BUFFER_SIZE here is set to 65508, and should be a passover parameter
            DatagramPacket packet= new DatagramPacket(new byte[65508],65508);
            while(!mapAllFinished){
                try {
                    socket.receive(packet);
                    byte[] data = packet.getData();
                    int length = packet.getLength();
                    InetAddress host = packet.getAddress();
                    int port = packet.getPort();
                    //Every time we receive a package we should send a ACK back.
                    pendingACKMsgs.add(new DatagramPacket(ackMsg, 3, host, port));

                    int msgID = 0;
                    for (int i = 3; i >= 0; i--) {
                        msgID = msgID << 8;
                        msgID += (int) data[i];
                    }

                    InetSocketAddress source=new InetSocketAddress(host,port);
                    if (!msgIDs.containsKey(new InetSocketAddress(host,port))) {
                        msgIDs.put(source, msgID);
                    } else if (msgID <= msgIDs.get(source)) continue;
                    else {
                        msgIDs.put(source, msgID);
                    }
                    //So we get a new package here. We should copy them into DoubleBuffer

                    dbcBuf.put(data,0,length);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    static class ACKSender extends Thread {
        DatagramSocket socket;

        public ACKSender(){

        }
        public void run() {
            while(true){
                try{
                    socket=new DatagramSocket();
                    break;
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
            DatagramPacket tmp = null;
            while (!Thread.interrupted()) {
                try {
                    tmp = pendingACKMsgs.poll();
                    if (tmp == null) {
                        Thread.sleep(100);
                    } else {
                        socket.send(tmp);
                    }
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
    }
    static class Flusher extends Thread{
        int round_robin;
        ByteCircularBuffer current;
        @Override
        public void run(){
            while(!interrupted()){
                try {
                    current=receivers[round_robin].getDbcBuf().getAnother();

                    //TODO : flush procedure

                    round_robin = (round_robin+1)%numOfThreads;
                    sleep(100);
                }catch (Exception e){
                    e.printStackTrace();
                }

            }

        }
    }
    /**
     * Created by haposola on 16-11-6.
     * Double buffer's idea is rent from Pro. Li.
     * When one of this two buffers is full, it should REST, and flush data into disk.
     * And data will be put into the other buffer, which is working
     *
     * We leave the flush procedure to another thread.
     *
     */
    static class DoubleByteCircularBuffer {
        ByteCircularBuffer bone;
        ByteCircularBuffer btwo;
        int size;
        ByteCircularBuffer current;
        public DoubleByteCircularBuffer(int size){
            bone=new ByteCircularBuffer(size);
            btwo=new ByteCircularBuffer(size);
            current=bone;
        }
        public int getSize(){return  size;}
        public ByteCircularBuffer getCurrent(){return current;}
        public ByteCircularBuffer getAnother(){return current==bone?  btwo: bone;}
        public void put(byte[] buf, int off, int len){
            int free=current.freeBlocks();
            if(free > len){
                current.put(buf,off,len);
            }else{
                current.put(buf,off,free);
                switchCurrent();
                current.put(buf,off+free,len-free);
            }
        }
        void switchCurrent(){
            current = (current == bone ? btwo : bone);
        }
    }
}
