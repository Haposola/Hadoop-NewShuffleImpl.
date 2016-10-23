package org.apache.hadoop.mapred;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableFactories;
import org.apache.hadoop.io.WritableFactory;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.io.serializer.Deserializer;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.io.serializer.Serializer;
import org.apache.hadoop.mapreduce.TaskCounter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormatCounter;
import org.apache.hadoop.util.Progress;
import org.apache.hadoop.util.ReflectionUtils;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by haposola on 16-5-31.
 * My ReduceTask
 * Map-end I just modified the OutputCollector.
 * But taking file-fetching out makes the ShuffleConsumerPlugin unusable, since it is file-based
 * So? Re-write Reduce Task seems to be the only way.
 * The code lies here but should works when I put it with Hadoop Source Code.
 * THAT IS FAR ANOTHER PROBLEM
 * ================
 * Now because of package privacy I have to move the code here.
 * At 6.7 night.
 * Every received serialized data buffer consists of different  k's <k,v> pair.
 * We implement it as this: Deserializer directly deserialize <k,v>pairs and put them into its HashMap.
 * If isFastStart=true, then FocusedReducer consume V and do reduce function.
 * Else, FocusReducer will
 *      start EventFetcher, Write Objects into file, and Read them to do reduce function when
 *          MapFinishedEvents all arrive.
 * We do that because we temporarily don't know how to decide the length of V in data buffer.
 * ======
 * I can't imagine I should write so many things.
 * Context is changed because I change RawIO to ObjectIO
 */
@SuppressWarnings("unchecked")
public class NewReduceTask<INKEY,INVALUE,OUTKEY,OUTVALUE> extends Task {
    private static final Log LOG = LogFactory.getLog(NewReduceTask.class.getName());

    static {                                        // register actor
        WritableFactories.setFactory
                (NewReduceTask.class,
                        new WritableFactory() {
                            public Writable newInstance() {
                                return new NewReduceTask();
                            }
                        });
    }

    boolean isFastStart;
    String jarPath;
    String jobPath;
    Class reducerClass ,iKeyClass,iValClass, oKeyClass, oValClass, cmptrClass;
    JobConf job;
    NewShuffleReceiver shuffleThread;
    String tmpOutDir;
    TaskAttemptID taskID;
    TaskReporter reporter;
    SerializationFactory serializationFactory;
    org.apache.hadoop.mapreduce.RecordWriter<OUTKEY, OUTVALUE> writer;
    //RawComparator comparator;
    ArrayList<Thread> workThreads;
    int numMaps;
    boolean mapAllFinished;
    MultiLock mainLock;
    ConcurrentHashMap<INKEY, Vector<INVALUE>> keyValue = new ConcurrentHashMap<INKEY, Vector<INVALUE>>();
    ConcurrentHashMap<Integer, Integer> hostID = new ConcurrentHashMap<Integer, Integer>();
    private Progress copyPhase;
    //private Progress sortPhase; TODO : think sort is avoidable. This is the start point of my work.
    private Progress reducePhase;
    private Counters.Counter shuffledMapsCounter =
            getCounters().findCounter(TaskCounter.SHUFFLED_MAPS);
    private Counters.Counter reduceShuffleBytes =
            getCounters().findCounter(TaskCounter.REDUCE_SHUFFLE_BYTES);
    private Counters.Counter reduceInputKeyCounter =
            getCounters().findCounter(TaskCounter.REDUCE_INPUT_GROUPS);
    private Counters.Counter reduceInputValueCounter =
            getCounters().findCounter(TaskCounter.REDUCE_INPUT_RECORDS);
    private Counters.Counter reduceOutputCounter =
            getCounters().findCounter(TaskCounter.REDUCE_OUTPUT_RECORDS);
    private Counters.Counter reduceCombineInputCounter =
            getCounters().findCounter(TaskCounter.COMBINE_INPUT_RECORDS);
    private Counters.Counter reduceCombineOutputCounter =
            getCounters().findCounter(TaskCounter.COMBINE_OUTPUT_RECORDS);
    private Counters.Counter fileOutputByteCounter =
            getCounters().findCounter(FileOutputFormatCounter.BYTES_WRITTEN);

    {
        getProgress().setStatus("reduce");
        setPhase(TaskStatus.Phase.SHUFFLE);        // phase to start with
    }
    public NewReduceTask(){
        super();
    }

    public NewReduceTask(String jobFile, TaskAttemptID taskId,
                         int partition, int numMaps, int numSlotsRequired) {
        super(jobFile, taskId, partition, numSlotsRequired);
        this.numMaps = numMaps;
    }

    private CompressionCodec initCodec() {
        // check if map-outputs are to be compressed
        if (conf.getCompressMapOutput()) {
            Class<? extends CompressionCodec> codecClass =
                    conf.getMapOutputCompressorClass(DefaultCodec.class);
            return ReflectionUtils.newInstance(codecClass, conf);
        }

        return null;
    }

    @Override
    public boolean isMapTask() {
        return false;
    }

    public int getNumMaps() {
        return numMaps;
    }

    /**
     * Localize the given JobConf to be specific for this task.
     */
    @Override
    public void localizeConfiguration(JobConf conf) throws IOException {
        super.localizeConfiguration(conf);
        conf.setNumMapTasks(numMaps);
    }

    @Override
    public void write(DataOutput out) throws IOException {
        super.write(out);
        out.writeInt(numMaps);                        // write the number of maps
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        super.readFields(in);
        numMaps = in.readInt();
    }

    @Override
    public void run(JobConf job, TaskUmbilicalProtocol umbilical)
            throws IOException, ClassNotFoundException, InterruptedException {
        if (isMapOrReduce()) {
            copyPhase = getProgress().addPhase("copy");
            reducePhase = getProgress().addPhase("reduce");
        }
        reporter = new TaskReporter(new Progress(), umbilical);
        LOG.info("seeing NewReduce Running YO");

    }

    static class NewTrackingRecordWriter<K, V>
            extends org.apache.hadoop.mapreduce.RecordWriter<K, V> {
        private final org.apache.hadoop.mapreduce.RecordWriter<K, V> real;
        private final org.apache.hadoop.mapreduce.Counter outputRecordCounter;
        private final org.apache.hadoop.mapreduce.Counter fileOutputByteCounter;
        private final List<FileSystem.Statistics> fsStats;

        @SuppressWarnings("unchecked")
        NewTrackingRecordWriter(NewReduceTask reduce,
                                org.apache.hadoop.mapreduce.TaskAttemptContext taskContext)
                throws InterruptedException, IOException {
            this.outputRecordCounter = reduce.reduceOutputCounter;
            this.fileOutputByteCounter = reduce.fileOutputByteCounter;

            List<FileSystem.Statistics> matchedStats = null;
            if (reduce.outputFormat instanceof org.apache.hadoop.mapreduce.lib.output.FileOutputFormat) {
                matchedStats = getFsStatistics(org.apache.hadoop.mapreduce.lib.output.FileOutputFormat
                        .getOutputPath(taskContext), taskContext.getConfiguration());
            }

            fsStats = matchedStats;

            long bytesOutPrev = getOutputBytes(fsStats);
            this.real = (org.apache.hadoop.mapreduce.RecordWriter<K, V>) reduce.outputFormat
                    .getRecordWriter(taskContext);
            long bytesOutCurr = getOutputBytes(fsStats);
            fileOutputByteCounter.increment(bytesOutCurr - bytesOutPrev);
        }

        @Override
        public void close(org.apache.hadoop.mapreduce.TaskAttemptContext context) throws IOException,
                InterruptedException {
            long bytesOutPrev = getOutputBytes(fsStats);
            real.close(context);
            long bytesOutCurr = getOutputBytes(fsStats);
            fileOutputByteCounter.increment(bytesOutCurr - bytesOutPrev);
        }

        @Override
        public void write(K key, V value) throws IOException, InterruptedException {
            long bytesOutPrev = getOutputBytes(fsStats);
            real.write(key, value);
            long bytesOutCurr = getOutputBytes(fsStats);
            fileOutputByteCounter.increment(bytesOutCurr - bytesOutPrev);
            outputRecordCounter.increment(1);
        }

        private long getOutputBytes(List<FileSystem.Statistics> stats) {
            if (stats == null) return 0;
            long bytesWritten = 0;
            for (FileSystem.Statistics stat : stats) {
                bytesWritten = bytesWritten + stat.getBytesWritten();
            }
            return bytesWritten;
        }
    }

    class NewShuffleReceiver extends Thread {
        Deserializer<INKEY> keyDeserializer;
        Deserializer<INVALUE> valDeserializer;

        public NewShuffleReceiver(int i) {
            this.setName("ShuffleReceiver" + String.valueOf(i));
        }

        public void run() {
            LOG.info(this.getName() + " starts to run");
            keyDeserializer = serializationFactory.getDeserializer(iKeyClass);
            valDeserializer = serializationFactory.getDeserializer(iValClass);
            while (!mapAllFinished) {
                try {
                    byte[] buffer = new byte[65508];
                    DatagramPacket dp = new DatagramPacket(buffer, 65508);
                    DatagramSocket socket = new DatagramSocket(null);
                    String host = InetAddress.getLocalHost().getCanonicalHostName();
                    socket.setReuseAddress(true);
                    socket.bind(new InetSocketAddress(host, 20016));
                    socket.receive(dp);
                    byte[] data = dp.getData().clone();
                    int length = dp.getLength();
                    InetAddress from = dp.getAddress();
                    int port = dp.getPort();
                    socket.close();
                    int ID = 0;
                    for (int i = 3; i >= 0; i--) {
                        ID = ID << 8;
                        ID += (int) data[i];
                    }
                    int source = 0;
                    for (int i = 7; i >= 4; i--) {
                        source = source << 8;
                        source += (int) data[i];
                    }
                    LOG.info(this.getName() + " received data : " + String.valueOf(length) +
                            " from " + String.valueOf(source) + " with ID " + String.valueOf(ID));
                    new ACKSender(from, port).start();//Send ACK back
                    LOG.info("Sending ACK to " + from + " " + String.valueOf(ID));

                    if (!hostID.containsKey(source)) {
                        hostID.put(source, 0);
                    } else if (ID <= hostID.get(source)) continue;
                    else {
                        hostID.put(source, ID);
                    }
                    //if(!hostID.containsKey(host) && ID<=hostID.get(host))continue;s
                    mainLock.lock();
                    try {
                        KVStream buf = new KVStream(data, 8, length - 8);
                        keyDeserializer.open(buf);
                        valDeserializer.open(buf);
                        while (buf.available() > 0) {
                            INKEY k = null;
                            k = keyDeserializer.deserialize(k);
                            INVALUE v = null;
                            v = valDeserializer.deserialize(v);
                            if (keyValue.containsKey(k)) {//TODO : FATAL PROBLEM, COMPARATOR NEEDED
                                keyValue.get(k).add(v);
                            } else {
                                Vector<INVALUE> tmp = new Vector<INVALUE>();
                                tmp.add(v);
                                keyValue.put(k, tmp);
                                //Thread frt= new FocusedReducer(k,this);//TODO : A thread for each key is really stupid
                                //workThreads.add(frt);
                                //frt.start();
                            }
                        }
                        keyDeserializer.close();
                        valDeserializer.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        mainLock.unlock();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {

                }
            }
        }

        class ACKSender extends Thread {
            InetAddress source;
            int port;

            public ACKSender(InetAddress source, int port) {
                this.source = source;
                this.port = port;
            }

            public void run() {
                byte[] data = new byte[3];
                data[0] = (byte) 'A';
                data[1] = (byte) 'C';
                data[2] = (byte) 'K';
                DatagramPacket dp = new DatagramPacket(data, 3, source, port);
                DatagramSocket tmp = null;
                boolean portAlreadyInUse = false;
                do try {
                    tmp = new DatagramSocket();
                    portAlreadyInUse = false;
                    tmp.send(dp);
                } catch (SocketException e) {
                    portAlreadyInUse = true;
                } catch (IOException e) {
                    e.printStackTrace();
                } while (portAlreadyInUse);
                if (tmp != null) tmp.close();
                tmp = null;
            }
        }
    }

    class KVStream extends ByteArrayInputStream{
        //Exciting. This stream need not to be manually maintained
        public KVStream(byte[] buf,int off,int len){
            super(buf,off,len);
        }
    }

    //Seems that I need a signal to tell  PartialReduce that there is no more input;
    //There should be one FocusedReduceThread for every vector in keyValue-HashMap
    class FocusedReducer extends Thread{
        INKEY keyOfThis;
        Thread shuffleThread;//Using this reference to check ShuffleThread's aliveness. Only PartialThread Needs This.
        org.apache.hadoop.mapreduce.Reducer<INKEY,INVALUE,OUTKEY,OUTVALUE> reducer;
        public FocusedReducer(INKEY key,Thread shuffleThread){
            this.keyOfThis=key;
            reducer = (org.apache.hadoop.mapreduce.Reducer<INKEY,INVALUE,OUTKEY,OUTVALUE>)
                    ReflectionUtils.newInstance(reducerClass, job);
            this.shuffleThread=shuffleThread;
        }
        @Override
        public void run(){
            LOG.info("running NEW reduce task");
            if(isFastStart){
                runFastStart();
            }else{
                runNormal();
            }
        }
        void runFastStart(){
            //FastStart is equal to PartialReduce. We should make a context that maintains a partial result,
            //And reducer.context.write() means write this partial result.
            mainLock.lock();
            try {
                //TODO : the final thing is a signal to tell it to interrupt,
                //              without influence the remaining values in the vector.
                org.apache.hadoop.mapreduce.Reducer.Context
                        reducerContext = createReduceContext(job,taskID,writer,committer,reporter,keyOfThis,keyValue.get(keyOfThis));
                //BAD BAD, Drag all reducer code here. Because PartialReduce may run several times, but setup() won't.
                //reducer.setup(reducerContext);
                //while(true){
                // if(keyValue.get(keyOfThis).isEmpty() && shuffleThread.isAlive())
                //   break;
                //reducer.reduce(keyOfThis,keyValue.get(keyOfThis),reducerContext);
                // }
                //reducer.cleanup(reducerContext);

                //((NewReduceContextImpl)reducerContext).writeFinal();
            }catch (Exception e){
                e.printStackTrace();
            } finally{
                mainLock.unlock();
            }
        }
        void runNormal(){
            //start Event Fetcher
            //TODO : use a event to change this val in EventFetcher thread.
            mainLock.lock();
            Serializer<INVALUE>odv=serializationFactory.getSerializer(iValClass);
            String tmp=keyOfThis.toString();String tmpOutFile;
            if(tmp.length()>30)tmpOutFile=tmp.substring(0,30);
            else tmpOutFile=tmp;
            File tmpFile=new File(tmpOutDir,tmpOutFile);

            try {
                LOG.info("Creating tempFile " +tmpFile.getName());
                if(!tmpFile.exists())tmpFile.createNewFile();
                odv.open(new FileOutputStream(tmpFile,true));
                while (!mapAllFinished) {
                    while (keyValue.get(keyOfThis).size() > 0) {
                        odv.serialize(keyValue.get(keyOfThis).remove(0));
                    }
                }
                while (keyValue.get(keyOfThis).size() > 0) {
                    odv.serialize(keyValue.get(keyOfThis).remove(0));
                }
                org.apache.hadoop.mapreduce.Reducer.Context
                        reducerContext = createReduceContext(job,taskID,writer,committer,reporter,keyOfThis,tmpFile,iValClass);
                reducer.run(reducerContext);
            }catch (Exception e){
                e.printStackTrace();
            }finally {
                mainLock.unlock();
            }
            LOG.info("Reducer of "+ keyOfThis.toString()+ " complete and exits");
        }
    }


    class MultiLock implements Lock{
        int count;
        ReentrantLock countLock;

        public MultiLock() {
            count = 0;
            countLock = new ReentrantLock();
        }

        public int getLockNumber(){
            return count;
        }

        @Override
        public void lock() {
            countLock.lock();
            count = count + 1;
            countLock.unlock();
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {

        }

        @Override
        public boolean tryLock() {
            return false;
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return false;
        }

        @Override
        public void unlock() {
            countLock.lock();
            count=count-1;
            countLock.unlock();
        }

        @Override
        public Condition newCondition() {
            return null;
        }
    }

}

