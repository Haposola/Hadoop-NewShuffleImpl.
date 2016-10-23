package org.apache.hadoop.mapreduce.newShuffleImpl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.io.serializer.Serializer;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapOutputCollector;
import org.apache.hadoop.mapred.MapTask;
import org.apache.hadoop.mapred.Task;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;

/**
 * Created by haposola on 16-5-24.
 * Circular Buffer, producer -> map, consumer -> Sliding Window Push Thread
 *
 */
public class MapOutputCollectAndPush<K, V>
        implements MapOutputCollector<K,V> {

    private static final Log LOG = LogFactory.getLog(MapOutputCollectAndPush.class);
    public PushThread pushThread;
    MapTask mapTask;
    ArrayList<CircularBuffer> workers;
    InetAddress[] reduceHosts;
    int mapID;
    private JobConf job;
    private Task.TaskReporter reporter;
    private int partitions;
    private Class<K> keyClass;
    private Class<V> valClass;

    public Thread getPushThread() {
        return pushThread;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void init(Context context) throws IOException, ClassNotFoundException {
        LOG.info("Initialization of MapOutputCollector");
        job = context.getJobConf();
        reporter = context.getReporter();
        mapTask = context.getMapTask();
        partitions = job.getNumReduceTasks();

        keyClass = (Class<K>)job.getMapOutputKeyClass();
        valClass = (Class<V>)job.getMapOutputValueClass();
        mapID = Integer.parseInt(mapTask.getTaskID().toString().split("_")[4]);

        if(FileSystem.get(job) instanceof LocalFileSystem){
            reduceHosts=new InetAddress[1];
            String hs=InetAddress.getByName("localhost").getCanonicalHostName();
            reduceHosts[0]=InetAddress.getByName(hs);
        }else {
            DistributedFileSystem dfs = (DistributedFileSystem) FileSystem.get(job);
            DatanodeInfo[] dni = dfs.getDataNodeStats();
            reduceHosts = new InetAddress[dni.length];
            for (int i = 0; i < dni.length; i++) {
                reduceHosts[i] = InetAddress.getByName(dni[i].getHostName());
            }
        }//Get the reduce hosts.Temporarily  they are all the DataNodes.

        workers = new ArrayList<CircularBuffer>(partitions);

        final int BUFFER_SIZE = job.getInt("mapreduce.map.output.buffer.size", 65508);
        for(int i=0;i<partitions;i++){
            workers.add(new CircularBuffer(i, BUFFER_SIZE));
        }
        pushThread = new PushThread();
        pushThread.start();
    }// init()

    @Override
    public void collect(K key, V value, int partition) throws IOException, InterruptedException {
        if (key.getClass() != keyClass) {
            throw new IOException("Type mismatch in key from map: expected "
                    + keyClass.getName() + ", received "
                    + key.getClass().getName());
        }
        if (value.getClass() != valClass) {
            throw new IOException("Type mismatch in value from map: expected "
                    + valClass.getName() + ", received "
                    + value.getClass().getName());
        }
        if (partition < 0 || partition >= partitions) {
            throw new IOException("Illegal partition for " + key + " (" +
                    partition + ")");
        }
        workers.get(partition).put(key, value);
    }

    @Override
    public void close() throws IOException, InterruptedException {

    }

    @Override
    public void flush() throws IOException, InterruptedException, ClassNotFoundException {

    }

    // Compression for map-outputs
    class PushThread extends Thread {
        //Round-robin to get reference of the CircularBuffer s.

        @Override
        public void run() {
            // consume data in the buffer and send it over the network

            int round_robin = 0;

            while (reporter.getProgress() < 0.667f) {//TODO : This is not well compitable
                CircularBuffer tempCB = workers.get(round_robin);
                ByteCircularBuffer buffer = tempCB.buffer;
                int len = buffer.occupiedBlocks();
                if (len > 0) {
                    LOG.info("Got " + String.valueOf(len + 8) + " bytes to send");
                    int tail = buffer.getTail();
                    int head = (tail + len + buffer.getSize()) % buffer.getSize();
                    byte[] b = new byte[len + 8];
                    int tmp = tempCB.msgID;
                    for (int i = 0; i < 4; i++) {
                        b[i] = (byte) (tmp & 0xFF);
                        tmp = tmp >> 8;
                    }
                    int tmp1 = mapID;
                    for (int i = 4; i < 8; i++) {
                        b[i] = (byte) (tmp1 & 0xFF);
                        tmp1 = tmp1 >> 8;
                    }
                    buffer.get(b, 8, tail, head);
                    //TODO : The communication process may need to be redesigned
                    DatagramPacket dp = new DatagramPacket(b, len + 8, reduceHosts[tempCB.partition], 20016);
                    boolean portAlreadyInUse = false, retransitionNeeded = true;
                    do {
                        DatagramSocket socket = null;
                        try {
                            socket = new DatagramSocket();
                            portAlreadyInUse = false;
                            socket.send(dp);
                            LOG.info("Sended a packet");
                            DatagramPacket ACK = new DatagramPacket(new byte[100], 100);
                            socket.setSoTimeout(5000);
                            socket.receive(ACK);
                            byte[] data = ACK.getData();
                            LOG.info("Received data");
                            if (ACK.getLength() == 3 && data[0] == (byte) 'A' && data[1] == (byte) 'C' && data[2] == (byte) 'K') {
                                LOG.info("Proper ACK received");
                                tempCB.buffer.consume(len);
                                tempCB.msgID += 1;
                                reporter.progress();
                                retransitionNeeded = false;
                            } else {
                                LOG.info("Broken ACK received");
                            }
                        } catch (SocketException e) {
                            if (socket != null) socket.close();
                            socket = null;
                            portAlreadyInUse = true;
                        } catch (IOException e) {
                            LOG.info("Exception when running push Thread, " + e.getMessage());
                            e.printStackTrace();
                        } finally {
                            if (socket != null) socket.close();
                        }
                    } while (portAlreadyInUse && retransitionNeeded);
                }
                round_robin = (round_robin + 1) % partitions;
            }
            LOG.info("Push thread exiting");
        }
    }//class swPushThread extends Thread

    private class CircularBuffer extends OutputStream {
        private final byte[] scratch = new byte[1];
        ByteCircularBuffer buffer;
        int partition;
        int msgID;
        private byte[] bytebuff;
        private SerializationFactory serializationFactory;
        private Serializer<K> keySerializer;
        private Serializer<V> valSerializer;

        public CircularBuffer(int partition, int size) throws IOException {
            bytebuff = new byte[size];
            buffer = new ByteCircularBuffer(bytebuff);
            this.partition = partition;

            serializationFactory = new SerializationFactory(job);
            keySerializer = serializationFactory.getSerializer(keyClass);
            keySerializer.open(this);
            valSerializer = serializationFactory.getSerializer(valClass);
            valSerializer.open(this);
        }

        @Override
        public void write(int v) throws IOException {
            scratch[0] = (byte) v;
            write(scratch, 0, 1);
        }

        @Override
        public void write(byte b[], int off, int len)
                throws IOException {
            buffer.put(b, off, len);
            //mount+=len;
        }

        public void put(K key, V val) throws IOException {
            //mount=0;
            keySerializer.serialize(key);
            valSerializer.serialize(val);
            buffer.moveHeadToCurrent();
        }

    }//class CircularBufferWithPushThread
}