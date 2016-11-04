package org.apache.hadoop.mapred;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableFactories;
import org.apache.hadoop.io.WritableFactory;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.mapreduce.TaskCounter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormatCounter;
import org.apache.hadoop.mapreduce.newShuffleImpl.NewShuffleDaemonProtocol;
import org.apache.hadoop.mapreduce.newShuffleImpl.ReceiverStatus;
import org.apache.hadoop.util.Progress;
import org.apache.hadoop.util.ReflectionUtils;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

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
    private int numMaps;

    private CompressionCodec codec;
    //RawComparator comparator;

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

    void queryReceiverStatus(){
        InetSocketAddress addr = new InetSocketAddress("localhost", 21116);
        boolean receiverRunning=true;
        NewShuffleDaemonProtocol proxy = null;
        try {
            proxy = RPC.waitForProxy(NewShuffleDaemonProtocol.class, 1, addr, new Configuration());
        } catch (IOException e) {
            e.printStackTrace();
        }
        JobID jobId = getJobID();
        while(receiverRunning) {
            try {
                Thread.sleep(1000);
                if (proxy.getLocalReceiverStatus(jobId) == ReceiverStatus.COMPLETE) {
                    receiverRunning = false;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }
    @Override
    public void run(JobConf job, TaskUmbilicalProtocol umbilical)
            throws IOException, ClassNotFoundException, InterruptedException {
        if (isMapOrReduce()) {
            copyPhase = getProgress().addPhase("copy");
            reducePhase = getProgress().addPhase("reduce");
        }
        TaskReporter reporter = startReporter(umbilical);
        LOG.info("seeing NewReduce Running YO");
        queryReceiverStatus();//spin in this function
        //Then we should start to sort the temporary files


        //Still Two types of ReduceTasks
        //FastStart will directly read data from Receiver's partial output files
            //And will desperately ZHENG QIANG DISK IO with ShuffleReceiver.
        //Normal will just wait for ShuffleReceiver's finish signal
            //Then start to Read and Sort and Reduce. May be multi-threaded.
        done(umbilical, reporter);
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



    class KVStream extends ByteArrayInputStream{
        //Exciting. This stream need not to be manually maintained
        public KVStream(byte[] buf,int off,int len){
            super(buf,off,len);
        }
    }
}

